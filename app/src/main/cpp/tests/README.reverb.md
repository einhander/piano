# Native FluidSynth reverb configuration

`FluidSynthEngine::setReverbParameters(roomSize, damping, width, level)` validates
the complete tuple then enqueues one trivially-copyable `SET_REVERB_PARAMS`
command. Rejected values / queue overflow leave desired configuration unchanged.
`getDesiredReverbParameters()` reports accepted values;
`getDefaultReverbParameters()` exposes defaults read from FluidSynth settings.
The pre-existing on/off command remains separate and defaults ON.
`setReverbEnabledChecked` commits desired enable state only after enqueue;
legacy `setReverb(bool)` delegates to it. Producer updates (enable and params)
share `mReverbConfigMutex`; it is never acquired by the audio callback and is
held over prepared-slot restore/flip so no queued setting can be skipped by both
preparing slots. Individual compatibility parameter updates use the same locked
read/modify/enqueue/commit transaction to prevent concurrent-field loss.
Compatibility ID 0 accepts only finite exact `0`/`1` and returns checked
enqueue result.

Ranges are room size 0..1, damping 0..1, width 0..100, level 0..1. The focused
host check exercises production validation/payload/commit helpers, concurrent
field updates, finite/range boundaries, and desired-state preservation on queue
overflow:

```sh
g++ -std=c++17 -Wall -Wextra -pedantic \
  app/src/main/cpp/tests/reverb_cmd_queue_test.cpp \
  app/src/main/cpp/realtime/SynthCmdQueue.cpp \
  -o /tmp/reverb_cmd_queue_test && /tmp/reverb_cmd_queue_test
```

Bundled API evidence: `third_party/fluidsynth/include/fluidsynth/synth.h:177–185`
declares `fluid_synth_{set,get}_reverb_group_*`; implementations document
`fx_group=-1` as all groups, and ranges at `third_party/fluidsynth/src/synth/fluid_synth.c:6202–6266`.
Default values are registered from `FLUID_REVERB_DEFAULT_*` at
`fluid_synth.c:222–225` (header macros in `src/synth/fluid_synth.h:49–52`);
the engine queries `fluid_settings_getnum_default` (`include/fluidsynth/settings.h:140`)
instead of hardcoding default assumptions.
