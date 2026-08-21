# Piano Sequencer — Project Guide for AI Agents

## Overview

Android live sequencer for performances with a USB MIDI keyboard.
C++ native audio engine + Kotlin Android UI.

**Target:** Android 10 (API 29) · minSdk 26 · compileSdk 34
**Toolchain:** Gradle plugin 8.2.0 · Kotlin 1.9.20 · NDK 26.1.10909125 · CMake 3.22.1 · C++17
**Audio:** Oboe (low-latency) + FluidSynth (SoundFont 2)
**Package:** `com.piano.sequencer` · **ABI:** arm64-v8a, armeabi-v7a

## Build & Verify

```bash
./build.sh debug    # → app/build/outputs/apk/debug/app-debug.apk
./build.sh release  # → app/build/outputs/apk/release/
```

Wraps `./gradlew :app:assembleDebug|Release --no-daemon --warning-mode none`.
Requires JDK 17, Android SDK (API 34), NDK 26.1, CMake 3.22.1.
`local.properties` is required (copy from `local.properties.template`).

- **Automated checks:** `./build.sh debug` must print `BUILD SUCCESSFUL`, and the JVM
  unit tests must pass: `./gradlew :app:testDebugUnitTest` (MIDI parser regression
  suite in `app/src/test/.../midi/MidiMessageParserTest.kt`). On-device behavior is
  verified manually (no adb/logcat on the dev machine).
- **LSP/clangd C++ errors are pre-existing noise.** The IDE LSP lacks NDK include paths
  and reports `fluidsynth.h`/`jni.h` not found plus a cascade of errors. Do NOT try to
  "fix" them — the Gradle/CMake build is the source of truth.

## Architecture

Two levels: Kotlin Android on top, C++ native engine below.

**Critical rule:** the music engine must NOT use MediaPlayer, SoundPool, Handler, Timer,
Kotlin coroutines, or independent audio players. All music components share a single time
source — the audio frame position in samples.

### Threading (easy to get wrong)
- **The native engine is a process-level singleton** (`NativeEngine::getInstance()`).
  Do NOT call `nativeShutdown()` on activity destroy — it must survive activity
  recreation (back button) or the loaded SoundFont is lost. Native resources are
  reclaimed on process death.
- **Every `PlaybackService` method is a direct JNI call** (not a thread-marshalled binder
  call). Always invoke them from a worker thread, never the main thread — SF2 load/unload,
  polyphony, master gain, channel programs, instrument list, etc.

### State & persistence
- SharedPreferences store `"piano_prefs"`: `sf2_path` (String), `polyphony` (Int),
  `master_gain` (Float), `chan_prog_<0..15>` (Int, `bank<<8 | program`), and the log
  folder (SAF URI, via `LogFolder`). Restored in `MainActivity.restorePersistedState()` on
  a worker thread after engine (re)init; channel programs are re-applied only after a
  successful SF2 reload.
- SF2 flow: user picks a file in Settings → copied to `getExternalFilesDir(null)/<name>` →
  `fluid_synth_sfload`. The path is persisted and auto-reloaded after process death.
- Data flow: UI edits the Kotlin project model → a background thread builds an immutable
  runtime snapshot → the native engine activates it at the audio-callback boundary.

### UI theme
- `Theme.PianoSequencer` parent is `Theme.MaterialComponents.Light.NoActionBar` — always
  light, NOT DayNight.
- Platform drawables (e.g. `@android:drawable/dialog_frame`) resolve against the device's
  system theme (dark in dark mode) → use the hardcoded `@drawable/card_frame` for card
  backgrounds.
- `ContextCompat.getDrawable(context, id)` takes a **resource ID, not an attr**. Resolve an
  attr first via `theme.resolveAttribute(attr, TypedValue, true)`; passing
  `android.R.attr.*` directly crashes with `Resources$NotFoundException`.

