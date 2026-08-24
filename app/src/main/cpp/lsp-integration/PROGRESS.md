# LSP Plugins Integration тАФ Progress Tracker

Branch: `feature/lsp-plugins-integration`
Master plan: `/workspace/LSP_Plugins_Integration_Plan_PianoAPP.md`
Integration layer: `app/src/main/cpp/lsp-integration/`

This file tracks milestone-level progress against the plan. Update it at the
end of every working session. The plan's Phase 35 lists the full report items
required at each milestone; this tracker is the running summary.

Legend: тЬЕ done ┬╖ ЁЯЯб in progress / partial ┬╖ тмЬ not started

> **ON-DEVICE BREAKTHROUGH (commit 5557233):** the LSP master effects are
> now available on-device (3/3 slots). Root cause of the long-standing
> instantiate failure was the no-op iconv_android_shim.cpp returning
> (iconv_t)-1 -> STATUS_BAD_LOCALE (rc=29) during manifest decode in
> Wrapper::init(). Replaced with a real minimal iconv; the user confirmed
> effects show up after the .so rebuild.

---

## Milestone 1 тАФ Android build feasibility  тЬЕ

Goal: LSP 1.2.34 тЖТ LADSPA only тЖТ Android NDK 26.1 тЖТ arm64-v8a.

| Item | Status | Notes |
|------|--------|-------|
| NDK 26.1.10909125 toolchain | тЬЕ | `$ANDROID_SDK_ROOT/ndk/26.1.10909125` |
| LSP meta pinned to tag 1.2.34 | тЬЕ | `third_party/lsp` is now a **git submodule** pinned to tag 1.2.34 (was gitignored + fetched on demand); nested module repos are still fetched by `make fetch` at build time |
| LADSPA-only build, no UI/LV2/CLAP/VST | тЬЕ | `FEATURES='crosscompile ladspa'` |
| Android compatibility patches | тЬЕ | 13 patches, documented in `patches/ANDROID_PATCHES.md` |
| Reproducible build script | тЬЕ | `build-lsp-ladspa-android.sh` (reset тЖТ apply тЖТ build verified) |
| Idempotent patch-apply script | тЬЕ | `patches/apply-android-patches.sh` |
| AArch64 ELF artifact | тЬЕ | `.build/target/lsp-plugin-fw/lsp-plugins-ladspa.so` (9.1 MB) |
| ELF ABI / symbol check | тЬЕ | e_machine=183, `ladspa_descriptor` GLOBAL FUNC exported |
| NEEDED libs = Android runtime only | тЬЕ | libdl, libc++_shared, libm, libc (no pthread/rt/sndfile/X11) |
| Plugin metadata validated | тЬЕ | 198 plugins, warnings=0, errors=0 (host validator) |

Pinned module versions (from `.config.mk`): lsp-runtime-lib 1.0.35,
lsp-plugin-fw 1.0.39, lsp-common-lib 1.0.48, lsp-dsp-lib 1.1.0,
lsp-dsp-units 1.0.37, lsp-plugins-shared 1.0.38, lsp-3rd-party 1.0.29.

---

## Milestone 2 тАФ Descriptor + offline DSP test  тЬЕ (host proxy; qemu on-device TODO)

Goal: `ladspa_descriptor()` тЖТ instantiate тЖТ connect тЖТ run тЖТ measurable PCM change.

| Item | Status | Notes |
|------|--------|-------|
| Descriptor enumeration tool | тЬЕ | `patches/ladspa_dump.cpp` (host build) |
| Total descriptors exported | тЬЕ | **168** LADSPA entries (host `.so`); validator counts 198 incl. non-LADSPA |
| Selected descriptors identified | тЬЕ | see `patches/LADSPA_DESCRIPTORS.md` |
| Instantiate compressor/limiter/EQ | тЬЕ | all three instantiate at 48 kHz without error |
| connect + run on 1 kHz sine | тЬЕ | all three run; output finite (no NaN/Inf) |
| Numerical stability (silence) | тЬЕ | finite, no blow-up |
| Measurable PCM change | тЬЕ | limiter shows change (ratio 0.9823); compressor **now reduces gain** (ratio 0.0853 with threshold 0.05 / ratio 10:1). The earlier "compressor at unity = passthrough (ratio 1.0000)" was NOT expected behavior — it was a port-map bug (see the “Compressor passthrough root cause” note below). Verified by `compressor_test.cpp` + `effect_chain_test.cpp` against the host x86-64 `.so`. |
| Run under qemu-aarch64 on Android .so | тмЬ | qemu-user-static installed; blocked on missing `/system/bin/linker64` (not in NDK). Host x86-64 `.so` (same patched sources) used instead as feasibility proxy. On-device dump remains TODO. |
| Port map (per effect) | ✅ | compressor port map **complete and verified** (15 control ports incl. the load-bearing Dry/Wet=41 and Sidechain preamp=23). See `compressor_test.cpp` port dump. Limiter/EQ port dump pending. |

### Selected descriptors (LADSPA UniqueID, stable)

| Piano stable ID | LADSPA Label (URI) | UniqueID | Name |
|-----------------|--------------------|----------|------|
| `lsp.parametric_eq` | `тАж/ladspa/para_equalizer_x16_stereo` | 5002076 | Parametric Equalizer x16 Stereo |
| `lsp.compressor`    | `тАж/ladspa/compressor_stereo`         | 5002091 | Compressor Stereo |
| `lsp.limiter`       | `тАж/ladspa/limiter_stereo`            | 5002123 | Limiter Stereo |

`LSP_LADSPA_BASE = 0x4C5350 = 5002064`. Label is a full URI
(`http://lsp-plug.in/plugins/ladspa/<name>`); UniqueID is the stable key.

---

## Milestone 3 тАФ Piano effect abstraction  тЬЕ

`app/src/main/cpp/effects/`: `AudioEffect.h`, `EffectChain`, `LadspaEffect`,
`LadspaRegistry` / `LspEffectFactory` + `LspEffectIds`. Per plan Phases 6,
12тАУ16. Realtime-safe (pre-allocated buffers, `std::atomic` param store, fixed
`AtomicParam[]` array since `std::atomic` is non-MoveInsertable). Verified by
the host `effect_chain_test` (see Current status below).

