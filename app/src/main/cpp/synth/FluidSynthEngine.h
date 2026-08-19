#pragma once

#include <fluidsynth.h>
#include "realtime/MidiQueue.h"
#include "realtime/SynthCmdQueue.h"
#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

// One instrument preset from a loaded SoundFont
struct InstrumentInfo {
    std::string name;
    int bank;
    int program;
};

// FluidSynth adapter — renders PCM from MIDI events
// NOT using FluidSynth audio driver, only library mode
// Each track maps to one MIDI channel
//
// ── Lock-free design (ZERO locks on the audio path) ─────────────────────────
//
// The FluidSynth C API is NOT thread-safe. The old design serialized every
// access with mSynthMutex, which the audio callback held during render(). A
// MIDI/settings thread calling fluid_synth_* (voice allocation takes internal
// locks) would then block the audio callback — the root cause of the
// underruns/clipping on weak CPUs.
//
// New design:
//  1. ALL fluid_synth_* calls happen on the audio thread. Live MIDI arrives
//     via the lock-free mMidiQueue (drained in the callback); control commands
//     (polyphony/gain/reverb/chorus/interps/channel program/panic/direct
//     notes) arrive via the lock-free mCmdQueue (SynthCmdQueue), also drained
//     in the callback. The audio thread is the only thread that touches the
//     synths, so no lock is needed.
//  2. SF2 load/unload must stay on a worker thread (file I/O). They use a
//     double-buffered synth: two pre-allocated fluid_synth_t slots. The worker
//     prepares the INACTIVE slot (the one the audio thread is not rendering
//     from) and then atomically flips mActiveIndex. The audio thread picks up
//     the new synth on the next callback. No waiting, no locks. (Side effect:
//     loading/unloading an SF2 resets the active voices — acceptable, it is a
//     setup operation, and the old code also reset presets on load.)
//  3. getInstruments() (SF2 preset enumeration, worker thread) reads the active
//     synth. It is synchronized with the audio thread via a sequence lock
//     (mSynthSeq): the audio thread increments it around its synth access
//     (beginSynthAccess/endSynthAccess); the reader retries until it sees a
//     stable (even) sequence, so it never reads a half-updated synth.
//
// The held-note bitmap (mHeldNotes/mHeldVel) is audio-thread-only state
// (processOneMidi), pre-allocated, no locks.
class FluidSynthEngine {
public:
    FluidSynthEngine();
    ~FluidSynthEngine();

    // Non-copyable
    FluidSynthEngine(const FluidSynthEngine&) = delete;
    FluidSynthEngine& operator=(const FluidSynthEngine&) = delete;

    // Initialize FluidSynth (must be called before use).
    // Creates BOTH synth slots with the given sample rate.
    bool init(int sampleRate, int bufferSize);

    // Load SoundFont 2 file (WORKER THREAD — does file I/O).
    // Returns synthID on success, -1 on failure.
    int loadSoundFont(const char* filePath);

    // Unload all SoundFonts (WORKER THREAD).
    void unloadSoundFonts();

    // ── Audio-thread entry points (called from the audio callback, NO locks) ──

    // Begin the audio thread's synth access (sequence lock → odd).
    // Must be paired with endSynthAccess(). All fluid_synth_* calls made by
    // the audio thread (processCommands/processOneMidi/render) go between
    // beginSynthAccess() and endSynthAccess().
    void beginSynthAccess();

    // End the audio thread's synth access (sequence lock → even).
    // Also refreshes the active-voice count for diagnostics.
    void endSynthAccess();

    // Drain the control command queue and apply each command to BOTH synth
    // slots (so the inactive slot is ready when it becomes active).
    // Audio thread only (inside the synth access region).
    void processCommands();

    // Process one live MIDI message into the active synth (with held-note
    // tracking + re-arm). Audio thread only (inside the synth access region).
    // No locks — the held-note bitmap is audio-thread-only state.
    void processOneMidi(const MidiMessage& m);