### MIDI
- `MidiManager.openInputPort` needs the **4-arg signature (with an `Executor`)**; the
  3-arg variant does not exist. It is called via reflection in `MidiDeviceManager`.

## Source Layout

```
app/src/main/java/com/piano/sequencer/
    PianoApp.kt                  # Application: global crash catcher → crash.log
    AppLogger.kt                 # in-app log (viewed in MainActivity)
    LogFolder.kt                 # SAF log-folder persistence
    MainActivity.kt              # launcher; engine init + restorePersistedState + App Log
    SettingsActivity.kt          # SF2 load/unload, polyphony, master gain
    InstrumentActivity.kt        # 16-channel instrument assignment
    NativeEngineBridge.kt        # JNI bridge (package root, NOT a subdir)
    midi/    MidiDeviceManager, MidiInputReceiver, MidiMapping
    project/ Project, Track, Scene, Clip, ProjectRepository, ProjectSerializer
    service/ PlaybackService.kt  # foreground service; owns the engine (JNI passthroughs)
    ui/session/ SessionView, SessionViewModel

app/src/main/cpp/
    native_engine_jni.cpp        # JNI entry points (root, no jni/ subdir)
    CMakeLists.txt
    engine/   NativeEngine, Sequencer, SceneManager, ClipScheduler, LaunchQuantizer,
              MidiRouter, MidiRecorder, Mixer, MasterBus
    audio/    OboeOutput, AudioClipPlayer, AudioDecoderBridge, RingBuffer
    synth/    FluidSynthEngine
    midi/     MidiFileParser, MidiFileWriter
    realtime/ MidiQueue (lock-free)
    model/    RuntimeProject.h, TransportState.h
    third_party/ oboe/ (submodule), fluidsynth/ (submodule)
```

`docs/architecture.md` describes the target architecture; the tree above is what exists today.

## Real-Time Audio Callback (Oboe) — FORBIDDEN

`new`/`delete`/`malloc`/`free` · `mutex`/`condition_variable` · file I/O · `log`/`printf`
· JNI calls · Kotlin calls · SoundFont loading · MP3/FLAC decoding · Room access · complex
container mutations · blocking syscalls. All buffers are pre-allocated; commands flow through
lock-free queues. See `docs/realtime-rules.md`.

- **Time source:** the audio frame counter only (`TransportState.framePosition`,
  `sampleRate`, `bpm`, `ppq`, `numerator`, `denominator`). No `System.currentTimeMillis`,
  `System.nanoTime`, `Handler.postDelayed`, `delay`, `Timer`, or the internal FluidSynth
  sequencer.
- **Inter-thread comms:** commands → audio thread via a pre-allocated lock-free SPSC/MPSC
  queue; data ← audio thread via atomics or a separate queue. On overflow: return an error
  and increment a dropped counter — never block the audio thread.
- **FluidSynth:** use as a library (render PCM float → Mixer → Oboe), NOT as an audio
  driver; no internal sequencer; load SF2 only on a worker thread; swap the synth engine at
  the callback boundary (atomic pointer swap); each track → a MIDI channel (drums → ch 10).
- **Panic:** sends CC 64=0, 120=0, 121=0, 123=0 on all channels + disables tracked notes.
  Available from the UI button, the notification, and an assignable MIDI key.

## Android Components

- Permissions: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `RECORD_AUDIO`.
- `MainActivity` — launcher. `SettingsActivity` / `InstrumentActivity` — its children.
- `PlaybackService` — foreground service, `mediaPlayback` type, `exported="false"`.
- `PianoApp` — the `Application`; a global uncaught-exception handler writes the full stack
  to `crash.log` (in the user-chosen log folder, else the app `filesDir`) and to the in-app log.

## Dependencies

androidx.core:core-ktx:1.12.0 · androidx.appcompat:appcompat:1.6.1 ·
androidx.activity:activity-ktx:1.8.0 · com.google.android.material:material:1.11.0 ·
androidx.constraintlayout:constraintlayout:2.1.4 ·
org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0.
Kotlin plugins: `org.jetbrains.kotlin.android`, `org.jetbrains.kotlin.plugin.serialization`.