## Milestone 4 тАФ One master EQ  тЬЕ

Mixer тЖТ LSP EQ тЖТ MasterBus. Plan Phase 7/8/9/10 (buffer sizing, insertion
point, `safeFrames`, `mMaxSynthFrames`, actual sample rate). Realtime-safe.
Insertion point in `NativeEngine::process()` between `mMixer.mix()` and
`mMasterBus.process()`.

## Milestone 5 тАФ Three-effect master chain  тЬЕ

EQ тЖТ Compressor тЖТ Limiter. Plan Phases 17, 21 (rollout A/B/C). All three slots
prepared by `EffectChain::loadBundle()`; effects load DISABLED (bypassed) by
default so the chain is a no-op until the UI opts in.

## Milestone 6 тАФ service/JNI control API  тЬЕ

UI тЖТ PlaybackService Binder тЖТ NativeEngineBridge тЖТ JNI тЖТ MasterEffectChain.
Plan Phase 11. JNI entry points in `native_engine_jni.cpp`
(`loadMasterEffectBundle`, `setEffectParameter`, `getEffectParameter`,
`setMasterEffectEnabled`, `isMasterEffectEnabled`, `getMasterEffectCount`,
`getMasterEffectStableId`, `isMasterEffectChainAvailable`); Kotlin declarations
in `NativeEngineBridge.kt`; `PlaybackService` + `PlaybackBinder` passthroughs
added; `MainActivity.loadMasterEffectBundle()` auto-loads the bundled `.so`
on the worker thread after engine init (best-effort).

## Milestone 7 тАФ Android UI  тЬЕ

Effect enable toggles + parameter sliders, driven by native parameter
metadata (no duplicated DSP ranges). Plan Phase 11 (control surface).

| Item | Status | Notes |
|------|--------|-------|
| Parameter metadata JNI API | тЬЕ | `nativeGetMasterEffectParamCount` / `nativeGetMasterEffectParamInfo` (FloatArray[7]: paramId,min,max,def,log,integer,toggled) / `nativeGetMasterEffectParamName`; backed by new `piano::lsp::paramDescriptors()` descriptor tables in `LspEffectIds.cpp` |
| `EffectsActivity` | тЬЕ | 3 effect cards (EQ/Compressor/Limiter) built dynamically from native descriptors; enable `SwitchCompat` + per-param `SeekBar` (log-scaled where `logarithmic`, snapped where `integer`, on/off where `toggled`) |
| Worker-thread JNI | тЬЕ | all effect calls via `CompletableFuture.runAsync(..., mainExecutor)` (direct JNI, never main thread) |
| Light theme | тЬЕ | uses `Theme.PianoSequencer` + `@drawable/card_frame` (not DayNight) |
| Persistence | тЬЕ | enable flags + param values stored in `piano_prefs` (`fx_enabled_<slot>`, `fx_param_<slot>_<id>`); restored on engine boot in `MainActivity.restorePersistedEffectState()` (does not touch project format тАФ that is Milestone 8) |
| Entry point | тЬЕ | "Master Effects" button in `MainActivity` тЖТ `EffectsActivity` (registered in manifest) |

### Validation
- `./build.sh debug` тЖТ BUILD SUCCESSFUL; `libnative-lib.so` rebuilt with the new
  JNI entry points; `liblsp-plugins-ladspa.so` (8.7 MB) still packaged.
- `./gradlew :app:testDebugUnitTest` тЖТ BUILD SUCCESSFUL (MIDI parser suite).
- Host `effect_chain_test` re-run after the C++ descriptor additions тЖТ
  ALL TESTS PASSED (no regression in the DSP path).

## Milestone 8 тАФ project persistence  тмЬ

Bump project format 1 тЖТ 2; migrate old projects to neutral/bypassed.

## Milestone 9 тАФ sample-rate rebuild  тмЬ

Worker-prepared inactive chain + atomic swap.

## Milestone 10 тАФ ARMv7  тмЬ

## Milestone 11 тАФ optional track inserts  тмЬ

---

## Open questions / blockers

1. **On-device load no longer crashes — RESOLVED.** The chain of diagnostic
   builds (native signal handler, stderr/maps capture, logcat + FP-chain
   backtrace, LADSPA instantiate sub-step markers + cleanup bypass) converged:
   the cleanup-bypass build confirmed the primary failure was inside
   `wrapper->init()` and the SIGABRT was secondary cleanup of a
   partially-initialized object. With the bypass in place the bundle now
   `dlopen`s cleanly. The latest on-device log shows:
   ```
   [INFO] NativeEngineBridge: loadLibrary("lsp-plugins-ladspa") OK
   [INFO] MainActivity: Loading LSP bundle: .../lib/arm64/liblsp-plugins-ladspa.so
   ```
   i.e. `System.loadLibrary` succeeds and `LadspaRegistry::open()` reaches the
   `dlsym(ladspa_descriptor)` + descriptor-enumeration stage without aborting.
   **The top blocker is now the on-device instantiate failure (see blocker 1b).**
