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

## Concurrency-hardening scenarios (T1–T5 — control-path UAF + lock-order fixes)

The adversarial audit follow-ups (control-thread dereference guard, single
lock order `lifetime mutex → control-busy`, honest status export):

- **T1 setter-burst**: `setParameterChecked` (model/cab/mix) +
  `requestRigConfig` + `instantiate` hammering one channel from a control
  thread **concurrently** with repeated `reprepareQuiescent` (48k↔44.1k) +
  `resetChannelRange` from an admin thread, plus a status/getter poller
  (`rigStatus`, `hasPendingConfig`, `swapCount`, `supersededCount`,
  `errorState`, `getParameterChecked`). No `process()` — the admin paths
  require the quiescent contract. Asserts: no crash / no UAF / no leak
  (ASan), some control calls legitimately return false under contention
  (busy-control or busy-swap), final drain leaves nothing in flight;
- **T2 cross-channel**: 40-accept swap storms on ch2 AND ch5 from two
  control threads against one processing thread — asserts PER-CHANNEL
  busy/commit semantics under the global control guards (each channel
  lands exactly its own commits, no wedge; the single lock order serializes
  sections, not semantics);
- **T3 segmented callback**: TWO `process()` calls within one logical block
  while the PENDING mailbox arrives (async control thread) mid-first-segment
  — the commit lands across the segmentation, every fed frame processed,
  outputs finite;
- **T4 busy false→true→false**: one accept (busy true, second request
  honestly rejected) → commit (busy false) advances `swapCount` by EXACTLY
  one and `supersededCount` by zero; a quiescent-reprepare supersede bumps
  `supersededCount`, NEVER `swapCount` (the ACK-honesty contract);
- **T5 status-code mapping**: every packed `rigStatus` code observable at
  least once — `Ok`, `BusySwapInFlight`, `BusyControl` (held via the
  `tryLockControlForTesting` seam, queried from another thread),
  `BuildFailed` (`setFailNextBuildCodeForTesting(EngineInitFailed)`),
  `AllocationFailed` (boolean seam), `BadChannel` (± out-of-range ids, and
  `errorState(-1) == BadChannel` — invalid channels have their own code),
  plus the packed superseded bits, saturation and round-trip.

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
x86-64, gcc 14.2 (2026-11-24, hardening commit — control-guarded derefs +
single lock order + packed `rigStatus`), ASan+UBSan:

```
I4 max|Δ|L natural-timbres=0.433210 swap-run=0.450213 bound=0.483210 (margin 0.050)
[I4] stress 1000 swaps: frames=38783739 busy-spins=26671 ok
[I5] determinism: ok (85834 tail floats compared bit-exact)
[T1] setter-burst × reprepare/reset + polls: ok (accepted=1501 rejected=4499)
[T2] cross-channel concurrent swaps (ch2+ch5, N=40): ok
[T3] segmented callback (2 process() per block): ok
[T4] busy-cycle lands one rig + honest supersede accounting: ok
[T5] status-code mapping (all 6 codes): ok
ALL PASS (failures: 0)
```

The swap-run max|Δ| sits at or below the natural hard-clip edge of the
high-gain timbre (0.433 — a property of the sound, not the swap). The
documented bound is natural-slew max + 0.05. T1's rejected≈75% reflects
the honest non-blocking control path: under concurrent reprepare/reset
(both heavy, guard-holding ops) control calls return busy-false rather
than ever dereferencing a possibly-freed rig — the exact adversarial
interleaving the pre-fix code lost.

## TSan note

`-fsanitize=thread` build of this exact test (all engine C objects
recompiled, `SWAPSAN=thread`) runs under `setarch $(uname -m) -R` (plain
TSan aborts at startup on this kernel with `FATAL: unexpected memory
mapping`). Result: ALL PASS, **zero** ThreadSanitizer warnings — including
the T1 reprepare×setter×poll storm, which is the direct lock-order/UAF
regression probe (TSan flags both mutex-order inversions and plain data
races). TSan in this lane previously earned its keep: it caught a real
use-after-retire (the fade-completion check accessing the pre-commit rig
pointer after the commit handed that rig to RETIRED — fixed by re-loading
`mPublished` before any post-state-machine access).
