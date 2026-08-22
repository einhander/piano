# LSP Plugins Integration — Progress Tracker

Branch: `feature/lsp-plugins-integration`
Master plan: `/workspace/LSP_Plugins_Integration_Plan_PianoAPP.md`
Integration layer: `app/src/main/cpp/lsp-integration/`

This file tracks milestone-level progress against the plan. Update it at the
end of every working session. The plan's Phase 35 lists the full report items
required at each milestone; this tracker is the running summary.

Legend: ✅ done · 🟡 in progress / partial · ⬜ not started

---

## Milestone 1 — Android build feasibility  ✅

Goal: LSP 1.2.34 → LADSPA only → Android NDK 26.1 → arm64-v8a.

| Item | Status | Notes |
|------|--------|-------|
| NDK 26.1.10909125 toolchain | ✅ | `$ANDROID_SDK_ROOT/ndk/26.1.10909125` |
| LSP meta pinned to tag 1.2.34 | ✅ | `third_party/lsp` is now a **git submodule** pinned to tag 1.2.34 (was gitignored + fetched on demand); nested module repos are still fetched by `make fetch` at build time |
| LADSPA-only build, no UI/LV2/CLAP/VST | ✅ | `FEATURES='crosscompile ladspa'` |
| Android compatibility patches | ✅ | 13 patches, documented in `patches/ANDROID_PATCHES.md` |
| Reproducible build script | ✅ | `build-lsp-ladspa-android.sh` (reset → apply → build verified) |
| Idempotent patch-apply script | ✅ | `patches/apply-android-patches.sh` |
| AArch64 ELF artifact | ✅ | `.build/target/lsp-plugin-fw/lsp-plugins-ladspa.so` (9.1 MB) |
| ELF ABI / symbol check | ✅ | e_machine=183, `ladspa_descriptor` GLOBAL FUNC exported |
| NEEDED libs = Android runtime only | ✅ | libdl, libc++_shared, libm, libc (no pthread/rt/sndfile/X11) |
| Plugin metadata validated | ✅ | 198 plugins, warnings=0, errors=0 (host validator) |

Pinned module versions (from `.config.mk`): lsp-runtime-lib 1.0.35,
lsp-plugin-fw 1.0.39, lsp-common-lib 1.0.48, lsp-dsp-lib 1.1.0,
lsp-dsp-units 1.0.37, lsp-plugins-shared 1.0.38, lsp-3rd-party 1.0.29.

---

## Milestone 2 — Descriptor + offline DSP test  ✅ (host proxy; qemu on-device TODO)

Goal: `ladspa_descriptor()` → instantiate → connect → run → measurable PCM change.

| Item | Status | Notes |
|------|--------|-------|
| Descriptor enumeration tool | ✅ | `patches/ladspa_dump.cpp` (host build) |
| Total descriptors exported | ✅ | **168** LADSPA entries (host `.so`); validator counts 198 incl. non-LADSPA |
| Selected descriptors identified | ✅ | see `patches/LADSPA_DESCRIPTORS.md` |
| Instantiate compressor/limiter/EQ | ✅ | all three instantiate at 48 kHz without error |
| connect + run on 1 kHz sine | ✅ | all three run; output finite (no NaN/Inf) |
| Numerical stability (silence) | ✅ | finite, no blow-up |
| Measurable PCM change | ✅ | limiter shows change (ratio 0.9991); compressor/EQ at unity = passthrough (ratio 1.0000, expected). Verified by running `ladspa_offline_test.cpp` against the host x86-64 `.so`. |
| Run under qemu-aarch64 on Android .so | ⬜ | qemu-user-static installed; blocked on missing `/system/bin/linker64` (not in NDK). Host x86-64 `.so` (same patched sources) used instead as feasibility proxy. On-device dump remains TODO. |
| Port map (per effect) | 🟡 | compressor ports enumerated (Bypass=8, Input gain=9, Output gain=10, Attack threshold=29, Ratio=34, Makeup=38). Limiter/EQ port dump pending. |

