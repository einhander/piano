#include "FluidSynthEngine.h"
#include <fluidsynth/synth.h>
#include <cstring>
#include <thread>
#include <chrono>

// ── Lock-free design (see FluidSynthEngine.h for the full rationale) ─────────
// ALL fluid_synth_* calls happen on the audio thread (the only thread that
// touches the synths), so no lock is needed on the audio path. SF2 load/unload
// (file I/O) stays on a worker thread and uses the double-buffered synth swap.

FluidSynthEngine::FluidSynthEngine() {
    for (int i = 0; i < 16; i++) {
        mChannelPrograms[i].store(-1, std::memory_order_relaxed);
    }
}

FluidSynthEngine::~FluidSynthEngine() {
    for (int i = 0; i < kSynthSlots; i++) {
        if (mSynth[i]) {
            delete_fluid_synth(mSynth[i]);
            mSynth[i] = nullptr;
        }
        if (mSettings[i]) {
            delete_fluid_settings(mSettings[i]);
            mSettings[i] = nullptr;
        }
    }
}

bool FluidSynthEngine::init(int sampleRate, int bufferSize) {
    // m8: free whatever was created if a later slot fails to allocate
    // (previously slot 0's settings/synth leaked when slot 1's failed).
    auto cleanup = [this]() {
        for (int j = 0; j < kSynthSlots; j++) {
            if (mSynth[j]) {
                delete_fluid_synth(mSynth[j]);
                mSynth[j] = nullptr;
            }
            if (mSettings[j]) {
                delete_fluid_settings(mSettings[j]);
                mSettings[j] = nullptr;
            }
        }
    };

    // Create BOTH synth slots with the given sample rate. The audio thread
    // renders from mSynth[mActiveIndex]; the worker prepares the other slot for
    // SF2 changes (double-buffered swap).
    for (int i = 0; i < kSynthSlots; i++) {
        mSettings[i] = new_fluid_settings();
        if (!mSettings[i]) {
            cleanup();
            return false;
        }
        fluid_settings_setnum(mSettings[i], "synth.sample-rate", sampleRate);
        fluid_settings_setint(mSettings[i], "synth.cpu-cores", 1);
        fluid_settings_setint(mSettings[i], "audio.periods", 2);

        mSynth[i] = new_fluid_synth(mSettings[i]);
        if (!mSynth[i]) {
            cleanup();
            return false;
        }

        // Disable FluidSynth audio driver — we render PCM ourselves via write_float
        fluid_settings_setstr(mSettings[i], "audio.driver", "none");

        // m4: pre-size the voice pool to the UI maximum (256) on the worker
        // thread so a runtime polyphony change (≤ 256) in the audio callback
        // never allocates (fluid_synth_set_polyphony only grows the pool when the
        // new value exceeds the current one). The user's setting (mPolyphony) is
        // the ACTIVE cap, applied by applyDesiredState below (a decrease → no
        // allocation).
        fluid_synth_set_polyphony(mSynth[i], 256);

        // Apply the default (desired) settings to both slots so they start in
        // sync. The audio thread keeps them in sync on later changes (and the
        // worker re-applies them to a prepared slot before a flip — M2).
        applyDesiredState(mSynth[i]);
    }

    mSampleRate = sampleRate;
    mBufferSize = bufferSize;
    mInitialized.store(true);
    return true;
}

// ── SF2 load/unload (worker thread — file I/O) ───────────────────────────────

// M2: apply the current desired state (polyphony/gain/reverb/chorus/interps/
// channel programs) to one synth slot. Worker thread, called BEFORE the flip
// so the new active slot has the correct settings when it becomes active.
void FluidSynthEngine::applyDesiredState(fluid_synth_t* synth) {
    if (!synth) return;
    fluid_synth_set_polyphony(synth, mPolyphony.load());
    fluid_synth_set_gain(synth, mGain.load());
    fluid_synth_reverb_on(synth, -1, mReverbOn.load());
    fluid_synth_chorus_on(synth, -1, mChorusOn.load());
    fluid_synth_set_interp_method(synth, -1, mInterps.load());
    // Channel programs (packed bank<<8|program; -1 = no explicit program).
    for (int ch = 0; ch < 16; ch++) {
        int packed = mChannelPrograms[ch].load(std::memory_order_relaxed);
        if (packed >= 0) {
            fluid_synth_bank_select(synth, ch, packed >> 8);
            fluid_synth_program_change(synth, ch, packed & 0xFF);
        }
    }
}

