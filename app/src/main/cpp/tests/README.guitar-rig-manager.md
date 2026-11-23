# GuitarRigManager host test (per-channel 0xFX rig slots)

## What

`guitar_rig_manager_test.cpp` validates `piano::guitar::GuitarRigManager`
(`app/src/main/cpp/effects/guitar/`), the 128-channel lazy slot table that
drives `GuitarRigEffect` rigs from the audio callback:

1. channel isolation — rig enabled on ch3 only: ch3 bus changes, the ch4
   bus stays **byte-identical** (manager skips unpublished channels);
2. rack gate OFF → bus bit-identical (rig instantiated + enabled, rack off);
3. disable ramps the wet mix to dry (5 ms), after which the fully-dry
   channel's bus is not touched at all — no copy, no cabinet FFT;
4. pending-before-instantiate: params (including amp model) set before the
   rig exists are honored at creation and leave no pending config;
5. interim live policy: amp-model / cab-type changes on a live published
   rig return `false` (UI surfaces it), stay pending, are ignored at audio
   rate, and apply via `applyPendingConfig()` at quiescence; continuous
   params (gain/mix/…) return `true`;
6. `reprepareQuiescent` (sample-rate path) keeps published channels,
   enable flags and parameter values, rig audible and finite afterwards;
   enable/disable cycles across scopes are leak-free under ASan at exit;
7. `resetChannelRange(4,4)` clears rig/enabled/rack flags and restores the
   descriptor defaults for the removed range;
8. oversized frame count (`frames > prepare maxFrames`) is a documented
   no-op — the bus is untouched.

## Build + run (ASan + UBSan)

From the repo root — same object set as `README.guitar-rig-effect.md`:

```sh
FX=$PWD/app/src/main/cpp/third_party/0xFX
CPP=$PWD/app/src/main/cpp
mkdir -p /tmp/opencode/guitar-rig-test && cd /tmp/opencode/guitar-rig-test
gcc -std=gnu99 -g -O1 -fPIC -fsanitize=address,undefined -fno-omit-frame-pointer \
  -I"$FX/src/engine" -I"$FX/src/engine/internal" -I"$FX/src/core" -I"$FX/deps" -c \
  "$FX"/src/engine/internal/{engine,amp,biquad,noise_gate,tuner,pedals,studio,cab_ir,mic_sim,preset,looper}.c \
  "$FX"/src/core/log.c "$FX"/deps/cJSON.c "$FX"/deps/kissfft/kiss_fft.c "$FX"/deps/kissfft/kiss_fftr.c
g++ -std=c++17 -g -O1 -fPIC -fsanitize=address,undefined -fno-omit-frame-pointer \
  -I"$CPP" -I"$FX/src/engine" -I"$FX/src/engine/internal" -I"$FX/src/core" -I"$FX/deps" \
  "$CPP/tests/guitar_rig_manager_test.cpp" \
  "$CPP/effects/guitar/GuitarRigManager.cpp" \
  "$CPP/effects/guitar/GuitarRigEffect.cpp" "$CPP/effects/guitar/GuitarRigParams.cpp" \
  /tmp/opencode/guitar-rig-test/*.o -lm -lpthread -o guitar_rig_manager_test
./guitar_rig_manager_test
```

## Expected

`ALL PASS (failures: 0)`, exit 0, no ASan/UBSan reports (observed host
x86-64, gcc 14.2, 2026-11-23).