## Diagnostics (no adb / no logcat on the dev machine)

- In-app log: the "App Log" section in `MainActivity` (Copy / Clear).
- Crashes: `PianoApp` writes the full stack to `crash.log` in the user-chosen log folder
  (MainActivity → "Log folder: Choose"), falling back to the app `filesDir`.
- Ask the user to paste the relevant log line rather than reaching for logcat.

## Agent Constraints

- Stay in the repository root — do not create, read, or modify files outside it.
- No external tool/package/SDK installation without explicit user permission.
- Read `docs/architecture.md` (and `docs/realtime-rules.md` for real-time
  constraints) before architectural decisions.
- Respect `.gitignore`/`.ignore`; don't create files that should be ignored or delete
  ignored files.
- Orchestration: never run multiple specialist agents in parallel. At most one
  sub-agent at a time, plus @oracle for architecture/review. Sequential lanes only.

## Docs

- `docs/architecture.md` — architecture, data flow, threading model
- `docs/realtime-rules.md` — real-time constraints, sample-accurate scheduling, JNI safety

## Dev-machine toolchain (this container)

- JDK 17 Temurin at `/opt/jdk-17`; Android SDK at `/opt/android-sdk`
  (platform android-34, build-tools 34.0.0, ndk 26.1.10909125, cmake 3.22.1,
  platform-tools, cmdline-tools/latest). Env exports live in `~/.piano_env.sh`
  (sourced from `~/.bashrc` and `~/.profile`); `local.properties` points at the
  SDK root. System `g++` 14.2 + `cmake` 3.31 are used only for the LSP host
  build / offline tests.
- Build/verify: `./build.sh debug` must print `BUILD SUCCESSFUL`; unit tests
  `./gradlew :app:testDebugUnitTest` must pass.
- Shallow-clone gotcha: the `oboe`/`fluidsynth` submodules are NOT fetched by
  a shallow clone and CMake fails with missing `third_party/*/CMakeLists.txt`.
  Run `git submodule update --init` once before the first native build.

## LSP Plugins integration layer

- LSP source tree (`app/src/main/cpp/third_party/lsp/`) is gitignored and
  fetched on demand: clone `lsp-plugins/lsp-plugins` tag `1.2.34`, then the
  build script's `make config`/`make fetch` pull the submodules.
- Cross-compile: `app/src/main/cpp/lsp-integration/build-lsp-ladspa-android.sh`
  → `third_party/lsp/.build/target/lsp-plugin-fw/lsp-plugins-ladspa.so`
  (AArch64, exports `ladspa_descriptor`, NEEDED = libdl/libc++_shared/libm/libc).
  Copy it (with the `lib` prefix) to
  `lsp-integration/prebuilt/arm64-v8a/liblsp-plugins-ladspa.so` so the Gradle
  `jniLibs` sourceSet packages it.
- Host x86-64 build (for the offline tests in `lsp-integration/tests/`): from
  `third_party/lsp`, `make config FEATURES='ladspa'`, then `rm -rf .build` +
  `make` (the `rm -rf .build` is required when switching host/target arch —
  stale objects of the wrong arch cause `incompatible object` link errors).
- `effect_chain_test.cpp` documents its own exact `g++` command line in a
  header comment; it links the app effect sources directly.
- The UI (`EffectsActivity`) reads parameter ranges/flags only from the native
  descriptor tables (`piano::lsp::paramDescriptors`); do NOT duplicate DSP
  ranges in Kotlin. Effect state persists to `piano_prefs` (`fx_enabled_<slot>`,
  `fx_param_<slot>_<id>`); the project format (Milestone 8) is untouched.
- Progress: `app/src/main/cpp/lsp-integration/PROGRESS.md` (milestone tracker).