// M3: free the old active slot's SF2 (now inactive). Worker thread, called
// AFTER the flip. Waits (bounded) for the audio thread to finish its current
// callback (mSynthSeq even), then unloads the SF2. The audio thread provably
// doesn't touch the old active slot post-flip (render/processOneMidi/
// endSynthAccess all re-load mActiveIndex; processCommands skips the
// preparing slot). Bounded wait: the worker yields (it is NOT the audio
// callback, so yielding is fine).
void FluidSynthEngine::freeOldActiveSlotSf2(int oldActive) {
    // Wait for the audio thread to finish its current callback (mSynthSeq
    // even). Bounded: one callback period (~10ms); the worker yields.
    int spins = 0;
    while ((mSynthSeq.load(std::memory_order_acquire) & 1) && spins < 10000) {
        std::this_thread::yield();
        spins++;
    }
    fluid_synth_t* oldSynth = mSynth[oldActive];
    if (oldSynth) {
        while (fluid_synth_sfcount(oldSynth) > 0) {
            fluid_sfont_t* sfont = fluid_synth_get_sfont(oldSynth, 0);
            if (!sfont) break;
            int id = fluid_sfont_get_id(sfont);
            fluid_synth_sfunload(oldSynth, id, 1);
        }
    }
}

// Prepare the inactive synth slot for an SF2 change: unload all SF2s from it,
// then (if newSfPath != null) load the new one, apply the desired state, flip
// mActiveIndex so the audio thread picks up the new synth on the next
// callback, then free the old active slot's SF2 (M3). The target is the slot
// the audio thread is NOT rendering from, so this never races with the audio
// thread (M2: the mPreparing flag makes processCommands skip the target slot).
int FluidSynthEngine::prepareInactiveSlot(const char* newSfPath) {
    int target = 1 - mActiveIndex.load(std::memory_order_acquire);
    fluid_synth_t* synth = mSynth[target];
    if (!synth) {
        return -1;
    }

    // M2: mark the target slot as "preparing" so the audio thread's
    // processCommands skips it (no shared fluid_synth_t across threads).
    mPreparing[target].store(true, std::memory_order_release);

    // Unload all SoundFonts from the target slot
    while (fluid_synth_sfcount(synth) > 0) {
        fluid_sfont_t* sfont = fluid_synth_get_sfont(synth, 0);
        if (!sfont) break;
        int id = fluid_sfont_get_id(sfont);
        fluid_synth_sfunload(synth, id, 1);
    }

    int sfId = -1;
    if (newSfPath) {
        // Load SF2 — returns synthID, -1 on error. Worker thread only.
        // [perf]: measure the load duration (file I/O + parsing) for the
        // one-time [perf] dump.
        auto t0 = std::chrono::steady_clock::now();
        sfId = fluid_synth_sfload(synth, newSfPath, 1);
        auto t1 = std::chrono::steady_clock::now();
        mLastSf2LoadMs.store(
            std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count(),
            std::memory_order_relaxed);
    }

    // M2: apply the current desired state to the prepared slot BEFORE the flip
    // (so the new active slot has the correct settings when it becomes active).
    applyDesiredState(synth);

    // Flip the active index — the audio thread picks up the new synth on the
    // next callback. (Side effect: active voices are reset — acceptable, this
    // is a setup operation and the old code also reset presets on load.)
    int oldActive = mActiveIndex.load(std::memory_order_acquire);
    mActiveIndex.store(target, std::memory_order_release);

    // M3: free the old active slot's SF2 (now inactive) — keeps 1× SF2
    // resident instead of 2× (a 150MB SF2 would otherwise be 300MB on a 2GB
    // device).
    freeOldActiveSlotSf2(oldActive);

    // M2: clear the preparing flag (after the M3 free, so processCommands
    // skips the target slot for the entire preparation).
    mPreparing[target].store(false, std::memory_order_release);

    return sfId;
}