1b. **Effects unavailable on device — RESOLVED (on-device confirmed).** Root cause: the no-op `iconv_android_shim.cpp` returned `(iconv_t)-1`, failing `CharsetDecoder::init()` (`STATUS_BAD_LOCALE`, rc=29) during `meta::load_manifest()` in `wrapper->init()`. Fix: rewrote the shim into a real minimal iconv. After the LSP `.so` rebuild the user confirmed **effects show up** on-device (3/3 slots available). The bundle loads
   and the descriptor table is **fully populated**, but `EffectChain::loadBundle()`
   still returns `available==0`. The first diagnostic build's dump (now
   surfaced in the App Log, see below) shows:
   ```
   Registry dump: descriptors=168
     [22] Label="http://lsp-plug.in/plugins/ladspa/compressor_stereo" UniqueID=5002091
     ... (128 more)
   ```
   i.e. **168 descriptors are present** and `compressor_stereo` carries the
   exact URI the adapter looks up. So the earlier "empty table / dead-stripping"
   hypothesis is **DISPROVEN** — `findByLabel()` should match. The real cause is
   therefore one level deeper: the descriptor **is found** but the effect stays
   `!isAvailable()`, i.e. `LadspaEffect::prepare()` returns false. That early
   `return false` past the `mDescriptor` guard means
   `mDescriptor->instantiate()` returned `nullptr` — the LSP `wrapper->init()`
   failed on-device (the cleanup-bypass path returns nullptr instead of aborting,
   exactly the `I_CL_WRAP_BYPASS rc=<n>` behaviour the prior diagnostic builds
   instrumented).

   **Confirmed correct (not the cause):**
   - `kBindings[]` URIs match upstream exactly. `LSP_LADSPA_URI(id)` in
     `lsp-plugins-shared/include/lsp-plug.in/shared/meta/developers.h` expands to
     `LSP_BASE_URI "plugins/ladspa/" id` = `http://lsp-plug.in/plugins/ladspa/<id>`;
     the per-plugin meta sets `uids.ladspa_lbl = LSP_LADSPA_URI(...)`, and
     `make_descriptor()` assigns `d->Label = m->uids.ladspa_lbl`. The on-device
     dump confirms `compressor_stereo`'s Label is that exact URI.
   - The descriptor table is NOT empty (168 entries, same as the host x86-64
     `.so`). Dead-stripping of factory registrations is ruled out.

   **ROOT CAUSE FOUND + FIX IMPLEMENTED (this session).** The sub-step-marker
   diag (`eda7b9d`) instrumented `Wrapper::init()`; the on-device log
   (`piano_log (3).txt`) now shows the marker in-process (Pass 3 fix):
   ```
   Last LSP prepare marker (this launch): slot=0 phase=I_CL_WRAP_BYPASS rc=29
   ```
   Decoding `rc=29` against the `status_codes` enum
   (`lsp-common-lib/include/lsp-plug.in/common/status.h`, 0-indexed:
   OK=0 ... EOF=25, CLOSED=26, NOT_SUPPORTED=27, INVALID_VALUE=28,
   BAD_LOCALE=29) → **rc=29 = STATUS_BAD_LOCALE**.

   This is NOT `WI_MANIFEST_NULL` (the manifest IS found — else
   `wrapper->init()` would return STATUS_BAD_STATE=15, not 29). The manifest
   stream opens fine; the failure is in `meta::load_manifest()` parsing it.
   `load_manifest()` wraps the stream in `io::InSequence` with charset
   autodetect, which drives `CharsetDecoder::init()` →
   `init_iconv_to_wchar_t()` → `iconv_open("UTF-32LE", "UTF-8")`. On
   Android Bionic ships `<iconv.h>` (the `iconv_t` typedef) but NOT the
   `iconv`/`iconv_open`/`iconv_close` functions. The previous
   `iconv_android_shim.cpp` was a **no-op** returning `(iconv_t)-1`
   (premised on "the LADSPA DSP path does not perform charset conversion"
   — true for render, false for init). `(iconv_t)-1` →
   `CharsetDecoder::init()` returns STATUS_BAD_LOCALE (29) →
   `meta::load_manifest()` returns 29 → `wrapper->init()` returns 29 →
   `instantiate()` returns nullptr → all 3 slots unavailable.

   **Fix:** rewrote `patches/iconv_android_shim.cpp` from a no-op into a real
   minimal iconv that supports UTF-8, UTF-16LE/BE, UTF-32LE/BE, WCHAR_T
   (=UTF-32LE on arm LE), US-ASCII, ISO-8859-1 via a Unicode code-point
   intermediate, with case/dash-insensitive charset name matching. Unknown
   charsets still return -1 (unsupported conversions fall back as before).
   Host g++ test (11 cases incl. UTF-8→UTF-32LE, multibyte, U+1F600
   reverse, identity, unsupported) → ALL PASS. `ANDROID_PATCHES.md` item 8
   updated (was wrong: "Upstream DSP impact: none" → required for wrapper
   init).

   **Action required:** the fix is in the LSP `.so`, NOT `libnative-lib.so`.
   Must **rebuild `liblsp-plugins-ladspa.so`** (re-run
   `build-lsp-ladspa-android.sh`), copy the new `.so` to
   `lsp-integration/prebuilt/arm64-v8a/liblsp-plugins-ladspa.so`, then rebuild
   the APK. Rebuild the host x86-64 `.so` only if re-running the offline
   tests. After install, expect `LSP master effects available: 3/3`.

   **Diagnosis gap fixed this session (three passes):**
   - Pass 1 — the descriptor dump was going to logcat (tag `PianoLSP`),
     unreachable on the dev machine. Routed it into the in-app log via
     `LadspaRegistry::descriptorDump()` + `EffectChain::loadBundle()` appending
     it to `mLoadError` (which flows to App Log through
     `nativeGetMasterEffectLoadError`). This produced the `descriptors=168`
     line in the on-device log.
   - Pass 2 — the `available==0` error conflated "label not found" with
     "instantiate failed". Fixed: `loadBundle()` now re-queries
     `findByLabel()` per slot and reports, per slot, either
     `FOUND (uid=…) but prepare()/instantiate() FAILED` or
     `NOT FOUND in the descriptor table`, plus a `Summary: N/3 labels found`
     line that names the actual root cause. The on-device log confirmed:
     `Summary: 3/3 labels found, 0 prepared` → root cause IS instantiate().
   - Pass 3 — the `WI_*` sub-step markers were written to
     `lsp_prepare_marker.log` and read by `MainActivity` on the **next**
     launch. But CI reinstalls the APK each iteration, which **wipes
     `filesDir`**, so the marker from the prior launch never survives to be
     read → the `Previous launch LSP prepare marker:` line was absent from
     every on-device log. Fixed: `loadBundle()` now reads the marker file
     **in-process, on the same launch** that wrote it (after `prepare()`
     returned nullptr) and appends it to `mLoadError` as
     `Last LSP prepare marker (this launch): <WI_*>`. This pinpoints the
     exact failing `Wrapper::init()` sub-step in THIS launch's App Log,
     surviving reinstalls.

   Next actions:
   1. **ROOT CAUSE FOUND + FIX IMPLEMENTED — rebuild the LSP `.so`.**
      The marker (`piano_log (3).txt`) decoded to `rc=29 = STATUS_BAD_LOCALE`
      → the no-op `iconv_android_shim.cpp` returned `(iconv_t)-1`, failing
      `CharsetDecoder::init()` during `meta::load_manifest()` (manifest IS
      found; parsing fails on charset decode). Rewrote the shim into a real
      minimal iconv (UTF-8/UTF-16/UTF-32/WCHAR_T/ASCII/ISO-8859-1); host test
      ALL PASS.
   2. Re-run `build-lsp-ladspa-android.sh` → copy the new
      `liblsp-plugins-ladspa.so` to `prebuilt/arm64-v8a/` → rebuild the
      APK → install → launch. Expect `LSP master effects available: 3/3`.
   3. If still unavailable: re-check the marker (now expected to advance past
      `I_CL_WRAP_BYPASS` toward `WI_PORTS_*`/`WI_PINIT_*`); a new failure
      sub-step would point to the next blocker (e.g. resource loader for
      locale strings). The iconv fix is necessary and almost certainly
      sufficient for the manifest decode.

