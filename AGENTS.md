# Piano Sequencer — Project Guide for AI Agents

## Overview

Android live sequencer for performances with USB MIDI keyboard.
C++ native audio engine + Kotlin Android UI.

**Target:** Android 10 (API 29), minSdk 26, compileSdk 34
**Build:** Gradle plugin 8.2.0, Kotlin 1.9.20, NDK 26.1.10909125, CMake 3.22.1, C++17
**Audio:** Oboe (low-latency, exclusive mode), FluidSynth (SoundFont 2)
**Package:** `com.piano.sequencer`
**ABI:** arm64-v8a, armeabi-v7a

## Build

```bash
./build.sh debug    # → app/build/outputs/apk/debug/
./build.sh release  # → app/build/outputs/apk/release/
```

Wraps `./gradlew :app:assembleDebug|Release --no-daemon --warning-mode none`.
Requires: JDK 17, Android SDK (API 34), NDK 26.1, CMake 3.22.1.

`local.properties` needed (copy from `local.properties.template`).

## Architecture

Two-level: Kotlin Android on top, C++ native engine below.

```
Kotlin / Android
    UI, Service, Project management, MIDI device detection, file system

C++ Native Engine
    Audio output, MIDI routing, sequencer, synthesis, mixer, transport
```

**Critical rule:** The music engine must NOT use MediaPlayer, SoundPool, Handler, Timer, Kotlin coroutines, or independent audio players. All music components use a single time source — audio frame position in samples.

## Source Layout

```
app/src/main/java/com/piano/sequencer/
    MainActivity.kt
    NativeEngineBridge.kt              # JNI bridge (at package root, not in subdir)
    service/
        PlaybackService.kt             # Foreground service, owns NativeEngine
    midi/
        MidiDeviceManager.kt           # USB MIDI device detection
        MidiInputReceiver.kt           # MIDI message handler
    project/
        Project.kt, Track.kt, Scene.kt, Clip.kt
        ProjectRepository.kt           # Project persistence
        ProjectSerializer.kt           # JSON serialization (kotlinx-serialization)

app/src/main/cpp/
    native_engine_jni.cpp              # JNI entry points (at root, no jni/ subdir)
    CMakeLists.txt
    engine/
        NativeEngine.cpp/h             # Main engine class
        TransportState.h               # (in model/, not engine/)
        Sequencer.cpp/h                # Sample-accurate scheduler
        SceneManager.cpp/h             # Scene management
        ClipScheduler.cpp/h            # Clip scheduling
        MidiRouter.cpp/h               # MIDI routing
        MidiRecorder.cpp/h             # MIDI recording
    audio/
        OboeOutput.cpp/h               # Oboe low-latency output
        AudioClipPlayer.cpp/h          # Audio clip playback
        AudioDecoderBridge.cpp/h       # Android MediaExtractor/MediaCodec bridge
        RingBuffer.cpp/h               # PCM ring buffer
    synth/
        FluidSynthEngine.cpp/h         # FluidSynth adapter
    realtime/
        MidiQueue.cpp/h                # Lock-free MIDI queue
    model/
        RuntimeProject.h               # Immutable project snapshot
        TransportState.h               # Transport state struct
    third_party/
        oboe/                          # Oboe audio library (submodule)
        fluidsynth/                    # FluidSynth static lib (submodule)
```

**Note:** The actual file set above reflects what currently exists. The plan (PIANO_PLAN.md) describes additional components (Mixer, MasterBus, CommandQueue, MeterQueue, RealtimeState, RuntimeTrack/Scene/Clip, PlaybackBinder, PlaybackNotification, ui/* subdirs) that are not yet implemented.

## Third-Party Libraries

Lived at `app/src/main/cpp/third_party/` (NOT at repo root).

- **Oboe** — low-latency audio output (Google). Linked as `oboe`.
- **FluidSynth** — SoundFont 2 synthesizer. Built static with minimal options
  (no shared lib, no dashboard, no readline, no sqlite3, no sndfile).
  CMake flags in `app/build.gradle.kts` externalNativeBuild arguments.

## Key Rules

### Real-Time Audio Callback (Oboe) — FORBIDDEN
- `new`, `delete`, `malloc`, `free`
- `mutex`, `condition_variable`
- File I/O
- `log` / `printf`
- JNI calls
- Kotlin calls
- SoundFont loading
- MP3/FLAC decoding
- Room database access
- Complex container mutations
- Blocking syscalls

All buffers pre-allocated. Commands via lock-free queues.
See `docs/realtime-rules.md` for full details.

### Time Source
Single source: audio frame counter.
- `TransportState.framePosition` — absolute frame position
- `TransportState.sampleRate` — device sample rate
- `TransportState.bpm`, `ppq`, `numerator`, `denominator`

No `System.currentTimeMillis`, `System.nanoTime`, `Handler.postDelayed`, `delay`, `Timer`, or internal FluidSynth sequencer.

### Inter-Thread Communication
- Commands → audio thread: lock-free SPSC/MPSC queue (pre-allocated)
- Data ← audio thread: atomic variables or separate queue
- On queue overflow: return error, increment dropped counter, do NOT block audio

### State Levels
1. **ProjectState** — editable Kotlin model
2. **RuntimeSnapshot** — immutable prepared state for native engine
3. **RealtimeState** — audio-thread-only mutable state

Update flow: UI modifies ProjectState → background thread creates RuntimeSnapshot → snapshot passed to NativeEngine → audio thread activates at callback boundary.

### FluidSynth Integration
- Use as library, NOT audio driver
- Render PCM float buffer → Mixer → Oboe
- Do NOT use FluidSynth internal sequencer
- Load SF2 only on worker thread
- Swap SynthEngine at callback boundary (atomic pointer swap)
- Each track → MIDI channel (Drums → channel 10)

### MIDI
- Android 10: `MidiManager` API → JNI → C++
- Architecture must allow swap to `AMidi` (Native MIDI API) later
- Live MIDI: message → lock-free queue → audio callback
- No quantization unless user explicitly enabled

### Panic Command
Sends CC 64=0, 120=0, 121=0, 123=0 on all channels + disables all tracked notes.
Available from: UI button, notification, assignable MIDI key.

## Android Permissions & Components

- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK`
- `RECORD_AUDIO`
- `MainActivity` — launcher activity
- `PlaybackService` — foreground service, `mediaPlayback` type, exported

## Dependencies

```
androidx.core:core-ktx:1.12.0
androidx.appcompat:appcompat:1.6.1
com.google.android.material:material:1.11.0
androidx.constraintlayout:constraintlayout:2.1.4
org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0
```

Kotlin plugins: `kotlin.native.cinterop`, `kotlin.plugin.serialization`.

## Development Plan

`PIANO_PLAN.md` — full development plan in Russian (30 sections, 13 phases).
All implementation must follow the plan. Read it before making architectural decisions.

## Docs

- `docs/architecture.md` — architecture deep-dive, data flow, threading model
- `docs/realtime-rules.md` — real-time constraints, sample-accurate scheduling, JNI safety

## Agent Constraints

- **Stay in project directory** — do not create, read, or modify files outside `/home/einhander/piano/`
- **No external tool installation** — do not install packages, SDKs, or tools without explicit user permission
- **Read PIANO_PLAN.md first** — all implementation must follow the plan; if uncertain, reference the plan
- **Respect `.gitignore` and `.ignore`** — do not create files that should be ignored; do not delete ignored files