// M5: re-prepare the inactive synth slot at a NEW sample rate (worker thread).
// Frees the old synth in the target slot, creates a new synth at the new rate,
// loads the given SF2 (if any), applies the desired state, flips mActiveIndex,
// then frees the old active slot's SF2 (M3). Used on a mid-session rate change
// (e.g. BT device switch 44.1k→48k) — the init() path is idempotent, so a
// reopen at a new rate would otherwise leave the transport tempo + FluidSynth
// pitch off by the rate ratio.
void FluidSynthEngine::reprepareAtNewRate(int newRate, const char* sfPath) {
    if (!mInitialized.load()) return;
    // M1: serialize worker↔worker SF2 slot prep (covers prepare→flip→free).
    // Worker-thread blocking is allowed; the audio thread never takes this lock.
    std::lock_guard<std::mutex> workerLock(mWorkerMutex);
    int target = 1 - mActiveIndex.load(std::memory_order_acquire);

    // M2: mark the target slot as "preparing" so the audio thread's
    // processCommands skips it (no shared fluid_synth_t across threads).
    mPreparing[target].store(true, std::memory_order_release);

    // Free the old synth in the target slot (it's inactive, so the audio
    // thread doesn't touch it).
    if (mSynth[target]) {
        delete_fluid_synth(mSynth[target]);
        mSynth[target] = nullptr;
    }
    if (mSettings[target]) {
        delete_fluid_settings(mSettings[target]);
        mSettings[target] = nullptr;
    }

    // Create a new synth at the new rate.
    mSettings[target] = new_fluid_settings();
    if (mSettings[target]) {
        fluid_settings_setnum(mSettings[target], "synth.sample-rate", newRate);
        fluid_settings_setint(mSettings[target], "synth.cpu-cores", 1);
        fluid_settings_setint(mSettings[target], "audio.periods", 2);
        mSynth[target] = new_fluid_synth(mSettings[target]);
        if (mSynth[target]) {
            // Disable FluidSynth audio driver — we render PCM ourselves.
            fluid_settings_setstr(mSettings[target], "audio.driver", "none");
            // m4: pre-size the voice pool to the UI max (256) so runtime
            // polyphony changes (≤ 256) never allocate in the audio callback.
            // The user's setting (mPolyphony) is the active cap (applyDesiredState).
            fluid_synth_set_polyphony(mSynth[target], 256);
            // Load the current SF2 (if any) into the new synth.
            // [perf]: measure the reload duration for the one-time [perf] dump.
            if (sfPath) {
                auto t0 = std::chrono::steady_clock::now();
                fluid_synth_sfload(mSynth[target], sfPath, 1);
                auto t1 = std::chrono::steady_clock::now();
                mLastSf2LoadMs.store(
                    std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count(),
                    std::memory_order_relaxed);
            }
            // M2: apply the current desired state to the prepared slot.
            applyDesiredState(mSynth[target]);
        }
    }

    // Flip the active index — the audio thread picks up the new synth on the
    // next callback.
    int oldActive = mActiveIndex.load(std::memory_order_acquire);
    mActiveIndex.store(target, std::memory_order_release);

    // M3: free the old active slot's SF2 (now inactive).
    freeOldActiveSlotSf2(oldActive);

    // M2: clear the preparing flag.
    mPreparing[target].store(false, std::memory_order_release);

    // Update the sample rate.
    mSampleRate = newRate;
}

int FluidSynthEngine::loadSoundFont(const char* filePath) {
    if (!mInitialized.load()) {
        return -1;
    }
    // M1: serialize worker↔worker SF2 slot prep (covers prepare→flip→free).
    // Worker-thread blocking is allowed; the audio thread never takes this lock.
    std::lock_guard<std::mutex> workerLock(mWorkerMutex);
    int sfId = prepareInactiveSlot(filePath);
    if (sfId >= 0) {
        { std::lock_guard<std::mutex> lock(mSfPathMutex); mLoadedSfPath = filePath; }
    }
    return sfId;
}

void FluidSynthEngine::unloadSoundFonts() {
    if (!mInitialized.load()) return;
    // M1: serialize worker↔worker SF2 slot prep (covers prepare→flip→free).
    std::lock_guard<std::mutex> workerLock(mWorkerMutex);
    { std::lock_guard<std::mutex> lock(mSfPathMutex); mLoadedSfPath.clear(); }
    // Unload all SF2s from the inactive slot and flip to it (active = no SF2).
    prepareInactiveSlot(nullptr);
}