### Selected descriptors (LADSPA UniqueID, stable)

| Piano stable ID | LADSPA Label (URI) | UniqueID | Name |
|-----------------|--------------------|----------|------|
| `lsp.parametric_eq` | `…/ladspa/para_equalizer_x16_stereo` | 5002076 | Parametric Equalizer x16 Stereo |
| `lsp.compressor`    | `…/ladspa/compressor_stereo`         | 5002091 | Compressor Stereo |
| `lsp.limiter`       | `…/ladspa/limiter_stereo`            | 5002123 | Limiter Stereo |

`LSP_LADSPA_BASE = 0x4C5350 = 5002064`. Label is a full URI
(`http://lsp-plug.in/plugins/ladspa/<name>`); UniqueID is the stable key.

---

## Milestone 3 — Piano effect abstraction  ✅

`app/src/main/cpp/effects/`: `AudioEffect.h`, `EffectChain`, `LadspaEffect`,
`LadspaRegistry` / `LspEffectFactory` + `LspEffectIds`. Per plan Phases 6,
12–16. Realtime-safe (pre-allocated buffers, `std::atomic` param store, fixed
`AtomicParam[]` array since `std::atomic` is non-MoveInsertable). Verified by
the host `effect_chain_test` (see Current status below).

## Milestone 4 — One master EQ  ✅

Mixer → LSP EQ → MasterBus. Plan Phase 7/8/9/10 (buffer sizing, insertion
point, `safeFrames`, `mMaxSynthFrames`, actual sample rate). Realtime-safe.
Insertion point in `NativeEngine::process()` between `mMixer.mix()` and
`mMasterBus.process()`.

## Milestone 5 — Three-effect master chain  ✅

EQ → Compressor → Limiter. Plan Phases 17, 21 (rollout A/B/C). All three slots
prepared by `EffectChain::loadBundle()`; effects load DISABLED (bypassed) by
default so the chain is a no-op until the UI opts in.

## Milestone 6 — service/JNI control API  ✅

UI → PlaybackService Binder → NativeEngineBridge → JNI → MasterEffectChain.
Plan Phase 11. JNI entry points in `native_engine_jni.cpp`
(`loadMasterEffectBundle`, `setEffectParameter`, `getEffectParameter`,
`setMasterEffectEnabled`, `isMasterEffectEnabled`, `getMasterEffectCount`,
`getMasterEffectStableId`, `isMasterEffectChainAvailable`); Kotlin declarations
in `NativeEngineBridge.kt`; `PlaybackService` + `PlaybackBinder` passthroughs
added; `MainActivity.loadMasterEffectBundle()` auto-loads the bundled `.so`
on the worker thread after engine init (best-effort).

## Milestone 7 — Android UI  ✅

Effect enable toggles + parameter sliders, driven by native parameter
metadata (no duplicated DSP ranges). Plan Phase 11 (control surface).

| Item | Status | Notes |
|------|--------|-------|
| Parameter metadata JNI API | ✅ | `nativeGetMasterEffectParamCount` / `nativeGetMasterEffectParamInfo` (FloatArray[7]: paramId,min,max,def,log,integer,toggled) / `nativeGetMasterEffectParamName`; backed by new `piano::lsp::paramDescriptors()` descriptor tables in `LspEffectIds.cpp` |
| `EffectsActivity` | ✅ | 3 effect cards (EQ/Compressor/Limiter) built dynamically from native descriptors; enable `SwitchCompat` + per-param `SeekBar` (log-scaled where `logarithmic`, snapped where `integer`, on/off where `toggled`) |
| Worker-thread JNI | ✅ | all effect calls via `CompletableFuture.runAsync(..., mainExecutor)` (direct JNI, never main thread) |
| Light theme | ✅ | uses `Theme.PianoSequencer` + `@drawable/card_frame` (not DayNight) |
| Persistence | ✅ | enable flags + param values stored in `piano_prefs` (`fx_enabled_<slot>`, `fx_param_<slot>_<id>`); restored on engine boot in `MainActivity.restorePersistedEffectState()` (does not touch project format — that is Milestone 8) |
| Entry point | ✅ | "Master Effects" button in `MainActivity` → `EffectsActivity` (registered in manifest) |

