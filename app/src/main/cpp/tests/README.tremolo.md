# Tremolo (instrument rack slot 8) — host test

Tremolo is a custom C++ audio effect (`app/src/main/cpp/effects/TremoloEffect.h`,
header-only) registered as instrument rack slot `kSlotTremolo = 8` with stable
id `piano.tremolo`. It has NO LADSPA binding: no port table, no LSP bundle
dependency, latency 0. Parameters (stable ids, block 410):

- `kParamTremoloRate = 410` — rate, 0.1..20 Hz, default 5, logarithmic
- `kParamTremoloDepth = 411` — depth, 0..100 %, default 50

"Enabled" is the existing per-slot rack switch; the wet-mix ramp is inherited
from `InstrumentEffectManager`. DSP: mono LFO,
`gain = 1 - d*0.5*(1 - sin(phase))`, `phase += 2*pi*rate/sr` (held in cycles,
wrapped); the applied depth ramps over 5 ms (same crossfade time as the rack's
wet-mix fade); depth 0 is a bit-exact passthrough.

Executable: `app/src/main/cpp/tests/tremolo_effect_test.cpp` — descriptor/id
wiring, bit-exact depth=0, gain bounds + attenuation at depth=100/5 Hz, L/R
symmetry (1e-6), split-run vs single-run equivalence (1e-5, state persists
across `process()` calls), rate-change discontinuity bound (< 0.01 per sample),
hard bypass, parameter clamping.

Plain g++ build + run (from the repo root; no Android, no LSP .so):

```sh
g++ -std=c++17 -O2 -Wall -Wextra -pthread \
  -I app/src/main/cpp -I app/src/main/cpp/effects \
  app/src/main/cpp/tests/tremolo_effect_test.cpp \
  app/src/main/cpp/effects/lsp/LspEffectIds.cpp \
  -o /tmp/opencode/tremolo_effect_test
/tmp/opencode/tremolo_effect_test
```

Expected: `tremolo effect test: PASS`, exit code 0.
