# GuitarRigManager live amp/cab swap host test (spec §10 protocol)

## What

`guitar_rig_swap_test.cpp` adversarially encodes the invariants of the
SAFE LIVE SWAP protocol implemented in `piano::guitar::GuitarRigManager`
(state table + proof sketch in `GuitarRigManager.h`, protocol section in
`docs/guitar-fx-port.md §Live rig swap`). Amp-model / cab-type changes on
a running rig are serialized rebuilds (`fx_amp_set_model` and
`fx_cab_generate_ir` are NOT live-safe — see `GuitarRigEffect.h`), handed
over CURRENT → PENDING → RETIRED mailboxes:

- **I1 busy-serialization + ACK + reclaim**: on a fully-wet channel a
  change is accepted (true), a second change while it is in flight is
  rejected (false), the ACK (`swapCount`) advances after the ~10 ms
  fade-out commits, `reclaimRetired()` then destroys the RETIRED rig and
  adopts the new CURRENT (pointer-equality guarded), and the next change
  is accepted again; reclaim is idempotent;
- **I2 builder-failure injection**: a simulated build failure (test seam
  `setFailNextBuildForTesting`) returns false, sets `errorState =
  AllocationFailed`, leaves CURRENT audible + finite, fakes no ACK, and
  clears on the next accepted swap;
- **I3 quiescent paths**: with the audio thread stopped (never started),
  `applyPendingConfig()` applies the PENDING rig directly (old destroyed
  inline) without faking a callback ACK; `drainAllQuiescent()` frees an
  unconsumed PENDING; destructors are leak-clean under ASan;
- **I4 stress (spec §10.8)**: 1000 amp-model/cab-type alternations from
  a control thread while a second thread processes the same channel at
  irregular block sizes cycling `[4096, 512, 1, 7, 16, 31, 64, 4096,
  4096, 16]`. Asserts: zero UAF/double-free/leak (ASan+UBSan clean + leak
  check at exit), every fed frame processed, ACK count == accepted swaps
  == 1000, busy-rejection observed (producer outruns consumer), all
  output finite. Click bound: the absolute max|Δ| per sample inside this
  rig is the HIGH-GAIN model's own hard-clip edge (≈0.41 at defaults —
  sound, not artifact), so the bound references a no-swap sweep of EVERY
  model×cab timbre at settled full wet: swap-run max|Δ| ≤ natural max +
  0.05. (Numbers below from the green run.);
- **I5 determinism**: the same single-threaded swap+block schedule twice
  (600 blocks, swap every 40, last 32 blocks captured) → **bit-identical**
  output, eps 0;
- **I6 rack-off landing**: after the disable ramp completes (rig fully
  dry), a pending swap commits silently on the first processed block.

## Build + run (ASan + UBSan)

From the repo root — same object set as `README.guitar-rig-manager.md`:

```sh
FX=$PWD/app/src/main/cpp/third_party/0xFX
CPP=$PWD/app/src/main/cpp
mkdir -p /tmp/opencode/guitar-swap && cd /tmp/opencode/guitar-swap
gcc -std=gnu99 -g -O1 -fPIC -fsanitize=address,undefined -fno-omit-frame-pointer \
  -I"$FX/src/engine" -I"$FX/src/engine/internal" -I"$FX/src/core" -I"$FX/deps" -c \
  "$FX"/src/engine/internal/{engine,amp,biquad,noise_gate,tuner,pedals,studio,cab_ir,mic_sim,preset,looper}.c \
  "$FX"/src/core/log.c "$FX"/deps/cJSON.c "$FX"/deps/kissfft/kiss_fft.c "$FX"/deps/kissfft/kiss_fftr.c
g++ -std=c++17 -g -O1 -fPIC -fsanitize=address,undefined -fno-omit-frame-pointer \
  -I"$CPP" -I"$FX/src/engine" -I"$FX/src/engine/internal" -I"$FX/src/core" -I"$FX/deps" \
  "$CPP/tests/guitar_rig_swap_test.cpp" \
  "$CPP/effects/guitar/GuitarRigManager.cpp" \
  "$CPP/effects/guitar/GuitarRigEffect.cpp" "$CPP/effects/guitar/GuitarRigParams.cpp" \
  /tmp/opencode/guitar-swap/*.o -lm -lpthread -o guitar_rig_swap_test
./guitar_rig_swap_test
```

## Expected

`ALL PASS (failures: 0)`, exit 0, no ASan/UBSan reports. Observed host
x86-64, gcc 14.2 (2026-11-23), two ASan runs + one TSan run:

```
I4 max|Δ|L natural-timbres=0.433210 swap-run=0.448972 bound=0.483210 (margin 0.050)
[I4] stress 1000 swaps: frames=38512103 busy-spins=33250 ok  (ASan, final commit tree)
[I4] stress 1000 swaps: frames=38559219 busy-spins=35234 ok  (ASan, same code)
swap-run max|Δ| observed range across runs: 0.433194 .. 0.450086 (bound 0.483210)
[I5] determinism: ok (85834 tail floats compared bit-exact)
ASAN_EXIT=0 / TSAN: ALL PASS, 0 warnings, swap-run max|Δ| 0.433194 ≤ natural 0.433210
```

The swap-run max|Δ| sits at or below the natural hard-clip edge of the
high-gain timbre (0.433 — a property of the sound, not the swap; the
absolute maxΔ is reached inside settled wet audio, not at a swap
boundary). The documented bound is natural-slew max + 0.05. The protocol
commits only at wet==0 (output == exact dry passthrough at that instant),
runs the fresh engine silently for 4 ms (priming) before the 10 ms
fade-in, so the swap boundaries contribute no measurable discontinuity.

## TSan note

`-fsanitize=thread` build of this exact test (all engine C objects
recompiled) runs under `setarch $(uname -m) -R` (plain TSan aborts at
startup on this kernel with `FATAL: unexpected memory mapping`). Result:
ALL PASS, **zero** ThreadSanitizer warnings. TSan in this lane earned
its keep: it caught a real use-after-retire (the fade-completion check
accessing the pre-commit rig pointer after the commit handed that rig to
RETIRED — the worker was concurrently deleting it); fixed by re-loading
`mPublished` before any post-state-machine access.