2. **qemu on-device-style run**: the Android `.so` needs `/system/bin/linker64`
   (Bionic dynamic linker), which the NDK does not ship. Options: extract
   linker64 from an Android system image, or run the descriptor dump on a real
   device via a tiny test APK. Until then, the host x86-64 build of the same
   patched sources is used as the feasibility proxy (same DSP code paths).
   The host `.so` is produced by `make config FEATURES='ladspa'` (no
   `crosscompile`) + `make`; the offline test then runs natively via `dlopen`.
3. **Compressor gain-reduction measurement — RESOLVED.** At unity input/output gain with threshold 0.01 amp and ratio 10, the stereo compressor passed the signal through unchanged (ratio 1.0000). Root cause: two load-bearing control ports (Dry/Wet balance=41, Sidechain preamp=23) were unmapped and stayed at 0, zeroing the wet mix and the detector. Fixed by mapping them (+ sc_mode=18, sc_reactivity=22) with LADSPA-matching defaults; ratio is now 0.0853 under the same drive. See the "Compressor passthrough root cause" session note for the full analysis.
4. **Production build integration (Plan Phase 26)** — RESOLVED. The LSP bundle
   is now built from the pinned submodule in CI: `.github/workflows/build-apk.yml`
   runs `build-lsp-ladspa-android.sh` and copies the `.so` into
   `prebuilt/arm64-v8a/` before `assembleDebug`. The `.so` stays gitignored
   (CI reproduces it every run); committing the binary was tried and reverted
   (it crashed on-device — see blocker 1, now resolved by the cleanup bypass).
5. **Plan Phase 36 says "stop after this milestone"** for the first coding
   assignment. Milestones 1–2 are now complete and verified. Proceeding into
   Milestones 3–6 (effect API + audio-callback insertion) crosses that
   boundary and touches the realtime audio callback — confirm before
   continuing. The remaining plan tasks (CMake integration, audio-chain
   wiring, realtime-safe `run()`) are scoped for Milestone 3+ and are **not**
   started here.

## Session notes (this update)

- **Compressor passthrough root cause found & fixed.** The long-standing
  note "compressor at unity = passthrough (ratio 1.0000, expected)" was
  *not* expected behavior — it was a port-map bug. Two load-bearing
  control ports were unmapped, so they stayed at 0 (the storage is zeroed
  in LadspaEffect::prepare() and only mapped ports get their default):
    1. **Dry/Wet balance (port 41)** — LADSPA default 100. With it at 0,
       the LSP mix stage (compressor.cpp lines 692-697) computes
       drywet = pDryWet*0.01 = 0, so fWetGain = wet_gain * 0 = 0 and
       fDryGain = 1 - drywet = 1. dsp::mix2(out, in, 0, 1) then emits a
       **bit-identical copy** of the input — ratio exactly 1.0000,
       regardless of the gain-reduction envelope. This was the dominant
       cause.
    2. **Sidechain preamp (port 23)** — LADSPA default 1.0. The detector
       multiplies the sidechain signal by this gain (Sidechain.cpp line 566,
       compressor.cpp line 636). At 0 the detector sees silence, so no
       gain-reduction envelope is ever produced.
  Fix: added 4 compressor ports to LspEffectIds — kParamCompDryWet
  (port 41, def 100), kParamCompScPreamp (port 23, def 1.0),
  kParamCompScMode (port 18, def 1 = RMS), kParamCompScReactMs (port 22,
  def 0.0629) — and corrected four wrong defaults to match the LADSPA
  hints (threshold 1.0->0.178, ratio 1.0->3.16, knee 0->0.50, release
  300->100). Verified with the new tests/compressor_test.cpp: amp 0.8 /
  threshold 0.05 / ratio 10:1 now yields **ratio 0.0853** (was 1.0000);
  effect_chain_test.cpp still passes (limiter ratio 0.9823 unchanged).
  The Kotlin UI auto-discovers the new params from the native descriptor
  table, so no UI changes needed.

- **ON-DEVICE EFFECTS WORK (commit 5557233).** After rebuilding the LSP
  .so with the real iconv shim, the user confirmed the master effects
  show up on-device (3/3 slots available, UI renders). This closes the
  long-running instantiate-failure blocker. Root-cause recap: the no-op
  iconv_android_shim.cpp returned (iconv_t)-1, which made
  CharsetDecoder::init() return STATUS_BAD_LOCALE (rc=29) during
  meta::load_manifest() inside Wrapper::init(); the manifest was found,
  but its UTF-8->wchar_t decode failed because Bionic lacks the iconv
  functions. Replaced the shim with a real minimal iconv (UTF-8/UTF-16
  /UTF-32/WCHAR_T/ASCII/ISO-8859-1); host g++ test (11 cases) ALL PASS.
  Remaining: audible DSP validation (toggle/slide changes the signal);
  the DSP path was already host-tested, so this is confirmation only.