// ── Audio-thread entry points (NO locks) ─────────────────────────────────────

void FluidSynthEngine::beginSynthAccess() {
    // Sequence lock → odd (audio thread is mid-callback). getInstruments
    // (worker) retries while this is odd.
    mSynthSeq.fetch_add(1, std::memory_order_release);
}

void FluidSynthEngine::endSynthAccess() {
    // Refresh the active-voice count for diagnostics (within the synth access
    // region, so it is safe to read the active synth).
    int idx = mActiveIndex.load(std::memory_order_acquire);
    if (mSynth[idx]) {
        mActiveVoices.store(fluid_synth_get_active_voice_count(mSynth[idx]),
                            std::memory_order_relaxed);
    }
    // Sequence lock → even (audio thread is between callbacks).
    mSynthSeq.fetch_add(1, std::memory_order_release);
}

void FluidSynthEngine::processCommands() {
    SynthCmd cmd;
    while (mCmdQueue.pop(cmd)) {
        // Apply to each synth slot, but SKIP slots the worker is currently
        // preparing (M2: mPreparing set) — the FluidSynth C API is not
        // thread-safe, so the audio thread and worker must never touch the
        // same fluid_synth_t concurrently. The worker applies the desired
        // state to the prepared slot before the flip (see prepareInactiveSlot),
        // so the skipped slot is not left stale.
        for (int i = 0; i < kSynthSlots; i++) {
            if (mSynth[i] && !mPreparing[i].load(std::memory_order_acquire)) {
                applyCommandToSynth(mSynth[i], cmd);
            }
        }
    }
}

void FluidSynthEngine::applyCommandToSynth(fluid_synth_t* synth, const SynthCmd& cmd) {
    switch (cmd.type) {
        case SynthCmdType::NOTE_ON:
            fluid_synth_noteon(synth, cmd.a, cmd.b, static_cast<int>(cmd.f));
            break;
        case SynthCmdType::NOTE_OFF:
            fluid_synth_noteoff(synth, cmd.a, cmd.b);
            break;
        case SynthCmdType::CC:
            fluid_synth_cc(synth, cmd.a, cmd.b, static_cast<int>(cmd.f));
            break;
        case SynthCmdType::PROGRAM_CHANGE:
            fluid_synth_program_change(synth, cmd.a, cmd.b);
            break;
        case SynthCmdType::PITCH_BEND:
            fluid_synth_pitch_bend(synth, cmd.a, cmd.b);
            break;
        case SynthCmdType::CHANNEL_PRESSURE:
            fluid_synth_channel_pressure(synth, cmd.a, cmd.b);
            break;
        case SynthCmdType::PANIC:
            // Send panic commands to FluidSynth
            for (int ch = 0; ch < 16; ch++) {
                fluid_synth_all_notes_off(synth, ch);
                fluid_synth_all_sounds_off(synth, ch);
                fluid_synth_cc(synth, ch, 123, 0);  // MIDI reset
                fluid_synth_cc(synth, ch, 120, 0);  // All notes off
                fluid_synth_cc(synth, ch, 64, 0);   // Sustain off
            }
            // B3: panic killed all voices — including the user's held keyboard
            // notes. Clear the held-note state so a later file/flush note-off on
            // the same (ch, note) cannot re-arm a note after an explicit panic
            // (the user must re-press the key to re-trigger it). Audio-thread-only
            // state (no lock). Idempotent if applied to both slots.
            std::memset(mHeldNotes, 0, sizeof(mHeldNotes));
            std::memset(mHeldVel, 0, sizeof(mHeldVel));
            break;
        case SynthCmdType::SET_POLYPHONY:
            fluid_synth_set_polyphony(synth, cmd.a);
            break;
        case SynthCmdType::SET_GAIN:
            fluid_synth_set_gain(synth, cmd.f);
            break;
        case SynthCmdType::SET_REVERB:
            fluid_synth_reverb_on(synth, -1, cmd.a);
            break;
        case SynthCmdType::SET_CHORUS:
            fluid_synth_chorus_on(synth, -1, cmd.a);
            break;
        case SynthCmdType::SET_INTERPS:
            fluid_synth_set_interp_method(synth, -1, cmd.a);
            break;
        case SynthCmdType::SET_CHANNEL_PROGRAM:
            if (cmd.a >= 0 && cmd.a <= 15) {
                // fluid_synth_bank_select handles bank style (GM/GS/XG) and the
                // full 0-16383 range; raw CC0 only covers 0-127.
                fluid_synth_bank_select(synth, cmd.a, cmd.b);
                fluid_synth_program_change(synth, cmd.a, static_cast<int>(cmd.f));
            }
            break;
    }
}