    // Batch overload — processes a pre-drained vector. Kept for the host
    // regression test (single-threaded) and any non-audio caller.
    void processLiveMidi(const std::vector<MidiMessage>& batch);

    // Render PCM float buffer (stereo) from the active synth.
    // Audio thread only (inside the synth access region). NO allocations.
    void render(float* output, int numFrames);

    // ── Worker-thread entry points (JNI/settings) — all enqueue, no locks ──

    // MIDI events — enqueued to the lock-free command queue (applied by the
    // audio thread). Safe from any thread.
    void noteOn(int channel, int note, int velocity);
    void noteOff(int channel, int note);
    void controlChange(int channel, int controller, int value);
    void programChange(int channel, int program);
    void pitchBend(int channel, int value);  // 0-16383
    void channelPressure(int channel, int value);

    // Panic: all notes off, all controllers reset (enqueued).
    void panic();

    // Set polyphony (enqueued).
    void setPolyphony(int polyphony);

    // Set master gain (enqueued).
    void setMasterGain(float gain);

    // Set reverb on/off (enqueued).
    void setReverb(bool on);

    // Set chorus on/off (enqueued).
    void setChorus(bool on);

    // Set interpolation method (enqueued): 0=none, 1=linear, 4=4th order.
    void setInterps(int method);

    // Set bank + program on a channel (enqueued).
    // Returns true if the command was enqueued (applied by the audio thread).
    bool setChannelProgram(int channel, int bank, int program);

    // Get the bank + program currently set on a channel (worker thread).
    // Returns the desired (enqueued) value, which also tracks live MIDI
    // program changes (0xC0) applied by the audio thread.
    bool getChannelProgram(int channel, int& bank, int& program) const;

    // Get sample rate
    int getSampleRate() const { return mSampleRate; }

    // Check if initialized
    bool isInitialized() const { return mInitialized; }

    // Getters (worker thread — atomic reads of the desired settings; the
    // audio thread applies them to the synths on the next callback).
    int getPolyphony() const;
    float getMasterGain() const;
    int getReverb() const;
    int getChorus() const;
    int getInterps() const;
    int getActiveVoices() const;
    // Number of control commands dropped (queue overflow). Worker-thread read.
    int getCmdQueueDrops() const { return static_cast<int>(mCmdQueue.droppedCount()); }
    int getSoundFontCount() const;
    std::string getSoundFontPath() const;

    // [perf]: duration (ms) of the most recent SF2 load (worker-thread write,
    // atomic read). 0 = no SF2 loaded yet. Surfaced in the one-time [perf] dump.
    int64_t getSf2LoadMs() const { return mLastSf2LoadMs.load(); }

    // M5: re-prepare the inactive synth slot at a NEW sample rate (worker
    // thread). Frees the old synth in the target slot, creates a new synth at
    // the new rate, loads the given SF2 (if any), applies the desired state,
    // flips mActiveIndex, then frees the old active slot's SF2 (M3). Used on a
    // mid-session rate change (e.g. BT device switch 44.1k→48k) — the init()
    // path is idempotent, so a reopen at a new rate would otherwise leave the
    // transport tempo + FluidSynth pitch off by the rate ratio.
    void reprepareAtNewRate(int newRate, const char* sfPath);

    // Enumerate all presets of all loaded SoundFonts (worker thread, NOT audio
    // callback). Synchronized with the audio thread via the sequence lock.
    std::vector<InstrumentInfo> getInstruments() const;

    // B3 test seam: number of re-arm note-ons issued by processOneMidi.
    // Written on the audio thread (or the test thread); read by the host test.
    int getRearmCount() const { return mRearmCount; }

    // B3 test seam: velocity of the last re-arm note-on (-1 = never re-armed).
    int getLastRearmVelocity() const { return mLastRearmVel; }

private:
    // Apply a single control command to one synth slot.
    void applyCommandToSynth(fluid_synth_t* synth, const SynthCmd& cmd);