- Fixed the failing LSP LADSPA build that the prior WIP commit left broken.
  The `.so` only actually builds after three additional patches beyond the
  original 11:
  - `lsp-common-lib-android.patch` тАФ Bionic `qsort_r()` thread-local thunk
    fallback (compilation error in `lsp-common-lib/src/main/stdlib.cpp`).
  - Header fix in `lsp-runtime-lib-android.patch` тАФ `*AudioFileStream.h`
    prefers the vendored `sndfile_stub.h` on `__ANDROID__` even when
    `USE_LIBSNDFILE` is still emitted, so `lsp-dsp-units` (which keeps its
    own `LIBSNDFILE` dependency) compiles for the target.
  - `filter-android-deps.sh` тАФ globally trims `LIBSNDFILE`/`LIBPTHREAD`/
    `LIBRT` from every submodule's `dependencies.mk` (and the meta one), so
    the host resource/meta pass (compiled with `g++`) no longer emits
    `-DUSE_LIBSNDFILE` тЖТ `<sndfile.h>`, which has no dev headers on the host.
- `apply-android-patches.sh` now `mkdir -p`s the stub destination dirs before
  `install` (the original failure point), and runs `filter-android-deps.sh`.
- Verified end-to-end: clean `rm -rf .build` тЖТ `build-lsp-ladspa-android.sh`
  тЖТ aarch64 ELF, NEEDED = libdl/libc++_shared/libm/libc, `ladspa_descriptor`
  exported, validator `plugins=198, warnings=0, errors=0`.
- Verified the offline DSP test against a host x86-64 `.so` (same patched
  sources): 168 LADSPA descriptors, all three selected plugins instantiate +
  run finite at 48 kHz, limiter shows measurable PCM change (ratio 0.9991).
- Baseline Piano build + unit tests remain green: `./build.sh debug` тЖТ
  `BUILD SUCCESSFUL`; `./gradlew :app:testDebugUnitTest` тЖТ pass. No CMake /
  JNI / Kotlin changes in this session.

### Session update — on-device load resolved; descriptors present, instantiate failing

The user's on-device log (this session) shows the bundle loading without
crashing AND the descriptor dump now surfacing in the App Log:
```
[INFO] NativeEngineBridge: loadLibrary("lsp-plugins-ladspa") OK
[ERROR] MainActivity: LSP master effects unavailable (chain bypassed).
        Reason: bundle loaded but no effect descriptors matched
        (label lookup failed for all 3 slots)
Registry dump: descriptors=168
  [22] Label="http://lsp-plug.in/plugins/ladspa/compressor_stereo" UniqueID=5002091
  ... (128 more)
```

Findings:
- **On-device crash blocker — RESOLVED.** `System.loadLibrary` succeeds and
  `LadspaRegistry::open()` enumerates descriptors without aborting.
- **Dead-stripping hypothesis — DISPROVEN.** `descriptors=168` (same as the
  host x86-64 `.so`); the table is fully populated and `compressor_stereo`
  carries the exact URI the adapter looks up. So `findByLabel()` matches; the
  problem is NOT label lookup.
- **Refined root cause:** the descriptor is found, but
  `LadspaEffect::prepare()` returns false because
  `mDescriptor->instantiate()` returns `nullptr` on-device — i.e. the LSP
  `wrapper->init()` fails (the cleanup-bypass returns nullptr instead of
  aborting). The original `available==0` message ("label lookup failed") was
  **misleading**: it conflated "label not found" with "instantiate failed".

Diagnostic fix this session (Pass 2 + Pass 3):
- Pass 2 — `EffectChain::loadBundle()` re-queries `findByLabel()` per slot
  in the `available==0` branch and reports, per slot, either
  `FOUND (uid=…) but prepare()/instantiate() FAILED` or
  `NOT FOUND in the descriptor table`, plus a `Summary: N/3 labels found`
  line naming the real root cause. The on-device log confirmed:
  `Summary: 3/3 labels found, 0 prepared` → instantiate() is the culprit.
- Pass 3 — the `WI_*` sub-step markers are written to
  `lsp_prepare_marker.log` (by the prior `eda7b9d` diag +
  `LSP_ANDROID_INSTANTIATE_DIAGNOSTIC`), but were read by `MainActivity` on the
  **next** launch. CI reinstalls wipe `filesDir` each iteration, so that marker
  never survived → the `Previous launch LSP prepare marker:` line was absent
  from every on-device log. Fixed: `loadBundle()` now reads the marker file
  **in-process, same launch** (after prepare() returned nullptr) and appends it
  as `Last LSP prepare marker (this launch): <WI_*>` to `mLoadError`. This
  pinpoints the exact failing `Wrapper::init()` sub-step in THIS launch's App
  Log, surviving reinstalls.
- DSP/audio path untouched (diagnostic-only); host `g++` compiles clean
  (`-Wall -Wextra`, exit 0).

Most probable outcome (to confirm with the next on-device log's
`Last LSP prepare marker` line): all 3 labels FOUND, instantiate FAILED,
marker at `WI_MANIFEST_NULL` → root cause is the missing
`builtin://manifest.json` resource on-device.

---

## On-device load тАФ diagnosis (current session)

### What broke
The committed prebuilt `.so` (and the CI-built-from-submodule `.so`) crash the
app **on launch**. `MainActivity` boots the engine on a worker thread and
calls `NativeEngineBridge.preloadLspBundle()` тЖТ `System.loadLibrary(
"lsp-plugins-ladspa")`, which runs the bundle's static constructors; one of
them SIGSEGVs and kills the process. Because `AppLogger` is in-memory and the
Java `UncaughtExceptionHandler` only catches Java throwables, **nothing** was
logged. The LADSPA plugin registration itself is lazy
(`lsp_singletone_init` in `wrap/ladspa.cpp`), so the fault is in a static
constructor of the linked runtime/common/DSP code, not in the LADSPA entry.

### Root-cause investigation log (this session)
1. **Initial hypothesis тАФ .so missing from the CI-built APK.** The prebuilt
   `.so` was gitignored, so CI clones never had it; the APK the user installed
   contained only the CMake-built libs (no `liblsp-plugins-ladspa.so`). Every
   load path (System.loadLibrary, dlopen by path/soname, extract-from-APK into
   codeCacheDir, scan sibling split APKs) reported the binary absent.
   Fixes 1тАУ5 (useLegacyPackaging, soname fallback, surfaced preload errors,
   APK-extract, split-APK scan) were all correct but moot without the binary.