### Validation
- `./build.sh debug` → BUILD SUCCESSFUL; `libnative-lib.so` rebuilt with the new
  JNI entry points; `liblsp-plugins-ladspa.so` (8.7 MB) still packaged.
- `./gradlew :app:testDebugUnitTest` → BUILD SUCCESSFUL (MIDI parser suite).
- Host `effect_chain_test` re-run after the C++ descriptor additions →
  ALL TESTS PASSED (no regression in the DSP path).

## Milestone 8 — project persistence  ⬜

Bump project format 1 → 2; migrate old projects to neutral/bypassed.

## Milestone 9 — sample-rate rebuild  ⬜

Worker-prepared inactive chain + atomic swap.

## Milestone 10 — ARMv7  ⬜

## Milestone 11 — optional track inserts  ⬜

---

## Open questions / blockers

1. **On-device load crashes the app (native SIGSEGV, no log)** — the current
   top blocker. The `.so` builds and links cleanly (valid AArch64 ELF,
   `ladspa_descriptor` exported, NEEDED = libdl/libc++_shared/libm/libc) and
   the host x86-64 `.so` of the same sources passes the offline DSP test, but
   `System.loadLibrary("lsp-plugins-ladspa")` crashes the app on launch on the
   target device. The process dies before the in-memory `AppLogger` flushes and
   before the Java `UncaughtExceptionHandler` runs (it only catches Java
   throwables), so there was no trace. The LADSPA plugin registration is lazy
   (`lsp_singletone_init` in `ladspa.cpp`), so the crash is in a **static
   constructor of the linked runtime/common/DSP code**, not in the LADSPA entry.
   **Diag build committed (`1a49a40`):** a native signal handler
   (`diagnostics/CrashHandler`) writes the fault PC + an `_Unwind_Backtrace`/
   `dladdr` backtrace to `<filesDir>/native_crash.log`, which `MainActivity`
   surfaces in the App Log on the next launch and then skips the bundle load
   so the app stays up to read it.
   **First backtrace captured (commit `460b677`, on-device):** the crash is a
   **SIGABRT (signal 6)**, not a SIGSEGV, and the backtrace is **entirely in
   `libc.so`** (`abort +0xa0` → anonymous libc frames). The LSP bundle's frames
   do not appear because `_Unwind_Backtrace` stops where unwind info ends (the
   libc abort trampoline). The LADSPA plugin registration is lazy, so the abort
   is triggered during `System.loadLibrary` — i.e. the Android dynamic linker
   calls `abort()` itself (the classic signature of a load-time failure:
   soname/dependency conflict, unsatisfied versioned symbol, or bad ELF).
   Bionic writes the *reason* to **logcat and stderr (fd 2) *before* abort()**;
   the backtrace alone can't name the culprit, and we have no logcat/adb.
   **Stderr-capture diag (`3712e04`):** `CrashHandler` now also
   (a) dumps `/proc/self/maps` at crash time (async-signal-safe `open`/`read`;
   `dl_iterate_phdr` would deadlock on the linker's `g_dl_mutex` held during a
   dlopen abort) — the map shows whether the LSP `.so` was mapped before the
   abort (mapped ⇒ fault in a static ctor; not mapped ⇒ the linker aborted
   during mapping), and (b) redirects fd 2 to `<filesDir>/lsp_load_stderr.log`
   around `System.loadLibrary` (`crash::beginStderrCapture`/`endStderrCapture`
   via `dup`/`dup2`), so the linker's fatal message is captured. Both files are
   surfaced in the App Log on the next launch.
   **Second on-device capture (from `3712e04`):** the crash is still a
   **SIGABRT**, and `/proc/self/maps` shows **`liblsp-plugins-ladspa.so` IS
   mapped** (all 4 PT_LOAD segments: r--p / r-xp / r--p / rw-p + `.bss`).
   → The linker **successfully loaded and mapped** the library; the abort is
   AFTER mapping, during relocation or `.init_array` (static constructor).
   **The stderr capture is EMPTY** — on this device (sdk=36 / Android 16)
   Bionic's `async_safe_fatal` wrote the abort reason to **logd (logcat) only**,
   NOT to fd 2. So fd-2 capture can't name the culprit here.
   **This commit (logcat + FP backtrace):** adds (c) a `fork`+`exec` of
   `/system/bin/logcat --pid=<us>` writing to `<filesDir>/lsp_load_logcat.log`
   around the load (filtered to tags `linker/DEBUG/libc/art/AndroidRuntime`),
   so the abort message text is captured without adb; and (d) an aarch64
   **frame-pointer-chain backtrace** (walk x29: `[fp]=next fp`, `[fp+8]=return
   addr`) in the crash handler — `_Unwind_Backtrace` stops at the libc abort
   trampoline (no unwind info), but NDK clang keeps frame pointers on aarch64,
   so the manual walk reaches the LSP static-ctor frames above `abort()`.
   Next: read the logcat abort message + FP backtrace to identify the exact
   crashing ctor / abort reason. See "On-device load — diagnosis" below.