    // Prepare the inactive synth slot for an SF2 change (worker thread):
    // unload all SF2s from it, then (if newSfPath != null) load the new one.
    // Returns the new SF2 id or -1.
    int prepareInactiveSlot(const char* newSfPath);

    // M2: apply the current desired state (polyphony/gain/reverb/chorus/
    // interps/channel programs) to one synth slot. Worker thread, called
    // BEFORE the flip so the new active slot has the correct settings.
    void applyDesiredState(fluid_synth_t* synth);

    // M3: free the old active slot's SF2 (now inactive). Worker thread, called
    // AFTER the flip. Waits (bounded) for the audio thread to finish its
    // current callback (mSynthSeq even), then unloads the SF2. The audio
    // thread provably doesn't touch the old active slot post-flip.
    void freeOldActiveSlotSf2(int oldActive);

    static constexpr int kSynthSlots = 2;

    // Double-buffered synths (see class comment). mActiveIndex selects the
    // slot the audio thread renders from; the worker prepares the other.
    fluid_synth_t* mSynth[kSynthSlots] = {nullptr, nullptr};
    fluid_settings_t* mSettings[kSynthSlots] = {nullptr, nullptr};
    std::atomic<int> mActiveIndex{0};

    // M2: per-slot "preparing" flag. The worker sets it on the target slot
    // BEFORE preparing and clears it AFTER the flip (+ M3 free). The audio
    // thread's processCommands SKIPS slots with the flag set, so the audio
    // thread and worker never touch the same fluid_synth_t concurrently
    // (FluidSynth C API is not thread-safe).
    std::atomic<bool> mPreparing[kSynthSlots] = {false, false};

    // Sequence lock for getInstruments (worker reads the active synth).
    // The audio thread increments it around its synth access (begin/end).
    // Even = between callbacks (safe to read), odd = mid-callback (retry).
    std::atomic<uint32_t> mSynthSeq{0};

    int mSampleRate = 48000;
    int mBufferSize = 0;
    std::atomic<bool> mInitialized{false};

    // Lock-free control command queue (worker → audio thread).
    SynthCmdQueue mCmdQueue{256};

    // Desired settings. Worker threads write these (via the enqueue methods);
    // the audio thread applies them to the synths; getters read the atomics.
    std::atomic<int> mPolyphony{64};
    std::atomic<float> mGain{1.0f};
    std::atomic<int> mReverbOn{1};
    std::atomic<int> mChorusOn{1};
    std::atomic<int> mInterps{4};
    // Packed bank<<8|program per channel; -1 = no explicit program.
    std::atomic<int> mChannelPrograms[16];

    // Active voice count (audio thread refreshes after each render).
    std::atomic<int> mActiveVoices{0};

    // Note: tracks only the last-loaded SF2 path. Multi-SF2 support not yet implemented.
    std::string mLoadedSfPath;
    mutable std::mutex mSfPathMutex;

    // [perf]: duration (ms) of the most recent fluid_synth_sfload (worker
    // thread writes, diagnostics read). 0 = no SF2 loaded yet.
    std::atomic<int64_t> mLastSf2LoadMs{0};

    // B3: live-keyboard held-note tracking.
    // AUDIO-THREAD-ONLY (processOneMidi), pre-allocated, zeroed at construction, no locks.
    // mHeldNotes[ch][note] == 1 → the user is currently holding that key;
    // mHeldVel[ch][note] → last keyboard velocity for that (ch, note).
    // A file/flush note-off for a held (ch, note) re-arms it with a direct
    // fluid_synth_noteon (not queued → invisible to the MIDI recorder).
    uint8_t mHeldNotes[16][128]{};
    uint8_t mHeldVel[16][128]{};

    // B3 test seam: re-arm note-on counter (audio-thread-only, plain int).
    int mRearmCount = 0;
    // B3 test seam: last re-armed velocity (audio-thread-only, plain int).
    int mLastRearmVel = -1;
};