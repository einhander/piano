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

Live MIDI: message → lock-free queue → audio callback → MidiRouter → FluidSynth.

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
- Load SF2 only on worker thread
- Swap SynthEngine at callback boundary (atomic pointer swap)
- Each track → one MIDI channel (Drums → channel 10)

## JNI Safety

- Cache method IDs at JNI_OnLoad, never lookup in callback
- No `NewStringUTF` in audio callback
- No `JNIEnv` pointer persistence across callbacks
- No exceptions in native code reachable from audio path
- No JNI calls from audio callback under any condition