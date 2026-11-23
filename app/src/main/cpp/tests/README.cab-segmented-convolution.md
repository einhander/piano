# Cabinet segmented-convolution harness (0xFX cab FFT overlap-add)

## What

`cab_segmented_convolution_test.cpp` proves the vendored 0xFX cabinet path
(`third_party/0xFX/src/engine/internal/cab_ir.c`, `fx_cab_process`) is correct
across **arbitrary block segmentations**. The Oboe callback hands the adapter
whatever frame count it gets, so overlap-add must carry convolution tail energy
across any cut of the stream, not just the uniform 4096-frame block the IR FFT
was sized for (bundled/synthetic IRs are 2048 samples; `fft_size = 8192`).

## Why

Stock `fx_cab_process` (pinned SHA `5b24877`) **overwrote** `overlap_buf` with
the new convolution tail each call. The head of the tail is block-alignment
correct, but the part spilling past the *next* block (tail = `ir_len−1` = 2047
samples vs blocks of 64/512…) was silently dropped — reverb-tail truncation
whenever the callback block is shorter than the IR. The harness confirmed this
empirically (before fixing), attributed it to the cabinet alone, and
`patches/cab_ir-segmented-overlap-add.patch` records the minimal
rotate-then-add fix. The same fix is bit-identical to stock behavior for
uniform `n == block_size == 4096` operation, so the nominal engine path is
unchanged.

Scenarios:

- **A** — engine-level (`fx_engine_create(48000)`, default chain, no pedals,
  amp British Crunch @ GAIN=0.0, cab `FX_CAB_4X12_STRAIGHT` on-axis
  `fs=70, bright=0.5, res=0.6`, mic DI default, gate defaults): unit impulse at
  sample 0 + silence, 8192 frames total, four segmentations ([4096,4096],
  [512×16], [64×128], variable `[1,17,31,64,3,511,128,999,…]`). Fresh engine
  per run. PASS = max abs diff ≤ `eps = max(1e-6, 1e-5 × ref peak)` for every
  pair against the [4096,4096] reference. An attribution run repeats the matrix
  with the cab bypassed: divergence there would prove a non-cabinet cause.
- **A2** — cabinet-only (internal `fx_cab_load_buffer`/`fx_cab_process`, no
  amp/mic/gate) against a float64 time-domain ground-truth convolution with the
  same normalized IR. Tolerance 5e-4 absolute (bounds float32 FFT round-off;
  observed reference agreement is 6e-8).
- **B** — 440 Hz sine at 44100 and 48000 through variable blocks including
  size 1: all outputs finite, 8-float guard slots before/after the output
  buffer untouched.
- **C** — geometry report: `fft_size >= block_n + ir_len − 1` for every
  processed n (runtime-introspected `cab.fft_size`), proving the defect is the
  carry policy, not the FFT sizing.
- **T** — per-block cost at n = 64/512/4096 (opt-in via `CAB_TIMING=1`, only
  meaningful in the non-sanitized run; input refilled per block since
  `fx_cab_process` is in-place).

## Build + run

Primary target (ASan + UBSan). From the repo root:

```sh
FX=$PWD/app/src/main/cpp/third_party/0xFX
T=$PWD/app/src/main/cpp/tests/cab_segmented_convolution_test.cpp
mkdir -p /tmp/opencode/cab-test && cd /tmp/opencode/cab-test
gcc -std=gnu99 -g -O1 -fPIC -fsanitize=address,undefined -fno-omit-frame-pointer \
  -I"$FX/src/engine" -I"$FX/src/engine/internal" -I"$FX/src/core" -I"$FX/deps" -c \
  "$FX"/src/engine/internal/{engine,amp,biquad,noise_gate,tuner,pedals,studio,cab_ir,mic_sim,preset,looper}.c \
  "$FX"/src/core/log.c "$FX"/deps/cJSON.c "$FX"/deps/kissfft/kiss_fft.c "$FX"/deps/kissfft/kiss_fftr.c
g++ -std=c++17 -g -O1 -fPIC -fsanitize=address,undefined -fno-omit-frame-pointer \
  -I"$FX/src/engine" -I"$FX/src/engine/internal" -I"$FX/src/core" -I"$FX/deps" \
  "$T" /tmp/opencode/cab-test/*.o -lm -lpthread -o cab_test
./cab_test
```

Object list mirrors the `fx0_engine` target in `app/src/main/cpp/CMakeLists.txt`
(11 internal modules + `core/log.c` + `deps/cJSON.c` + both kissfft sources).
The C sources compile as C (`-std=gnu99`, matching `C_EXTENSIONS ON`); the test
TU is C++17 including `engine_internal.h` inside `extern "C"`.

Timing run (non-sanitized, -O2):

```sh
mkdir -p /tmp/opencode/cab-test-o2 && cd /tmp/opencode/cab-test-o2
gcc -std=gnu99 -O2 -fPIC -I"$FX/src/engine" -I"$FX/src/engine/internal" -I"$FX/src/core" -I"$FX/deps" -c \
  "$FX"/src/engine/internal/{engine,amp,biquad,noise_gate,tuner,pedals,studio,cab_ir,mic_sim,preset,looper}.c \
  "$FX"/src/core/log.c "$FX"/deps/cJSON.c "$FX"/deps/kissfft/kiss_fft.c "$FX"/deps/kissfft/kiss_fftr.c
g++ -std=c++17 -O2 -fPIC -I"$FX/src/engine" -I"$FX/src/engine/internal" -I"$FX/src/core" -I"$FX/deps" \
  "$T" /tmp/opencode/cab-test-o2/*.o -lm -lpthread -o cab_test
CAB_TIMING=1 ./cab_test
```

## Expected

`ALL PASS (failures: 0)`, exit 0, no ASan/UBSan reports. Post-fix reference
numbers (host x86-64, gcc 14.2): scenario A max diffs `0.000000000` for all
three variants; A2 max diff vs ground truth `0.000000060` for all four
segmentations; timing ≈ `0.094–0.098 ms` per cab block at every n (fft_size is
always 8192): 1.57 / 0.19 / 0.023 ms per 1024 frames for n = 64 / 512 / 4096 —
small callback blocks make the cab ~64× more expensive per frame; the adapter
should hand the engine large fixed blocks. Pre-fix (stock) measurements are in
`docs/guitar-fx-port.md` §Patches.
