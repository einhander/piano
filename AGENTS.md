# Piano Sequencer — Project Guide for AI Agents

# Orchestration invariant

The orchestrator is never an implementation worker.

After every context compaction, session restart, API retry, or recovery:

1. Read the active `.slim/deepwork/*.md` state file.
2. Inspect `git status` and `git diff`.
3. Reconstruct the current phase.
4. Delegate all source-code modifications.

## Overview

Android live sequencer app for performances with USB MIDI keyboard.
C++ native audio engine + Kotlin Android UI.

**Target:** Android 10 (API 29), minSdk 26.
**Build:** Gradle 8.5, Kotlin 1.9.20, NDK 26.1, CMake 3.22.1, C++17.
**Audio:** Oboe (low-latency, exclusive mode), FluidSynth (SoundFont 2).
**Build script:** `./build.sh [debug|release]`

---

## Architecture

Two-level architecture — Kotlin Android on top, C++ native engine below.

```
Kotlin / Android
    UI, Service, Project management, MIDI device detection, file system

C++ Native Engine
    Audio output, MIDI routing, sequencer, synthesis, mixer, transport
```

**Critical rule:** The music engine must NOT use MediaPlayer, SoundPool, Handler, Timer, Kotlin coroutines, or independent audio players. All music components use a single time source — audio frame position in samples.

---

## Project Structure

```
live-sequencer/
├── app/
│   ├── src/main/java/.../
│   │   ├── MainActivity.kt
│   │   ├── service/
│   │   │   ├── PlaybackService.kt          # Foreground service, owns NativeEngine
│   │   │   ├── PlaybackBinder.kt           # Binder API for UI
│   │   │   └── PlaybackNotification.kt     # Foreground notification
│   │   ├── midi/
│   │   │   ├── MidiDeviceManager.kt        # USB MIDI device detection
│   │   │   ├── MidiInputReceiver.kt        # MIDI message handler
│   │   │   └── MidiDeviceState.kt          # Device state model
│   │   ├── project/
│   │   │   ├── Project.kt                  # Project model
│   │   │   ├── Track.kt                    # Track model
│   │   │   ├── Scene.kt                    # Scene model
│   │   │   ├── Clip.kt                     # Clip model
│   │   │   ├── ProjectRepository.kt        # Project persistence
│   │   │   └── ProjectSerializer.kt        # JSON serialization
│   │   ├── ui/
│   │   │   ├── session/                    # Session screen (tracks × scenes)
│   │   │   ├── mixer/                      # Mixer screen
│   │   │   ├── instrument/                 # Instrument screen (SF2 selection)
│   │   │   ├── midi/                       # MIDI settings screen
│   │   │   └── settings/                   # Audio settings screen
│   │   └── nativebridge/
│   │       └── NativeEngineBridge.kt       # JNI bridge
│   │
│   └── src/main/cpp/
│       ├── CMakeLists.txt
│       ├── jni/
│       │   └── native_engine_jni.cpp       # JNI entry points
│       ├── engine/
│       │   ├── NativeEngine.cpp/h          # Main engine class
│       │   ├── Transport.cpp/h             # Frame-based transport
│       │   ├── Sequencer.cpp/h             # Sample-accurate scheduler
│       │   ├── SceneManager.cpp/h          # Scene management
│       │   ├── ClipScheduler.cpp/h         # Clip scheduling
│       │   ├── MidiRouter.cpp/h            # MIDI routing
│       │   ├── MidiRecorder.cpp/h          # MIDI recording
│       │   ├── Mixer.cpp/h                 # Track mixer
│       │   └── MasterBus.cpp/h             # Master bus + limiter
│       ├── audio/
│       │   ├── OboeOutput.cpp/h            # Oboe low-latency output
│       │   ├── AudioClipPlayer.cpp/h       # Audio clip playback
│       │   ├── AudioDecoderBridge.cpp/h    # Android decode bridge
│       │   └── RingBuffer.h                # PCM ring buffer
│       ├── synth/
│       │   ├── FluidSynthEngine.cpp/h      # FluidSynth adapter
│       │   └── FluidSynthEngine.h
│       ├── realtime/
│       │   ├── CommandQueue.h              # Lock-free SPSC command queue
│       │   ├── MidiQueue.h                 # Lock-free MIDI queue
│       │   ├── MeterQueue.h                # Meter data queue
│       │   └── RealtimeState.h             # Audio-thread-only state
│       └── model/
│           ├── RuntimeProject.h            # Immutable project snapshot
│           ├── RuntimeTrack.h              # Runtime track state
│           ├── RuntimeScene.h              # Runtime scene state
│           └── RuntimeClip.h               # Runtime clip state
│
├── third_party/
│   ├── oboe/                               # Submodule: Oboe audio library
│   └── fluidsynth/                         # Submodule: FluidSynth static lib
│
├── docs/
│   ├── architecture.md                     # Architecture deep-dive
│   ├── realtime-rules.md                   # Real-time constraints
│   ├── project-format.md                   # Project file format
│   └── testing.md                          # Testing strategy
│
└── README.md
```

---

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
- No quantization unless user explicitly enables it

### Panic Command
Sends CC 64=0, 120=0, 121=0, 123=0 on all channels + disables all tracked notes.
Available from: UI button, notification, assignable MIDI key.

---

## Build

```bash
# Debug build
./build.sh debug

# Release build
./build.sh release
```

Requires: JDK 17, Android SDK (API 34), NDK 26.1, CMake 3.22.1.

---

## Dependencies

- **Oboe** — low-latency audio output (Google)
- **FluidSynth** — SoundFont 2 synthesizer (static library)
- **AndroidX** — core-ktx, appcompat, material, constraintlayout

---

## CI / Testing

- Build script: `./build.sh`
- Unit tests: tick-to-frame, frame-to-tick, quantization, MIDI parser, serialization
- Native tests: command queue, ring buffer, scheduler, mixer, transport drift, Panic
- Instrumentation: activity recreation, service binding, file picker, audio focus
- Manual: 60-min continuous live test with MIDI keyboard, scenes, audio, screen lock

---

## Not in MVP

VST/AU/LV2, piano roll, time stretching, warping, automation, waveform editor, multi-track audio recording, cloud sync, Ableton Link, Bluetooth MIDI, complex insert effects, internal instrument store.

---

## Agent Constraints

- **Stay in project directory** — do not create, read, or modify files outside `/home/einhander/Documents/4_Projects/Piano/`
- **No external tool installation** — do not install packages, SDKs, or tools without explicit user permission
- **Read PIANO_PLAN.md first** — all implementation must follow the plan; if uncertain, reference the plan
- **Respect `.gitignore` and `.ignore`** — do not create files that should be ignored; do not delete ignored files

---

## Files Reference

| File | Purpose |
|---|---|
| `PIANO_PLAN.md` | Full development plan (30 sections) |
| `AGENTS.md` | This file — project guide for AI agents |
| `build.sh` | Build script wrapper |
| `.gitignore` | Git ignore rules |
| `.ignore` | OpenCode ignore rules |
| `.slim/deepwork/phase-0.md` | Deepwork progress tracking |