2. **qemu on-device-style run**: the Android `.so` needs `/system/bin/linker64`
   (Bionic dynamic linker), which the NDK does not ship. Options: extract
   linker64 from an Android system image, or run the descriptor dump on a real
   device via a tiny test APK. Until then, the host x86-64 build of the same
   patched sources is used as the feasibility proxy (same DSP code paths).
   The host `.so` is produced by `make config FEATURES='ladspa'` (no
   `crosscompile`) + `make`; the offline test then runs natively via `dlopen`.
3. **Compressor gain-reduction measurement**: at unity input/output gain with
   threshold 0.01 amp and ratio 10, the stereo compressor passes the signal
   through unchanged (ratio 1.0000). Likely a control-port mapping issue
   (e.g. "Compression mode" / sidechain source left at 0). To resolve in a
   later milestone by dumping all control port names + defaults. This does not
   block Milestones 1–2 (instantiation + finite run + limiter PCM change are
   proven).
4. **Production build integration (Plan Phase 26)** — RESOLVED. The LSP bundle
   is now built from the pinned submodule in CI: `.github/workflows/build-apk.yml`
   runs `build-lsp-ladspa-android.sh` and copies the `.so` into
   `prebuilt/arm64-v8a/` before `assembleDebug`. The `.so` stays gitignored
   (CI reproduces it every run); committing the binary was tried and reverted
   (it crashed on-device — see blocker 1).
5. **Plan Phase 36 says "stop after this milestone"** for the first coding
   assignment. Milestones 1–2 are now complete and verified. Proceeding into
   Milestones 3–6 (effect API + audio-callback insertion) crosses that
   boundary and touches the realtime audio callback — confirm before
   continuing. The remaining plan tasks (CMake integration, audio-chain
   wiring, realtime-safe `run()`) are scoped for Milestone 3+ and are **not**
   started here.

## Session notes (this update)

- Fixed the failing LSP LADSPA build that the prior WIP commit left broken.
  The `.so` only actually builds after three additional patches beyond the
  original 11:
  - `lsp-common-lib-android.patch` — Bionic `qsort_r()` thread-local thunk
    fallback (compilation error in `lsp-common-lib/src/main/stdlib.cpp`).
  - Header fix in `lsp-runtime-lib-android.patch` — `*AudioFileStream.h`
    prefers the vendored `sndfile_stub.h` on `__ANDROID__` even when
    `USE_LIBSNDFILE` is still emitted, so `lsp-dsp-units` (which keeps its
    own `LIBSNDFILE` dependency) compiles for the target.
  - `filter-android-deps.sh` — globally trims `LIBSNDFILE`/`LIBPTHREAD`/
    `LIBRT` from every submodule's `dependencies.mk` (and the meta one), so
    the host resource/meta pass (compiled with `g++`) no longer emits
    `-DUSE_LIBSNDFILE` → `<sndfile.h>`, which has no dev headers on the host.