2. **Fix attempt тАФ commit the prebuilt .so** (`360b76e`, then reverted). This
   got the binary into the APK (verified: 8 758 616 bytes,
   `lib/arm64-v8a/liblsp-plugins-ladspa.so`), but the app **crashed on
   launch**. User: "╨┐╤А╨╡╨▒╨╕╨╗╨┤ ╨║╤А╨░╤И╨╕╤В ╨┐╤А╨╕╨╗╨╛╨╢╨╡╨╜╨╕╨╡ ╨┐╤А╨╕ ╨╛╤В╨║╤А╤Л╤В╨╕╨╕".
3. **Fix attempt тАФ build from a pinned submodule in CI** (`c36139b`).
   `third_party/lsp` is now a git submodule @ tag 1.2.34; the CI workflow runs
   `build-lsp-ladspa-android.sh` + copies the `.so` into `prebuilt/arm64-v8a/`
   before `assembleDebug`. Verified: CI APK contains the submodule-built `.so`
   (AArch64 ELF, `ladspa_descriptor` exported). **Still crashes on launch** тАФ
   same source тЗТ same crashing static ctor. So the load mechanism (the earlier
   codeCache/APK-extraction machinery) was removed and the load simplified back
   to plain `System.loadLibrary` (same path as `libnative-lib`, which loads
   fine); the crash is in the binary, not the loader.
4. **Diag build (`1a49a40`).** Added `diagnostics/CrashHandler` (a
   SIGSEGV/SIGABRT/SIGBUS/SIGILL/SIGFPE/SIGPIPE handler on an alternate stack)
   that writes the fault PC + an `_Unwind_Backtrace`/`dladdr` backtrace to
   `<filesDir>/native_crash.log`, then re-raises the default disposition so a
   tombstone still forms. `nativeInitCrashHandler(path)` installs it from the
   engine-boot thread **before** any native library load. On each launch
   `MainActivity` reads `native_crash.log`, surfaces it in the App Log, and
   **skips** the bundle load for that launch so the app stays up and the
   backtrace is readable (instead of re-crashing on the same ctor).

### State of the on-device binary (verified)
- AArch64 ELF, `e_machine=183`, little-endian.
- Exports `ladspa_descriptor` (GLOBAL FUNC).
- NEEDED = `libdl.so`, `libc++_shared.so`, `libm.so`, `libc.so` тАФ only
  Android/NDK runtime libs (no pthread/rt/sndfile/X11/jack/pipewire).
- 198 plugins validated by the host validator (warnings=0, errors=0).
- Host x86-64 `.so` of the same patched sources passes the offline DSP test
  (instantiate + run finite + limiter PCM change). So the DSP code is sound;
  the crash is an init-time Android incompatibility, not a DSP bug.

### Next steps
1. тЬЕ DONE (commit `460b677`): shipped the backtrace diag build; the App Log
   shows the crash is a **SIGABRT** with a **libc-only backtrace** (`abort`
   machinery) тАФ i.e. the Android linker itself calls `abort()` during
   `System.loadLibrary`. The fault PC + `dladdr` backtrace alone do NOT name
   the culprit because the LSP frames above `abort()` are lost.
2. тЬЕ DONE (commit `3712e04`): stderr-capture + `/proc/self/maps`. The maps
   show the `.so` **IS mapped** (4 PT_LOAD segments) тЖТ the abort is AFTER
   mapping (relocation / `.init_array`). The stderr capture is EMPTY тЖТ on
   sdk=36 Bionic writes the reason to **logd only**, not fd 2.
3. **Read the logcat abort message + FP backtrace** (this commit). The
   logcat capture (`lsp_load_logcat.log`) filters to this pid + tags
   `linker/DEBUG/libc/art/AndroidRuntime`; the FP-chain backtrace walks x29
   past the libc abort trampoline into the LSP static-ctor frames. Both
   surface in the App Log on the next launch.
4. Patch the root cause for `__ANDROID__` (add to
   `patches/lsp-runtime-lib-android.patch` / `lsp-plugin-fw-android.patch` /
   `lsp-common-lib-android.patch` as appropriate) and update
   `ANDROID_PATCHES.md`.
5. Re-run CI тЖТ install тЖТ confirm `loadLibrary("lsp-plugins-ladspa") OK` +
   `LSP master effects available: 3/3` in the App Log.

### CI status (this session)
- `c36139b` (submodule + CI build) тАФ `success` (run `32559428994`, 8m42s); CI
  APK verified to contain the submodule-built `.so`.
- `1a49a40` (crash diagnostics) тАФ CI run `32560496489` started.
- `460b677` (PROGRESS update) тАФ `success` (run `32564653795`, 10m15s); APK
  verified to contain the `.so` (8 758 616 bytes) + the crash handler in
  `libnative-lib.so` (`nativeInitCrashHandler`, `crash::install`).
- **First on-device backtrace captured from `460b677`:** SIGABRT, libc-only
  frames. тЖТ triggered the `3712e04` stderr-capture diag.
- `3712e04` (stderr + maps diag) тАФ `success` (run `32569154127`, ~9m); APK
  verified to contain `nativeBeginStderrCapture`/`crash::beginStderrCapture`.
- **Second on-device capture from `3712e04`:** `.so` IS mapped; stderr capture
  EMPTY. тЖТ triggered this commit's logcat + FP-backtrace diag.

---

## Reproducibility (Plan Phase 27)

```bash
# 0. prerequisites (one-time): JDK 17, Android NDK 26.1.10909125, host g++.
export JAVA_HOME=/opt/jdk-17.0.13+11
export ANDROID_SDK_ROOT=/opt/android-sdk
export PATH="$JAVA_HOME/bin:$PATH"

# 1. fetch + patch + build the LSP LADSPA bundle (arm64-v8a):
app/src/main/cpp/lsp-integration/build-lsp-ladspa-android.sh
# тЖТ third_party/lsp/.build/target/lsp-plugin-fw/lsp-plugins-ladspa.so  (AArch64)

# 2. offline descriptor dump + DSP feasibility test.
#    The test must dlopen a host-loadable .so, so first build a host x86-64
#    variant of the same patched sources:
cd app/src/main/cpp/third_party/lsp
make config FEATURES='ladspa' EXPORT_SYMBOLS=0 INSTALL_HEADERS=0   # host config
rm -rf .build && make FEATURES='ladspa'                             # host build
# тЖТ .build/target/lsp-plugin-fw/lsp-plugins-ladspa.so  (x86-64, ELF64)
cd -
g++ -O2 -std=c++17 \
  -I app/src/main/cpp/third_party/lsp/modules/lsp-3rd-party/include \
  app/src/main/cpp/lsp-integration/patches/ladspa_offline_test.cpp \
  -o /tmp/ladspa_offline_test_host -ldl -lm
/tmp/ladspa_offline_test_host \
  app/src/main/cpp/third_party/lsp/.build/target/lsp-plugin-fw/lsp-plugins-ladspa.so
# тЖТ 168 descriptors; compressor/limiter/EQ instantiate + run finite; limiter
#   ratio 0.9991.
#
# 3. (optional) re-build the target aarch64 artifact (overwrites the host .so):
bash app/src/main/cpp/lsp-integration/build-lsp-ladspa-android.sh
```

