# GuitarRigEffect host test (stereo pair of mono 0xFX engines)

## What

`guitar_rig_effect_test.cpp` validates `piano::guitar::GuitarRigEffect`
(`app/src/main/cpp/effects/guitar/`) — the AudioEffect adapter over TWO
independent mono `fx_engine_t` instances from the vendored 0xFX core
(pinned SHA `5b248779c96dce6e64d0ed29cfa598014dc4d3c4`):

1. zero input through an engaged rig → exact-zero output, all finite;
2. fresh/bypassed rig → bit-exact passthrough (buffers provably untouched);
3. a 4096+123-frame call vs an explicit two-chunk reference run →
   bit-identical (internal chunking at `FX_MAX_BLOCK_SIZE=4096` consumes
   every frame), 8-float guard slots around the buffer untouched;
4. 440 Hz sine, mix=1 → finite, RMS > 0 (measured ≈ 0.585 at defaults);
5. NaN params ignored, out-of-range clamped, integer enum quantized, DSP
   stays finite;
6. latency: impulse at frame 0 through the whole rig at n=4096 — first
   non-zero output frame is 0 (peak of the cab response at frame 63);
   assert equals `getLatencyFrames()` (declared 0 — every stage is causal
   direct-form / minimum-phase, the partitioned cab convolution adds no
   block delay);
7. L-only input → R output bit-identical (independent mono engines, no
   bleed);
8. repeated prepare/re-prepare (48k→44.1k) + destruction → ASan leak check
   clean at exit (explicit teardown via scope-exit destructors);
9. live-swap support API: discrete `amp_model`/`cab_type` on a live rig
   mirror only (the manager's swap protocol owns engine reconfiguration);
   `applyConfigNow()` reconciles the engines at quiescence; the swap ramp
   duration is honored — forced-dry fade measured through `wetApplied()`:
   −0.1 per 1 ms at `setRampSeconds(0.01)`, −0.2 per 1 ms at 0.005.

## Build + run (ASan + UBSan)

From the repo root:

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
  "$CPP/tests/guitar_rig_effect_test.cpp" \
  "$CPP/effects/guitar/GuitarRigEffect.cpp" "$CPP/effects/guitar/GuitarRigParams.cpp" \
  /tmp/opencode/guitar-rig-test/*.o -lm -lpthread -o guitar_rig_effect_test
./guitar_rig_effect_test
```

The C object list mirrors the `fx0_engine` target in
`app/src/main/cpp/CMakeLists.txt`; the effect/adapter compile as C++17 TUs
and include only the public `fx_engine.h`.

## Expected

`ALL PASS (failures: 0)`, exit 0, no ASan/UBSan reports. Observed on host
x86-64, gcc 14.2 (2026-11-23): `[6] latency: first non-zero frame=0,
peak frame=63, declared=0`.

## Notes

- The cabinet FFT cost warning applies here too: `fx_cab_process` pays a
  full 8192-point FFT pair per call regardless of frame count (see
  `README.cab-segmented-convolution.md`), so the adapter chunking prefers
  the largest allowed chunks; segmentation itself is bit-exact thanks to
  the overlap-add patch.
- Live-swap policy (spec §10): amp model / cab changes on a live rig are
  mirror-only on this object — `GuitarRigManager` builds a replacement rig
  and swaps it via the CURRENT/PENDING/RETIRED mailbox protocol (see the
  effect header comment + docs/guitar-fx-port.md §Live rig swap for the
  verified engine.c line references and the two-phase fade).