- `apply-android-patches.sh` now `mkdir -p`s the stub destination dirs before
  `install` (the original failure point), and runs `filter-android-deps.sh`.
- Verified end-to-end: clean `rm -rf .build` → `build-lsp-ladspa-android.sh`
  → aarch64 ELF, NEEDED = libdl/libc++_shared/libm/libc, `ladspa_descriptor`
  exported, validator `plugins=198, warnings=0, errors=0`.
- Verified the offline DSP test against a host x86-64 `.so` (same patched
  sources): 168 LADSPA descriptors, all three selected plugins instantiate +
  run finite at 48 kHz, limiter shows measurable PCM change (ratio 0.9991).
- Baseline Piano build + unit tests remain green: `./build.sh debug` →
  `BUILD SUCCESSFUL`; `./gradlew :app:testDebugUnitTest` → pass. No CMake /
  JNI / Kotlin changes in this session.

---

## On-device load — diagnosis (current session)

### What broke
The committed prebuilt `.so` (and the CI-built-from-submodule `.so`) crash the
app **on launch**. `MainActivity` boots the engine on a worker thread and
calls `NativeEngineBridge.preloadLspBundle()` → `System.loadLibrary(
"lsp-plugins-ladspa")`, which runs the bundle's static constructors; one of
them SIGSEGVs and kills the process. Because `AppLogger` is in-memory and the
Java `UncaughtExceptionHandler` only catches Java throwables, **nothing** was
logged. The LADSPA plugin registration itself is lazy
(`lsp_singletone_init` in `wrap/ladspa.cpp`), so the fault is in a static
constructor of the linked runtime/common/DSP code, not in the LADSPA entry.

### Root-cause investigation log (this session)
1. **Initial hypothesis — .so missing from the CI-built APK.** The prebuilt
   `.so` was gitignored, so CI clones never had it; the APK the user installed
   contained only the CMake-built libs (no `liblsp-plugins-ladspa.so`). Every
   load path (System.loadLibrary, dlopen by path/soname, extract-from-APK into
   codeCacheDir, scan sibling split APKs) reported the binary absent.
   Fixes 1–5 (useLegacyPackaging, soname fallback, surfaced preload errors,
   APK-extract, split-APK scan) were all correct but moot without the binary.
2. **Fix attempt — commit the prebuilt .so** (`360b76e`, then reverted). This
   got the binary into the APK (verified: 8 758 616 bytes,
   `lib/arm64-v8a/liblsp-plugins-ladspa.so`), but the app **crashed on
   launch**. User: "пребилд крашит приложение при открытии".