// Process one live MIDI message into the active synth (with held-note tracking
// + re-arm). Audio thread only (inside the synth access region). No locks —
// the held-note bitmap is audio-thread-only state.
//
// Convention (see MidiQueue.h): timestamp == 0 → live keyboard event;
// timestamp > 0 → file/player event (MidiFilePlayer). The held-note bitmap is
// updated for live events only; the re-arm check after any note-off is
// source-agnostic.
void FluidSynthEngine::processOneMidi(const MidiMessage& m) {
    if (!mInitialized.load()) return;
    int idx = mActiveIndex.load(std::memory_order_acquire);
    fluid_synth_t* synth = mSynth[idx];
    if (!synth) return;

    uint8_t status = m.status;
    uint8_t type = status & 0xF0;
    uint8_t channel = status & 0x0F;
    const bool isLive = (m.timestamp == 0);
    bool noteOff = false;

    switch (type) {
        case 0x90: // Note On / Note Off
            if (m.data2 > 0) {
                fluid_synth_noteon(synth, channel, m.data1, m.data2);
                if (isLive) {
                    mHeldNotes[channel][m.data1] = 1;
                    mHeldVel[channel][m.data1] = m.data2;
                }
            } else {
                // Clear the bitmap BEFORE the re-arm check: a keyboard's own
                // note-off must not re-arm itself.
                if (isLive) {
                    mHeldNotes[channel][m.data1] = 0;
                }
                fluid_synth_noteoff(synth, channel, m.data1);
                noteOff = true;
            }
            break;
        case 0x80: // Note Off
            if (isLive) {
                mHeldNotes[channel][m.data1] = 0;
            }
            fluid_synth_noteoff(synth, channel, m.data1);
            noteOff = true;
            break;
        case 0xA0: // Polyphonic Aftertouch
            fluid_synth_key_pressure(synth, channel, m.data1, m.data2);
            break;
        case 0xB0: // Control Change
            fluid_synth_cc(synth, channel, m.data1, m.data2);
            break;
        case 0xC0: // Program Change
            fluid_synth_program_change(synth, channel, m.data1);
            // Track the live program change in the desired channel program so
            // getChannelProgram reflects it (bank unchanged).
            if (channel >= 0 && channel <= 15) {
                int packed = mChannelPrograms[channel].load(std::memory_order_relaxed);
                int bank = packed >= 0 ? (packed >> 8) : 0;
                mChannelPrograms[channel].store((bank << 8) | m.data1,
                                                std::memory_order_relaxed);
            }
            break;
        case 0xD0: // Channel Aftertouch
            fluid_synth_channel_pressure(synth, channel, m.data1);
            break;
        case 0xE0: // Pitch Bend
            {
                int16_t value = static_cast<int16_t>(
                    (m.data2 << 7) | m.data1
                );
                fluid_synth_pitch_bend(synth, channel, value);
            }
            break;
    }

    // B3: re-arm (source-agnostic). Any note-off — keyboard, file normal
    // note-off, or file flush — releases ALL voices for (ch, note), including
    // the user's currently-held keyboard note on the shared channel. If the
    // bitmap says the user is still holding it, re-issue the note-on at the
    // last keyboard velocity. Direct synth call (not queued) → the MIDI
    // recorder, which consumes the queue, never sees the re-arm artifact.
    if (noteOff && mHeldNotes[channel][m.data1]) {
        fluid_synth_noteon(synth, channel, m.data1, mHeldVel[channel][m.data1]);
        mRearmCount++;
        mLastRearmVel = mHeldVel[channel][m.data1];
    }
}

