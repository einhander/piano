# Piano Sequencer

Piano Sequencer is an Android live-performance sequencer designed for musicians who use an external USB MIDI keyboard.

Its main goal is to provide a focused and reliable performance environment where a musician can connect a MIDI controller, load SoundFont instruments and prepared MIDI material, and control a performance with minimal interaction with the touchscreen.

The project prioritizes:

- low audio latency
- predictable MIDI and sequencer timing
- stable real-time playback
- fast switching between instruments, clips, and scenes
- practical operation during live performance

It is intentionally not intended to reproduce a complete desktop DAW on Android.

The application combines a Kotlin Android frontend with a native C++ audio engine built around Oboe and FluidSynth.

> **Status:** Work in progress. The project is under active development and is not yet considered release-ready.

## Features

The project is being developed to support:

- USB MIDI keyboards via Android MIDI
- Low-latency native audio output
- SoundFont 2 (`.sf2`) instruments
- FluidSynth-based software synthesis
- MIDI file playback
- MIDI routing and channel selection
- Instrument selection per MIDI channel
- Transport and sequencer functionality
- Clips and scenes for live performance
- Track volume, pan, mute, and solo
- MIDI recording and quantization
- Project save/load
- Background playback through an Android foreground service
- MIDI-controlled scene and instrument switching

Some of these features are still under development.

## Architecture

The application is split into two main layers:

```text
┌─────────────────────────────────────┐
│          Android / Kotlin           │
│                                     │
│  UI                                 │
│  Project management                 │
│  MIDI device management             │
│  Playback foreground service        │
└──────────────────┬──────────────────┘
                   │ JNI
┌──────────────────▼──────────────────┐
│          Native C++ Engine          │
│                                     │
│  MIDI routing                       │
│  Sequencer / transport              │
│  Real-time scheduling               │
│  FluidSynth                         │
│  Mixing                             │
│  Audio output                       │
└──────────────────┬──────────────────┘
                   │
                 Oboe
                   │
              Android Audio
```

The audio engine uses a single sample-frame-based time source instead of Android timers. MIDI events and sequencer events are therefore scheduled relative to the audio stream itself.

The real-time audio path is implemented in native C++ and is designed to avoid blocking operations, memory allocation, JNI calls, and other non-real-time-safe operations inside the audio callback.

## Technology

- **Kotlin** — Android UI, services, MIDI device management, and project state
- **C++17** — native audio and sequencer engine
- **JNI** — communication between Kotlin and the native engine
- **Oboe** — low-latency Android audio output
- **FluidSynth** — SoundFont 2 synthesis
- **Android MIDI API** — USB MIDI device support
- **CMake** — native build system
- **Gradle** — Android build system
- **kotlinx.serialization** — project data serialization

## Android Support

Current configuration:

```text
minSdk:     26
targetSdk:  29
compileSdk: 34
```

The primary target is **Android 10 / API 29**, while the application can run on Android 8.0 and newer according to the current `minSdk`.

Native ABIs:

```text
arm64-v8a
armeabi-v7a
```

## Requirements

To build the project you need:

- JDK 17
- Android SDK with API 34
- Android NDK `26.1.10909125`
- CMake `3.22.1`
- Git

FluidSynth and Oboe are included as Git submodules.

## Clone

Clone the repository together with its submodules:

```bash
git clone --recursive https://github.com/einhander/piano.git
cd piano
```

If you already cloned the repository without `--recursive`:

```bash
git submodule update --init --recursive
```

## Configure Android SDK

Create `local.properties`:

```bash
cp local.properties.template local.properties
```

Make sure `sdk.dir` points to your Android SDK installation, for example:

```properties
sdk.dir=/home/user/Android/Sdk
```

## Build

A convenience build script is provided.

### Debug APK

```bash
./build.sh debug
```

The APK will be generated in:

```text
app/build/outputs/apk/debug/app-debug.apk
```

You can also build directly with Gradle:

```bash
./gradlew :app:assembleDebug
```

### Release APK

```bash
./build.sh release
```

or:

```bash
./gradlew :app:assembleRelease
```

Release output is written to:

```text
app/build/outputs/apk/release/
```

## Tests

Run JVM unit tests with:

```bash
./gradlew :app:testDebugUnitTest
```

The test suite currently includes MIDI message parser regression tests.

A successful development build can be checked with:

```bash
./build.sh debug
```

and should complete with:

```text
BUILD SUCCESSFUL
```

## Project Structure

```text
piano/
├── app/
│   └── src/
│       ├── main/
│       │   ├── cpp/
│       │   │   ├── audio/
│       │   │   ├── engine/
│       │   │   ├── midi/
│       │   │   ├── model/
│       │   │   ├── realtime/
│       │   │   ├── synth/
│       │   │   └── third_party/
│       │   ├── java/com/piano/sequencer/
│       │   │   ├── midi/
│       │   │   ├── project/
│       │   │   ├── service/
│       │   │   └── ui/
│       │   └── res/
│       └── test/
├── docs/
├── test_files/
├── AGENTS.md
├── build.sh
└── local.properties.template
```

## Real-Time Audio Design

The native engine follows several important real-time constraints.

The audio callback must not perform operations such as:

- dynamic memory allocation
- file access
- blocking synchronization
- logging
- Kotlin calls
- JNI calls
- SoundFont loading
- expensive decoding or resource management

Control changes from the Android layer are passed to the engine outside the real-time audio path.

The sequencer uses the audio frame position as its master clock so that MIDI playback, transport, clips, and other musical events can share the same timing source.

## SoundFonts

Piano Sequencer uses **FluidSynth** to render SoundFont 2 instruments.

Supported instrument files use the:

```text
.sf2
```

format.

SoundFonts are loaded into the native synthesis engine, while FluidSynth itself does not own the Android audio device. Rendered PCM audio is mixed by the application and sent through Oboe.

## MIDI

The application is designed primarily around external USB MIDI keyboards connected to the Android device using USB OTG.

The MIDI subsystem is intended to handle common channel messages including:

- Note On
- Note Off
- Control Change
- Program Change
- Pitch Bend
- Sustain pedal

Live MIDI input is forwarded to the native audio engine with the goal of minimizing latency between a key press and generated audio.
