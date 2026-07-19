# Architecture

## Overview

Two-level architecture — Kotlin Android on top, C++ native engine below.

```
Kotlin / Android
    UI, Service, Project management, MIDI device detection, file system

C++ Native Engine
    Audio output, MIDI routing, sequencer, synthesis, mixer, transport
```

## Data Flow

```
UI (Kotlin)
    → Commands (Play, Stop, LaunchScene, etc.)
    → Binder API
    → PlaybackService
    → JNI
    → NativeEngine (C++)
    → Audio callback (Oboe)
```

```
Audio callback (C++)
    → Transport (frame-based time)
    → Sequencer (sample-accurate scheduling)
    → FluidSynth (MIDI synthesis)
    → Mixer (per-track volume, pan, mute, solo)
    → MasterBus (limiter)
    → Oboe output
```

```
MIDI input (USB)
    → Android MidiManager
    → JNI
    → MidiQueue (lock-free)
    → Audio callback
    → MidiRouter
    → FluidSynth
```

## State Levels

### ProjectState (Kotlin)
Editable model. Lives in UI thread. Contains tracks, scenes, clips, settings.

### RuntimeSnapshot (C++)
Immutable prepared state. Created on background thread, activated at callback boundary.

### RealtimeState (C++)
Audio-thread-only mutable state. Frame position, active clips, meters, notes.

## Threading Model

| Thread | Responsibility |
|---|---|
| Main/UI | UI, user commands |
| PlaybackService | Service lifecycle, owns NativeEngine |
| Audio callback | Real-time rendering, scheduling |
| MIDI input | USB MIDI message handling |
| Control | Background snapshot creation |
| Decode worker | Audio file decoding |
| Asset loading | SF2 loading |
| File I/O | Project save/load |

## Inter-Thread Communication

### Commands → Audio Thread
Lock-free SPSC/MPSC queue (pre-allocated). EngineCommand structure.

### Data ← Audio Thread
Atomic variables for transport position, meters, error flags.

### Queue Overflow
Return error, increment dropped counter, do NOT block audio.

## Key Components

### Transport
Frame-based time source. Converts between frame position and musical ticks.

### Sequencer
Sample-accurate scheduling. Processes scheduled MIDI events at exact sample boundaries.

### MidiRouter
Routes MIDI messages to tracks, handles channel mapping, transpose, velocity scaling.

### MidiRecorder
Records live MIDI input with transport-relative timestamps.

### SceneManager
Manages scene launches, quantization, clip queuing.

### ClipScheduler
Schedules MIDI clips with loop support, sample-accurate start/stop.

### FluidSynthEngine
Wraps FluidSynth library. Renders PCM float buffer. No internal sequencer.

### Mixer
Per-track volume, pan, mute, solo. Equal-power panning.

### MasterBus
Master volume, soft clipper/limiter, peak meter.

### OboeOutput
Low-latency audio output. Exclusive mode with shared fallback.

## File I/O

All file I/O on worker thread. Never in audio callback.
Project files stored in user-selected directory via SAF.
SoundFont loading on asset loading worker thread.