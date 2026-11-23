# Per-channel vibrato (FluidSynth generator offsets)

Vibrato is a per-absolute-channel offset applied through FluidSynth's
`fluid_synth_set_gen` on top of the SoundFont's own vibrato:

- `GEN_VIBLFOFREQ` — rate, **Hz** (validated 0.5..12, default 5)
- `GEN_VIBLFODELAY` — delay, **ms** (validated 0..2000, default 0)
- `GEN_VIBLFOTOPITCH` — depth, **absolute cents** (validated 0..100, default 25)

Values are passed directly in native SF2 units — no timecents or other
conversion. "Disable" zeroes the app's per-channel offsets only; the SoundFont's
authored vibrato is untouched (offset semantics, not absolute).

The state follows the per-channel release pattern exactly:

- `setChannelVibrato` validates (channel 0..127, finite in-range values),
  then: pre-init → stash and return true (replayed on init / SF2 load via
  `applyDesiredState`); post-init → enqueue `SET_CHANNEL_VIBRATO` and store the
  desired state only after the push succeeds (rejected writes never mutate
  state).
- The stored state is re-applied to BOTH synth slots where the release offset
  is re-applied: SF2 load/swap, `reprepareAtNewRate`, queued CC121, drained
  live CC121, and after PANIC.
- `resetChannelRange` disables the stored state for the range (restores the
  documented defaults for the numeric fields); the audio side's CC121 in
  `RESET_CHANNEL_RANGE` already cleared the live offsets.
- `NativeEngine::setInstrumentRackEnabled` keeps the offsets in step with the
  instrument rack: OFF pushes the stored tuple with `enabled=false` (temporary
  mute, stored state kept) and ON re-pushes the stored state — mirroring how
  the LSP slots in the same rack behave. The push goes through
  `pushChannelVibratoOffsets`, which never touches the stored state.

`SynthCmd` gained `SET_CHANNEL_VIBRATO` (appended; no existing values
reordered) and two float fields `vibratoDepth`/`vibratoDelay`
(encoding: `a`=channel, `b`=enabled 0/1, `f`=rateHz); the command carries its
own value tuple so it is self-contained.

## Host test

Executable: `app/src/main/cpp/tests/instrument_vibrato_validation_test.cpp`.
It initializes the production `FluidSynthEngine` with the bundled
`VintageDreamsWaves-v2.sf2`, then checks the validation matrix, pre-init
stashing, per-channel independence, getter round-trip, queue overflow, range
reset, and slot-swap replay (no audio assertions — the offsets are a
synth-internal setting; the release test covers rendered behavior).

Prerequisite: the host FluidSynth static library (one-time, ~a few minutes):

```sh
cmake -S app/src/main/cpp/third_party/fluidsynth -B /tmp/opencode/fs-host \
  -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF \
  -Denable-alsa=off -Denable-jack=off -Denable-oss=off -Denable-pulseaudio=off \
  -Denable-pipewire=off -Denable-sdl3=off -Denable-dbus=off -Denable-midishare=off \
  -Denable-network=off -Denable-readline=off -Denable-libsndfile=off \
  -Denable-ladspa=off -Denable-native-dls=off -Denable-signalsmith=off \
  -Denable-opensles=off -Denable-oboe=off -Denable-aufile=off -Denable-portaudio=off
cmake --build /tmp/opencode/fs-host --target libfluidsynth -j
```

Build + run (from the repo root):

```sh
g++ -std=c++17 -I app/src/main/cpp -I app/src/main/cpp/engine \
  -I app/src/main/cpp/synth -I app/src/main/cpp/realtime -I app/src/main/cpp/midi \
  -I app/src/main/cpp/third_party/fluidsynth/include -I /tmp/opencode/fs-host/include \
  app/src/main/cpp/synth/FluidSynthEngine.cpp app/src/main/cpp/realtime/MidiQueue.cpp \
  app/src/main/cpp/realtime/SynthCmdQueue.cpp \
  app/src/main/cpp/tests/instrument_vibrato_validation_test.cpp \
  /tmp/opencode/fs-host/src/libfluidsynth.a -lm -lpthread -lgomp \
  -o /tmp/opencode/instrument_vibrato_validation_test
/tmp/opencode/instrument_vibrato_validation_test \
  app/src/main/cpp/third_party/fluidsynth/sf2/VintageDreamsWaves-v2.sf2
```

Expected: `vibrato validation: all checks passed`, exit code 0.