void FluidSynthEngine::processLiveMidi(const std::vector<MidiMessage>& batch) {
    if (!mInitialized.load() || batch.empty()) return;
    for (const auto& m : batch) {
        processOneMidi(m);
    }
}

void FluidSynthEngine::render(float* output, int numFrames) {
    if (!mInitialized.load()) {
        std::memset(output, 0, numFrames * 2 * sizeof(float));
        return;
    }
    int idx = mActiveIndex.load(std::memory_order_acquire);
    fluid_synth_t* synth = mSynth[idx];
    if (!synth) {
        std::memset(output, 0, numFrames * 2 * sizeof(float));
        return;
    }
    // Render stereo float buffer directly (no lock — audio thread only).
    fluid_synth_write_float(synth, numFrames, output, 0, 2, output, 1, 2);
}

// ── Worker-thread entry points (JNI/settings) — all enqueue, no locks ────────

static inline void makeCmd(SynthCmd& c, SynthCmdType t, int a = 0, int b = 0, float f = 0.0f) {
    c.type = t;
    c.a = a;
    c.b = b;
    c.f = f;
}

void FluidSynthEngine::noteOn(int channel, int note, int velocity) {
    if (!mInitialized.load()) return;
    SynthCmd c;
    makeCmd(c, SynthCmdType::NOTE_ON, channel, note, static_cast<float>(velocity));
    mCmdQueue.push(c);
}

void FluidSynthEngine::noteOff(int channel, int note) {
    if (!mInitialized.load()) return;
    SynthCmd c;
    makeCmd(c, SynthCmdType::NOTE_OFF, channel, note);
    mCmdQueue.push(c);
}

void FluidSynthEngine::controlChange(int channel, int controller, int value) {
    if (!mInitialized.load()) return;
    SynthCmd c;
    makeCmd(c, SynthCmdType::CC, channel, controller, static_cast<float>(value));
    mCmdQueue.push(c);
}

void FluidSynthEngine::programChange(int channel, int program) {
    if (!mInitialized.load()) return;
    SynthCmd c;
    makeCmd(c, SynthCmdType::PROGRAM_CHANGE, channel, program);
    mCmdQueue.push(c);
}

void FluidSynthEngine::pitchBend(int channel, int value) {
    if (!mInitialized.load()) return;
    SynthCmd c;
    makeCmd(c, SynthCmdType::PITCH_BEND, channel, value);
    mCmdQueue.push(c);
}

void FluidSynthEngine::channelPressure(int channel, int value) {
    if (!mInitialized.load()) return;
    SynthCmd c;
    makeCmd(c, SynthCmdType::CHANNEL_PRESSURE, channel, value);
    mCmdQueue.push(c);
}

void FluidSynthEngine::panic() {
    if (!mInitialized.load()) return;
    SynthCmd c;
    makeCmd(c, SynthCmdType::PANIC);
    mCmdQueue.push(c);
}

void FluidSynthEngine::setPolyphony(int polyphony) {
    if (!mInitialized.load()) return;
    mPolyphony.store(polyphony, std::memory_order_relaxed);
    SynthCmd c;
    makeCmd(c, SynthCmdType::SET_POLYPHONY, polyphony);
    mCmdQueue.push(c);
}

void FluidSynthEngine::setMasterGain(float gain) {
    if (!mInitialized.load()) return;
    mGain.store(gain, std::memory_order_relaxed);
    SynthCmd c;
    makeCmd(c, SynthCmdType::SET_GAIN, 0, 0, gain);
    mCmdQueue.push(c);
}

void FluidSynthEngine::setReverb(bool on) {
    if (!mInitialized.load()) return;
    mReverbOn.store(on ? 1 : 0, std::memory_order_relaxed);
    SynthCmd c;
    makeCmd(c, SynthCmdType::SET_REVERB, on ? 1 : 0);
    mCmdQueue.push(c);
}

void FluidSynthEngine::setChorus(bool on) {
    if (!mInitialized.load()) return;
    mChorusOn.store(on ? 1 : 0, std::memory_order_relaxed);
    SynthCmd c;
    makeCmd(c, SynthCmdType::SET_CHORUS, on ? 1 : 0);
    mCmdQueue.push(c);
}