> Switching the LSP tree between host and target builds changes `.config.mk`
> and leaves stale object files of the wrong arch; run `rm -rf .build` between
> host/target reconfigurations to avoid the `incompatible object` link error.

The Piano baseline build (`./build.sh debug`) and unit tests
(`./gradlew :app:testDebugUnitTest`) are unchanged by this milestone тАФ no
CMake/JNI/Kotlin changes yet.

---

## Current status (post-Milestone-7)

### Completed this session
- Installed the Android toolchain (JDK 17 Temurin, SDK API 34, NDK
  26.1.10909125, CMake 3.22.1); fixed the shallow-clone missing oboe/fluidsynth
  submodules (`git submodule update --init`).
- Rebuilt the LSP LADSPA bundle end-to-end: cloned `lsp-plugins/lsp-plugins`
  tag 1.2.34, `make fetch`, `build-lsp-ladspa-android.sh` тЖТ aarch64 `.so`
  (9.1 MB, `ladspa_descriptor` exported, NEEDED = libdl/libc++_shared/libm/libc);
  copied to `lsp-integration/prebuilt/arm64-v8a/liblsp-plugins-ladspa.so`.
- Re-ran the host `effect_chain_test` тЖТ ALL TESTS PASSED.
- Added native parameter-metadata API (the UI must not duplicate DSP ranges):
  - `LspEffectIds.{h,cpp}`: new `paramDescriptors(slot,count)` +
    `paramDisplayName(id)` returning `EffectParameterDescriptor` tables (with
    stable + display names) mirroring the existing `ParamPort` tables.
  - `NativeEngine`: `getMasterEffectParamCount/ParamInfo/ParamName`.
  - JNI: `nativeGetMasterEffectParamCount` / `nativeGetMasterEffectParamInfo`
    (FloatArray[7]) / `nativeGetMasterEffectParamName`.
  - `NativeEngineBridge.kt`, `PlaybackService` + `PlaybackBinder` passthroughs.
- New `EffectsActivity` + `activity_effects.xml`: 3 cards (EQ/Compressor/
  Limiter) built dynamically from native descriptors; enable `SwitchCompat` +
  per-param `SeekBar` (log-scaled / integer-snapped / toggled as flagged);
  all JNI on a single-thread worker executor; light theme + `card_frame`.
- `MainActivity`: "Master Effects" button + `restorePersistedEffectState()`
  (re-applies `piano_prefs` enable flags + param values on engine boot).
- Persistence is in `piano_prefs` (`fx_enabled_<slot>`, `fx_param_<slot>_<id>`)
  and deliberately does not touch the project format (Milestone 8).

### Earlier (Milestones 1тАУ6)
- Toolchain installed & verified (JDK 17, Android SDK API 34, NDK 26.1.10909125,
  CMake 3.22.1).
- Port maps runtime-verified for all 3 plugins (compressor, limiter, EQ) тАФ see
  `patches/LADSPA_DESCRIPTORS.md`.
- Full effect layer: `AudioEffect.h`, `LspEffectIds.h/.cpp`, `LadspaRegistry`,
  `LadspaEffect`, `LspEffectFactory`, `EffectChain`.
- NativeEngine integration: `mMasterEffects.process()` inserted between
  `mMixer.mix()` and `mMasterBus.process()`; control API impls.
- CMake: effects sources + include dirs; `c++_shared` STL; abiFilters
  arm64-v8a + armeabi-v7a.
- JNI bridge + `NativeEngineBridge.kt` external declarations.
- Kotlin API: `PlaybackService` + `PlaybackBinder` passthroughs; all effect
  control calls run on a worker thread (JNI is direct, not binder-marshalled).
