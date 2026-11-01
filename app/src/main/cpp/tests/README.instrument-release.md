# Per-channel volume-envelope release offset

Release is a per-absolute-channel `GEN_VOLENVRELEASE` timecents offset. `0`
preserves SF2-authored release; public setter accepts finite `[-2400, 2400]`.
Setter commits desired state only after queue enqueue succeeds. Producer and
slot-preparation operations serialize via `mReleaseConfigMutex`; audio callback
never takes that lock. Range reset sends the audio command and sets desired
offsets to zero only after successful enqueue. Slot restore replays desired
offsets for all 128 channels.

The executable test uses the bundled `VintageDreamsWaves-v2.sf2`. It initializes
the production `FluidSynthEngine`, checks setter rejection/queue overflow and
desired-state retention, exercises channels 0/16/127, resets a range, then
compares actual rendered tail energy after 0.25 s sustain and 1 s of tail in
512-frame chunks. Run using the host FluidSynth build described in
`held_note_rearm_test.cpp`:

```sh
g++ -std=c++17 -I app/src/main/cpp -I app/src/main/cpp/engine \
  -I app/src/main/cpp/synth -I app/src/main/cpp/realtime -I app/src/main/cpp/midi \
  -I app/src/main/cpp/third_party/fluidsynth/include -I /tmp/opencode/fs-host/include \
  app/src/main/cpp/synth/FluidSynthEngine.cpp app/src/main/cpp/realtime/MidiQueue.cpp \
  app/src/main/cpp/realtime/SynthCmdQueue.cpp \
  app/src/main/cpp/tests/instrument_release_validation_test.cpp \
  /tmp/opencode/fs-host/src/libfluidsynth.a -lm -lpthread -lgomp \
  -o /tmp/opencode/instrument_release_validation_test
/tmp/opencode/instrument_release_validation_test \
  app/src/main/cpp/third_party/fluidsynth/sf2/VintageDreamsWaves-v2.sf2
```

Identity persistence uses `instrument_release_<stable-set-id>_<local-0..15>`;
file-set removal clears its 16 keys only after native range reset succeeds.