void FluidSynthEngine::setInterps(int method) {
    if (!mInitialized.load()) return;
    mInterps.store(method, std::memory_order_relaxed);
    SynthCmd c;
    makeCmd(c, SynthCmdType::SET_INTERPS, method);
    mCmdQueue.push(c);
}

bool FluidSynthEngine::setChannelProgram(int channel, int bank, int program) {
    if (!mInitialized.load()) return false;
    if (channel < 0 || channel > 15) return false;
    if (bank < 0) bank = 0;
    if (bank > 16383) bank = 16383;
    if (program < 0 || program > 127) program = 0;
    mChannelPrograms[channel].store((bank << 8) | program, std::memory_order_relaxed);
    SynthCmd c;
    makeCmd(c, SynthCmdType::SET_CHANNEL_PROGRAM, channel, bank, static_cast<float>(program));
    return mCmdQueue.push(c);
}

bool FluidSynthEngine::getChannelProgram(int channel, int& bank, int& program) const {
    if (!mInitialized.load()) return false;
    if (channel < 0 || channel > 15) return false;
    int packed = mChannelPrograms[channel].load(std::memory_order_relaxed);
    if (packed < 0) {
        bank = 0;
        program = 0;
        return true;
    }
    bank = packed >> 8;
    program = packed & 0xFF;
    return true;
}

// ── Getters (worker thread — atomic reads of the desired settings) ───────────

int FluidSynthEngine::getPolyphony() const { return mPolyphony.load(); }
float FluidSynthEngine::getMasterGain() const { return mGain.load(); }
int FluidSynthEngine::getReverb() const { return mReverbOn.load(); }
int FluidSynthEngine::getChorus() const { return mChorusOn.load(); }
int FluidSynthEngine::getInterps() const { return mInterps.load(); }
int FluidSynthEngine::getActiveVoices() const { return mActiveVoices.load(); }

int FluidSynthEngine::getSoundFontCount() const {
    // Read the active synth's SF2 count under the sequence lock (the audio
    // thread may be rendering from it). Spin (with yield) until consistent.
    while (true) {
        uint32_t s1 = mSynthSeq.load(std::memory_order_acquire);
        if (s1 & 1) {
            std::this_thread::yield();
            continue;
        }
        int idx = mActiveIndex.load(std::memory_order_acquire);
        int count = mSynth[idx] ? fluid_synth_sfcount(mSynth[idx]) : 0;
        uint32_t s2 = mSynthSeq.load(std::memory_order_acquire);
        if (s1 == s2) {
            return count;
        }
        // Inconsistent read — retry
    }
}

std::string FluidSynthEngine::getSoundFontPath() const {
    std::lock_guard<std::mutex> lock(mSfPathMutex);
    return mLoadedSfPath;
}

std::vector<InstrumentInfo> FluidSynthEngine::getInstruments() const {
    if (!mInitialized.load()) return {};
    std::vector<InstrumentInfo> result;
    // Enumerate the active synth's presets under the sequence lock (the audio
    // thread may be rendering from it). Spin (with yield) until a consistent
    // read. One-shot UI operation; not for playback.
    while (true) {
        uint32_t s1 = mSynthSeq.load(std::memory_order_acquire);
        if (s1 & 1) {
            std::this_thread::yield();
            continue;
        }
        int idx = mActiveIndex.load(std::memory_order_acquire);
        fluid_synth_t* synth = mSynth[idx];
        if (synth) {
            result.reserve(256);
            int sfCount = fluid_synth_sfcount(synth);
            for (int i = 0; i < sfCount; i++) {
                fluid_sfont_t* sfont = fluid_synth_get_sfont(synth, i);
                if (!sfont) continue;
                fluid_sfont_iteration_start(sfont);
                fluid_preset_t* preset;
                while ((preset = fluid_sfont_iteration_next(sfont)) != nullptr) {
                    const char* name = fluid_preset_get_name(preset);
                    result.push_back({name ? name : "",
                                      fluid_preset_get_banknum(preset),
                                      fluid_preset_get_num(preset)});
                }
            }
        }
        uint32_t s2 = mSynthSeq.load(std::memory_order_acquire);
        if (s1 == s2) {
            break;  // consistent read
        }
        // Inconsistent read — discard and retry
        result.clear();
    }
    return result;
}