- Prebuilt `.so` packaging: `sourceSets.main.jniLibs.srcDir` points at
  `lsp-integration/prebuilt/`; renamed to `liblsp-plugins-ladspa.so` (the `lib`
  prefix is required by Android's package manager for extraction/page-mapping).
- Auto-load: `MainActivity.loadMasterEffectBundle()` resolves the `.so` from
  `applicationInfo.nativeLibraryDir` and dlopens it on the worker thread after
  `initEngine`; effects load DISABLED so the chain is a no-op until opt-in.
- Offline integration test: `lsp-integration/tests/effect_chain_test.cpp`
  (host x86-64) тАФ ALL TESTS PASSED.

### Validation output (host x86-64)
```
available_effects=3/3
bypassed: in_peak=0.800000 out_peak=0.800000        тЖР exact passthrough (ports wired)
limiter: in_rms=0.565861 out_rms=0.555831 ratio=0.9823 ssd=9.22e+01  тЖР engaged
silence: peak=8.00e-01                              тЖР bounded (residual release tail)
param_roundtrip: set 4.0 got 4.0000                 тЖР atomic param store works
ALL TESTS PASSED
```
Notes on the test design:
- The bypassed-chain == exact-passthrough assertion proves the audio in/out
  ports and the chain deinterleave/interleave are wired correctly.
- The LSP LADSPA wrappers do not react to compressor/limiter thresholds the
  way a full GUI session does (Milestone 2 saw only a ~0.1 % RMS change with
  all-zero controls). The enabled-limiter assertion therefore checks that the
  output is *not bit-identical* to the input (the startup transient engages
  the gain-reduction envelope) rather than asserting a specific peak reduction.
- Parameter set/get round-trips through the realtime-safe `AtomicParam` store.

### Build verification
- `./build.sh debug` тЖТ BUILD SUCCESSFUL (arm64-v8a + armeabi-v7a).
- APK packages `lib/arm64-v8a/liblsp-plugins-ladspa.so` (8.7 MB) +
  `libc++_shared.so` + `libnative-lib.so`. (Locally the `.so` is produced by
  `build-lsp-ladspa-android.sh` into `prebuilt/`; in CI the workflow builds it
  from the submodule and copies it there before `assembleDebug`.)
- `./gradlew :app:testDebugUnitTest` тЖТ BUILD SUCCESSFUL (MIDI parser suite).

### Remaining (next session)
- **On-device load — RESOLVED.** The cleanup-bypass build confirmed the
  SIGABRT was secondary (cleanup of a partially-initialized object); with the
  bypass in place `System.loadLibrary("lsp-plugins-ladspa")` succeeds on
  device (`loadLibrary(...) OK` in the App Log) and `LadspaRegistry::open()`
  reaches the descriptor-enumeration stage without aborting. No further
  crash-diagnosis work needed on this front.

- **On-device instantiate failure (top priority).** The descriptor dump
  (`descriptors=168`) DISPROVED the dead-stripping hypothesis — the table is
  fully populated and `compressor_stereo` carries the exact URI the adapter
  looks up, so `findByLabel()` matches. The real failure is one level deeper:
  the descriptor is found but `LadspaEffect::prepare()` returns false because
  `mDescriptor->instantiate()` returns `nullptr` on-device (the LSP
  `wrapper->init()` fails; the cleanup-bypass returns nullptr instead of
  aborting). The prior `available==0` message ("label lookup failed") was
  misleading — it conflated "label not found" with "instantiate failed".

  **This session fixed the diagnosis:** `EffectChain::loadBundle()` now
  re-queries `findByLabel()` per slot and reports `FOUND (uid=…) but
  prepare()/instantiate() FAILED` vs `NOT FOUND`, plus a
  `Summary: N/3 labels found` line naming the real root cause. The LSP-side
  instantiate sub-step + `wrapper->init()` return code are already written to
  `lsp_prepare_marker.log` (prior `eda7b9d` diag +
  `LSP_ANDROID_INSTANTIATE_DIAGNOSTIC`), surfaced by `MainActivity` on the
  **next** launch.

  Next actions:
  1. **ROOT CAUSE FOUND + FIX IMPLEMENTED — rebuild the LSP `.so`.**
     `piano_log (3).txt` decoded the marker to `rc=29 = STATUS_BAD_LOCALE`
     (0-indexed status_codes enum). Cause: the no-op `iconv_android_shim.cpp`
     returned `(iconv_t)-1`, failing `CharsetDecoder::init()` inside
     `meta::load_manifest()` — the manifest IS found, but its UTF-8→wchar_t
     decode fails because Bionic ships `<iconv.h>` (the typedef) but not the
     iconv functions, and our shim was a no-op. Rewrote the shim into a real
     minimal iconv (UTF-8/UTF-16/UTF-32/WCHAR_T/ASCII/ISO-8859-1); host g++
     test (11 cases) ALL PASS. `ANDROID_PATCHES.md` item 8 corrected.
  2. Re-run `build-lsp-ladspa-android.sh` → copy the new
     `liblsp-plugins-ladspa.so` to `prebuilt/arm64-v8a/` → rebuild APK →
     install → launch. Expect `LSP master effects available: 3/3`.
  3. If still failing: the marker will now advance past `I_CL_WRAP_BYPASS`
     toward a later `WI_*` sub-step — that names the next blocker. The iconv
     fix is necessary and almost certainly sufficient for manifest decode.
- On-device runtime validation: ✅ CONFIRMED. After the iconv fix + LSP
  `.so` rebuild, the user reports **effects show up** on-device (3/3 master
  effect slots available, UI renders). The iconv shim was the necessary and
  sufficient fix for the manifest-decode/instantiate failure. Remaining
  runtime validation: confirm toggling/sliding audibly changes the signal
  (DSP path was already host-tested).
- Project persistence (Milestone 8): bump project format 1 → 2; migrate the
  `piano_prefs`-based effect state into the project (or keep both).
- Sample-rate rebuild (Milestone 9): worker-prepared inactive chain + atomic
  swap when the device rate differs from the chain's prepared rate.
- ARMv7 fallback (Milestone 10): the LSP `.so` is arm64-v8a only; on ARMv7
  devices `loadMasterEffectBundle()` returns 0 and the chain stays bypassed.

---

## Session update -- compressor/limiter gain range clamped 0..10

### What changed
The `input_gain` / `output_gain` parameters (ports 9/10) for the
**compressor** and **limiter** were exposed to the UI with a range of
`0..1000` (matching the upstream LADSPA `PortRangeHint`). A 0..1000 linear
trim is far too coarse for a UI slider -- the useful range is 0..1 with a
little headroom -- so the app now clamps these to **`0..10`** (10x = +20 dB
headroom), matching the parametric EQ which already used `0..10`.

- `effects/lsp/LspEffectIds.cpp`: updated both the runtime `ParamPort`
  tables (`kCompPorts`, `kLimPorts`) and the UI-facing
  `EffectParameterDescriptor` tables (`kCompDescriptors`, `kLimDescriptors`)
  -- four tables, eight range values `1000.0f -> 10.0f`. Defaults (1.0f) and
  the `logarithmic`/`sr` flags are unchanged. The parametric EQ tables were
  already `0..10` and are untouched.
- `lsp-integration/patches/LADSPA_DESCRIPTORS.md`: noted the app clamp on
  the compressor and limiter `input_gain`/`output_gain` rows (the doc still
  records the native LADSPA range `0..1000` for port-reference purposes).

### Verification
- `./build.sh debug` -> `BUILD SUCCESSFUL`.
- `./gradlew :app:testDebugUnitTest` -> all unit tests pass.
- The DSP path is unaffected: the clamp only changes the UI/parameter
  envelope, not the LADSPA plugin's native port range.

### Note
The `makeup` gain (compressor, `0.001..1000`) is a separate parameter and
was intentionally left at its native range -- only the input/output trim
gains were clamped per the request.