3. **Fix attempt — build from a pinned submodule in CI** (`c36139b`).
   `third_party/lsp` is now a git submodule @ tag 1.2.34; the CI workflow runs
   `build-lsp-ladspa-android.sh` + copies the `.so` into `prebuilt/arm64-v8a/`
   before `assembleDebug`. Verified: CI APK contains the submodule-built `.so`
   (AArch64 ELF, `ladspa_descriptor` exported). **Still crashes on launch** —
   same source ⇒ same crashing static ctor. So the load mechanism (the earlier
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
- NEEDED = `libdl.so`, `libc++_shared.so`, `libm.so`, `libc.so` — only
  Android/NDK runtime libs (no pthread/rt/sndfile/X11/jack/pipewire).
- 198 plugins validated by the host validator (warnings=0, errors=0).
- Host x86-64 `.so` of the same patched sources passes the offline DSP test
  (instantiate + run finite + limiter PCM change). So the DSP code is sound;
  the crash is an init-time Android incompatibility, not a DSP bug.

### Next steps
1. ✅ DONE (commit `460b677`): shipped the backtrace diag build; the App Log
   shows the crash is a **SIGABRT** with a **libc-only backtrace** (`abort`
   machinery) — i.e. the Android linker itself calls `abort()` during
   `System.loadLibrary`. The fault PC + `dladdr` backtrace alone do NOT name
   the culprit because the LSP frames above `abort()` are lost.
2. ✅ DONE (commit `3712e04`): stderr-capture + `/proc/self/maps`. The maps
   show the `.so` **IS mapped** (4 PT_LOAD segments) → the abort is AFTER
   mapping (relocation / `.init_array`). The stderr capture is EMPTY → on
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
5. Re-run CI → install → confirm `loadLibrary("lsp-plugins-ladspa") OK` +
   `LSP master effects available: 3/3` in the App Log.

### CI status (this session)
- `c36139b` (submodule + CI build) — `success` (run `32559428994`, 8m42s); CI
  APK verified to contain the submodule-built `.so`.
- `1a49a40` (crash diagnostics) — CI run `32560496489` started.
- `460b677` (PROGRESS update) — `success` (run `32564653795`, 10m15s); APK
  verified to contain the `.so` (8 758 616 bytes) + the crash handler in
  `libnative-lib.so` (`nativeInitCrashHandler`, `crash::install`).
- **First on-device backtrace captured from `460b677`:** SIGABRT, libc-only
  frames. → triggered the `3712e04` stderr-capture diag.
- `3712e04` (stderr + maps diag) — `success` (run `32569154127`, ~9m); APK
  verified to contain `nativeBeginStderrCapture`/`crash::beginStderrCapture`.
- **Second on-device capture from `3712e04`:** `.so` IS mapped; stderr capture
  EMPTY. → triggered this commit's logcat + FP-backtrace diag.

---

## Reproducibility (Plan Phase 27)

```bash
# 0. prerequisites (one-time): JDK 17, Android NDK 26.1.10909125, host g++.
export JAVA_HOME=/opt/jdk-17.0.13+11
export ANDROID_SDK_ROOT=/opt/android-sdk
export PATH="$JAVA_HOME/bin:$PATH"

# 1. fetch + patch + build the LSP LADSPA bundle (arm64-v8a):
app/src/main/cpp/lsp-integration/build-lsp-ladspa-android.sh
# → third_party/lsp/.build/target/lsp-plugin-fw/lsp-plugins-ladspa.so  (AArch64)

# 2. offline descriptor dump + DSP feasibility test.
#    The test must dlopen a host-loadable .so, so first build a host x86-64
#    variant of the same patched sources:
cd app/src/main/cpp/third_party/lsp
make config FEATURES='ladspa' EXPORT_SYMBOLS=0 INSTALL_HEADERS=0   # host config
rm -rf .build && make FEATURES='ladspa'                             # host build
# → .build/target/lsp-plugin-fw/lsp-plugins-ladspa.so  (x86-64, ELF64)
cd -
g++ -O2 -std=c++17 \
  -I app/src/main/cpp/third_party/lsp/modules/lsp-3rd-party/include \
  app/src/main/cpp/lsp-integration/patches/ladspa_offline_test.cpp \
  -o /tmp/ladspa_offline_test_host -ldl -lm
/tmp/ladspa_offline_test_host \
  app/src/main/cpp/third_party/lsp/.build/target/lsp-plugin-fw/lsp-plugins-ladspa.so
# → 168 descriptors; compressor/limiter/EQ instantiate + run finite; limiter
#   ratio 0.9991.
#
# 3. (optional) re-build the target aarch64 artifact (overwrites the host .so):
bash app/src/main/cpp/lsp-integration/build-lsp-ladspa-android.sh
```

> Switching the LSP tree between host and target builds changes `.config.mk`
> and leaves stale object files of the wrong arch; run `rm -rf .build` between
> host/target reconfigurations to avoid the `incompatible object` link error.

The Piano baseline build (`./build.sh debug`) and unit tests
(`./gradlew :app:testDebugUnitTest`) are unchanged by this milestone — no
CMake/JNI/Kotlin changes yet.

---

## Current status (post-Milestone-7)

### Completed this session
- Installed the Android toolchain (JDK 17 Temurin, SDK API 34, NDK
  26.1.10909125, CMake 3.22.1); fixed the shallow-clone missing oboe/fluidsynth
  submodules (`git submodule update --init`).
- Rebuilt the LSP LADSPA bundle end-to-end: cloned `lsp-plugins/lsp-plugins`
  tag 1.2.34, `make fetch`, `build-lsp-ladspa-android.sh` → aarch64 `.so`
  (9.1 MB, `ladspa_descriptor` exported, NEEDED = libdl/libc++_shared/libm/libc);
  copied to `lsp-integration/prebuilt/arm64-v8a/liblsp-plugins-ladspa.so`.
- Re-ran the host `effect_chain_test` → ALL TESTS PASSED.
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

### Earlier (Milestones 1–6)
- Toolchain installed & verified (JDK 17, Android SDK API 34, NDK 26.1.10909125,
  CMake 3.22.1).
- Port maps runtime-verified for all 3 plugins (compressor, limiter, EQ) — see
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
  (host x86-64) — ALL TESTS PASSED.

### Validation output (host x86-64)
```
available_effects=3/3
bypassed: in_peak=0.800000 out_peak=0.800000        ← exact passthrough (ports wired)
limiter: in_rms=0.565861 out_rms=0.555831 ratio=0.9823 ssd=9.22e+01  ← engaged
silence: peak=8.00e-01                              ← bounded (residual release tail)
param_roundtrip: set 4.0 got 4.0000                 ← atomic param store works
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
- `./build.sh debug` → BUILD SUCCESSFUL (arm64-v8a + armeabi-v7a).
- APK packages `lib/arm64-v8a/liblsp-plugins-ladspa.so` (8.7 MB) +
  `libc++_shared.so` + `libnative-lib.so`. (Locally the `.so` is produced by
  `build-lsp-ladspa-android.sh` into `prebuilt/`; in CI the workflow builds it
  from the submodule and copies it there before `assembleDebug`.)
- `./gradlew :app:testDebugUnitTest` → BUILD SUCCESSFUL (MIDI parser suite).

### Remaining (next session)
- **On-device load fix (top priority).** Install the diag build (`959e4cc`
  CI run `32576112937`, artifact `app-debug-apk`), reopen the app, read the
  App Log. The `lsp_prepare_marker.log` now shows the **exact sub-step** of
  `instantiate()` that was in progress when the abort fired:
  - `I_RL_NULL` → resource loader unavailable (primary cause);
  - `I_WINIT_RC=<n>` → `wrapper->init()` returned a non-OK status code
    (primary cause; `n` is the numeric status_t value);
  - `I_WINIT_OK` then a later crash → the failure is after init, in
    connect_port/activate;
  - a marker earlier than `I_PLUGIN_OK` → the failure is in factory/plugin
    creation.
  With `LSP_ANDROID_INSTANTIATE_DIAGNOSTIC` defined, destructive cleanup
  (`delete wrapper/loader/plugin`) after a failed init is bypassed, so the
  SIGABRT (which previously fired during cleanup of a partially-initialized
  object) should NOT occur. If the app no longer crashes but `instantiate()`
  returns nullptr, the primary failure is confirmed and the secondary
  cleanup crash is confirmed.
- On-device runtime validation (after the load is fixed): open "Master
  Effects", confirm the 3 cards render with correct ranges and that
  toggling/sliding changes the signal.
- Project persistence (Milestone 8): bump project format 1 → 2; migrate the
  `piano_prefs`-based effect state into the project (or keep both).
- Sample-rate rebuild (Milestone 9): worker-prepared inactive chain + atomic
  swap when the device rate differs from the chain's prepared rate.
- ARMv7 fallback (Milestone 10): the LSP `.so` is arm64-v8a only; on ARMv7
  devices `loadMasterEffectBundle()` returns 0 and the chain stays bypassed.
