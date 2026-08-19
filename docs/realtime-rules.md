# Real-Time Rules

## Audio Callback (Oboe) — FORBIDDEN

Inside the Oboe audio callback, the following are strictly prohibited:

- `new`, `delete`, `malloc`, `free` — no dynamic memory allocation
- `mutex`, `condition_variable` — no blocking synchronization
- File I/O — no file operations
- `log` / `printf` — no logging
- JNI calls — no Java/Kotlin interop
- Kotlin calls — no Kotlin interop
- SoundFont loading — no asset loading
- MP3/FLAC decoding — no decoding
- Room database access — no database operations
- Complex container mutations — no vector resizing, map insertions
- Blocking syscalls — no futex, waitpid, etc.

All buffers must be pre-allocated. Commands flow via lock-free queues.

## Time Source

Single source of musical time: audio frame counter.

```cpp
struct TransportState {
    int64_t framePosition = 0;
    double sampleRate = 48000.0;
    double bpm = 120.0;
    int ppq = 960;
    int numerator = 4;
    int denominator = 4;
    bool playing = false;
};
```

No `System.currentTimeMillis`, `System.nanoTime`, `Handler.postDelayed`, `delay`, `Timer`, or internal FluidSynth sequencer.

## Sample-Accurate Scheduling

Each Oboe callback:

1. Get `currentFrame`
2. Calculate `endFrame = currentFrame + numFrames`
3. Find events between `currentFrame` and `endFrame`
4. Render audio up to first event
5. Apply event
6. Render to next event
7. Mix audio clips
8. Process master bus
9. Write buffer to Oboe output
10. Advance `framePosition`

Events sorted by `targetFrame` within each callback.

## MIDI Handling

Live MIDI: message → lock-free queue → audio callback → FluidSynth
(`processOneMidi`). **There is no condition variable anywhere in the MIDI path**
— the audio thread drains the lock-free queue directly. The separate MIDI thread
polls with a 2 ms sleep and is **recording-only** (it never feeds the synth, so
the audio callback never touches a mutex/condvar to wake it).

No quantization unless user explicitly enables it.

Recorded MIDI: store original timestamp → convert to transport tick.

## Panic Command

For all MIDI channels:
- CC 64 = 0 (sustain off)
- CC 120 = 0 (all notes off)
- CC 121 = 0 (all sounds off)
- CC 123 = 0 (mono off)

Additionally, disable all tracked active notes in the engine.

## FluidSynth Integration

- Library mode only, NOT audio driver
- Render PCM float buffer → Mixer → Oboe
- No internal sequencer
- **All `fluid_synth_*` calls run on the audio thread** (the FluidSynth C API is
  not thread-safe). Live MIDI is drained from the lock-free queue in the
  callback; control commands (polyphony / gain / reverb / chorus / interps /
  channel program / panic / direct notes) arrive via the lock-free
  **SynthCmdQueue** (worker → audio; overflow → dropped + counter).
- **Double-buffered synth slots** (`mSynth[2]`): SF2 load/unload happens on the
  worker thread (file I/O) into the *inactive* slot, then swaps at the callback
  boundary (no wait, no lock).
- **`mPreparing[2]`** atomic flag: the audio thread skips the slot being
  prepared; the worker applies the desired state (from atomics) to the prepared
  slot *before* the flip. After the flip the worker frees the old slot's SF2
  (bounded wait for the sequence lock to go even) → **1× SF2 resident** (not 2×).
- A **sequence lock** (`mSynthSeq`) guards the worker-thread
  `getInstruments` / `getSoundFontCount` reads of the active synth.
- Each track → one MIDI channel (Drums → channel 10)

## Performance hints (ADPF) — NOT USED

Android's Adaptive Performance (`APerformanceHintManager` / Oboe's
`setPerformanceHintEnabled` + `reportWorkload`) is **deliberately not used**.
`setPerformanceHintEnabled(true)` is **never called in this project**, so the
audio callback is clean (the `begin/endPerformanceHintInCallback` wrappers are
no-ops when ADPF is disabled).

If it WERE enabled, Oboe 1.10.2 wraps *every* user data callback with
`begin/endPerformanceHintInCallback`, which:

- takes a **mutex**,
- on first use does **`dlopen("libandroid.so")` + a binder call + `LOGW/LOGD`**,
- on *every* call issues a **HAL call** (`reportActualDuration`).

That would put a mutex + dlopen + binder + log + HAL call **into the audio
callback** — a hard violation of the FORBIDDEN list above. The RT-safe
alternative is **`oboe::LatencyTuner`**: it auto-tunes the buffer size
(2×–8× burst). It is **lock-free in the LatencyTuner class**; the underlying
AAudio calls it makes (`getXRunCount`, `setBufferSizeInFrames`) take an
**uncontended `shared_lock` in steady state** (a concurrent `stop()`/`close()`
can briefly block), so it is safe to call in the data callback.

## JNI Safety

- Cache method IDs at JNI_OnLoad, never lookup in callback
- No `NewStringUTF` in audio callback
- No `JNIEnv` pointer persistence across callbacks
- No exceptions in native code reachable from audio path
- No JNI calls from audio callback under any condition