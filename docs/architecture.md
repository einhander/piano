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
    → processOneMidi
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
| Audio callback | Real-time rendering, scheduling; drains MIDI queue → feeds synth |
| MIDI input | USB MIDI in → lock-free queue; now **records-only** |
| Control | Background snapshot creation |
| Decode worker | Audio file decoding |
| Asset loading | SF2 loading (into the inactive synth slot) |
| File I/O | Project save/load |

The MIDI input thread no longer feeds the synth. It polls the lock-free
`mLiveMidiQueue` (2 ms sleep, **no condition variable, no notify from the audio
thread**) and records live keyboard events only. The audio callback drains the
MIDI queue directly and feeds the synth (see FluidSynthEngine below).

## Inter-Thread Communication

### Commands → Audio Thread
Lock-free SPSC/MPSC queue (pre-allocated). EngineCommand structure.

A second lock-free MPSC queue, **SynthCmdQueue** (worker → audio), carries
synth control commands: polyphony, master gain, reverb, chorus, interpolation,
channel program, panic, and direct notes. The audio thread drains it in the
callback and applies each command to the synth slots.

### Data ← Audio Thread
Atomic variables for transport position, meters, error flags, and the
active-voice count.

### Queue Overflow
Return error, increment dropped counter, do NOT block audio. (Both the command
queue and SynthCmdQueue expose a dropped counter, surfaced in the `[perf]` log.)

## Key Components

### Transport
Frame-based time source. Converts between frame position and musical ticks.

### Sequencer
Sample-accurate scheduling. Processes scheduled MIDI events at exact sample boundaries.

### MidiRouter
Routes MIDI messages to tracks, handles channel mapping, transpose, velocity scaling.
**Unused/legacy** — defined but never wired into the engine; the live path is
`processOneMidi` in the audio callback (see FluidSynthEngine).

### MidiRecorder
Records live MIDI input with transport-relative timestamps.

### SceneManager
Manages scene launches, quantization, clip queuing.

### ClipScheduler
Schedules MIDI clips with loop support, sample-accurate start/stop.

### FluidSynthEngine
Wraps FluidSynth library. Renders PCM float buffer. No internal sequencer.
**Lock-free** (the FluidSynth C API is not thread-safe, so all `fluid_synth_*`
calls run on the audio thread):

- **Double-buffered synths** (`mSynth[2]`): `mActiveIndex` selects the slot the
  audio thread renders from; the worker prepares the other.
- **SF2 load/unload** happens on the worker thread (file I/O) into the
  **inactive** slot, then swaps at the callback boundary (no wait, no lock).
- **`mPreparing[2]`** atomic flag: the audio thread's command drain *skips* the
  slot being prepared; the worker applies the desired state (read from atomics)
  to the prepared slot *before* the flip, so the new active slot is correct.
- After the flip the worker **frees the old slot's SF2** (bounded wait for the
  sequence lock to go even) → only **1× SF2 resident** (not 2×).
- A **sequence lock** (`mSynthSeq`) guards the worker-thread
  `getInstruments`/`getSoundFontCount` reads of the active synth.
- Live MIDI is drained from the lock-free queue in the callback and fed via
  `processOneMidi` (held-note tracking + re-arm); control commands arrive via
  SynthCmdQueue.

### Mixer
Per-track volume, pan, mute, solo. Equal-power panning.

### MasterBus
Master volume, soft clipper/limiter, peak meter.

### OboeOutput
Low-latency audio output. Exclusive mode with shared fallback.

- The **actual Oboe sample rate is authoritative** — queried after `open()` and
  passed to `initEngine` (was hardcoded 48000; a 44.1k device would otherwise
  desync the transport tick math from the synth render rate).
- On a stream **(re)open at a changed rate** (e.g. a BT device switch
  44.1k↔48k), the transport is updated and the **inactive synth slot is
  re-prepared at the new rate** (the SF2 is reloaded into it), then swapped at
  the callback boundary.
- **`oboe::LatencyTuner`** auto-tunes the buffer between **2×burst and 8×burst**
  based on underruns; `tune()` is called in the data callback (verified
  mutex-free). The user can override with a **fixed buffer size** (128–2048
  frames), which disables auto-tune.

### Performance settings
User-tunable synth/output settings (Settings screen), all applied by the audio
thread from atomics and **persisted in `piano_prefs`**:

- **Reverb** / **Chorus** — on/off, both default **ON** (keys `reverb`, `chorus`).
- **Interpolation** — 4th order / linear / none, default **4th order** (key
  `interps`; 4/1/0).
- **Buffer size** — 128–2048 frames, or **Auto** (LatencyTuner) (keys
  `buffer_size`, `auto_tune`).

### Diagnostics
`[perf]` lines in the in-app log (App Log in MainActivity), all read from
atomics on a worker thread — never the audio callback:

- **Base fields** (every line): rate, buffer/capacity (frames), latency (ms),
  sharing + performance mode, auto-tune, underruns, callback count, processed
  frames, midi/cmd queue drops, active voices, polyphony, gain, reverb, chorus,
  interps, **active clip count** (`clips`), **midi queue depth** (`midi_q`).
- **One-time dump** on `startAudio()` additionally appends: **burst** (frames
  per Oboe burst — the buffer is N×burst), **device** (model / sdk / soc /
  cores), and **SF2** (filename + load time in ms; omitted if no SF2 is loaded).
- **1 Hz line** while playing: the base fields, re-logged each second.
- **Event lines**: `[perf] UNDERRUN: total=N` when the underrun count rises;
  plus AppLogger lines for SF2 load/unload, stream open/start failures, and
  setting-change failures.

## File I/O

All file I/O on worker thread. Never in audio callback.
Project files stored in user-selected directory via SAF.
SoundFont loading on asset loading worker thread.