#pragma once

#include <fluidsynth.h>
#include "realtime/MidiQueue.h"
#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>
#include <unordered_map>
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
class FluidSynthEngine {
public:
    FluidSynthEngine();
    ~FluidSynthEngine();

    // Non-copyable
    FluidSynthEngine(const FluidSynthEngine&) = delete;
    FluidSynthEngine& operator=(const FluidSynthEngine&) = delete;

    // Initialize FluidSynth (must be called before use)
    bool init(int sampleRate, int bufferSize);

    // Load SoundFont 2 file
    // Returns synthID on success, -1 on failure
    int loadSoundFont(const char* filePath);

    // Unload all SoundFonts
    void unloadSoundFonts();

    // Render PCM float buffer (stereo)
    // Called from audio callback — NO allocations
    void render(float* output, int numFrames);

    // MIDI events — all safe for audio thread
    void noteOn(int channel, int note, int velocity);
    void noteOff(int channel, int note);
    void controlChange(int channel, int controller, int value);
    void programChange(int channel, int program);
    void pitchBend(int channel, int value);  // 0-16383
    void channelPressure(int channel, int value);

    // Panic: all notes off, all controllers reset
    void panic();

    // Process live MIDI from queue — called from MIDI thread (NOT audio callback)
    void processLiveMidi(MidiQueue* queue);

    // Process live MIDI from a pre-drained batch — called from MIDI thread (NOT audio callback)
    // Used when the queue needs to be drained once and shared between recorder + synth.
    void processLiveMidi(const std::vector<MidiMessage>& batch);

    // Set polyphony
    void setPolyphony(int polyphony);

    // Set master gain
    void setMasterGain(float gain);

    // Get sample rate
    int getSampleRate() const { return mSampleRate; }

    // Check if initialized
    bool isInitialized() const { return mInitialized; }

    // Getters
    int getPolyphony() const;
    float getMasterGain() const;
    int getSoundFontCount() const;
    std::string getSoundFontPath() const;

    // Enumerate all presets of all loaded SoundFonts (settings thread, NOT audio callback).
    // Holds mSynthMutex for the whole enumeration — one-shot UI operation, not for playback.
    std::vector<InstrumentInfo> getInstruments() const;

    // Set bank + program on a channel (settings thread, NOT audio callback).
    // Returns false if not applied (not initialized, no synth, or invalid channel).
    bool setChannelProgram(int channel, int bank, int program);

    // Get the bank + program currently set on a channel (settings thread, NOT audio callback).
    // Returns false if unavailable; bank/program are 0 when the channel has no explicit program.
    bool getChannelProgram(int channel, int& bank, int& program) const;

    // B3 test seam: number of re-arm note-ons issued by processLiveMidi.
    // Written on the MIDI thread only; read by the host test (single-threaded).
    int getRearmCount() const { return mRearmCount; }

    // B3 test seam: velocity of the last re-arm note-on (-1 = never re-armed).
    // Written on the MIDI thread only; read by the host test (single-threaded).
    int getLastRearmVelocity() const { return mLastRearmVel; }

private:
    fluid_synth_t* mSynth = nullptr;
    fluid_settings_t* mSettings = nullptr;
    int mSampleRate = 48000;
    int mBufferSize = 0;
    std::atomic<bool> mInitialized{false};
    // Note: tracks only the last-loaded SF2 path. Multi-SF2 support not yet implemented.
    std::string mLoadedSfPath;
    mutable std::mutex mSfPathMutex;

    // Protects all mSynth access across audio callback, MIDI thread, and settings/JNI threads.
    // FluidSynth C API is NOT thread-safe; this mutex serializes concurrent access.
    // Audio callback holds it briefly during render(); MIDI thread during live MIDI processing;
    // settings thread during polyphony/gain/SF2 changes. Settings changes are infrequent,
    // so lock contention is minimal. Audio callback hold time is bounded by buffer size.
    mutable std::mutex mSynthMutex;

    // B3: live-keyboard held-note tracking.
    // MIDI-thread-only (processLiveMidi), pre-allocated, zeroed at construction, no locks.
    // mHeldNotes[ch][note] == 1 → the user is currently holding that key;
    // mHeldVel[ch][note] → last keyboard velocity for that (ch, note).
    // A file/flush note-off for a held (ch, note) re-arms it with a direct
    // fluid_synth_noteon (not queued → invisible to the MIDI recorder).
    uint8_t mHeldNotes[16][128]{};
    uint8_t mHeldVel[16][128]{};

    // B3 test seam: re-arm note-on counter (MIDI-thread-only, plain int).
    int mRearmCount = 0;
    // B3 test seam: last re-armed velocity (MIDI-thread-only, plain int).
    int mLastRearmVel = -1;
};
