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
| LSP meta pinned to tag 1.2.34 | ✅ | vendored under `third_party/lsp` (gitignored, fetched on demand) |
| LADSPA-only build, no UI/LV2/CLAP/VST | ✅ | `FEATURES='crosscompile ladspa'` |
| Android compatibility patches | ✅ | 11 patches, documented in `patches/ANDROID_PATCHES.md` |
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

## Milestone 2 — Descriptor + offline DSP test  🟡

Goal: `ladspa_descriptor()` → instantiate → connect → run → measurable PCM change.

| Item | Status | Notes |
|------|--------|-------|
| Descriptor enumeration tool | ✅ | `patches/ladspa_dump.cpp` (host build) |
| Total descriptors exported | ✅ | **168** LADSPA entries (host `.so`); validator counts 198 incl. non-LADSPA |
| Selected descriptors identified | ✅ | see `patches/LADSPA_DESCRIPTORS.md` |
| Instantiate compressor/limiter/EQ | ✅ | all three instantiate at 48 kHz without error |
| connect + run on 1 kHz sine | ✅ | all three run; output finite (no NaN/Inf) |
| Numerical stability (silence) | ✅ | finite, no blow-up |
| Measurable PCM change | 🟡 | limiter shows change (ratio 0.9991); compressor/EQ at unity = passthrough (expected). Compressor threshold/ratio mapping needs verification to show gain reduction. |
| Run under qemu-aarch64 on Android .so | ⬜ | qemu-user-static installed; blocked on missing `/system/bin/linker64` (not in NDK). Host x86-64 `.so` used instead as feasibility proxy. On-device dump remains TODO. |
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

## Milestone 3 — Piano effect abstraction  ⬜

`app/src/main/cpp/effects/`: `AudioEffect.h`, `EffectChain`, `LadspaEffect`,
`LadspaRegistry` / `LspEffectFactory`. Per plan Phases 6, 12–16. No UI yet.

## Milestone 4 — One master EQ  ⬜

Mixer → LSP EQ → MasterBus. Plan Phase 7/8/9/10 (buffer sizing, insertion point,
`safeFrames`, `mMaxSynthFrames`, actual sample rate). Realtime-safe.

## Milestone 5 — Three-effect master chain  ⬜

EQ → Compressor → Limiter. Plan Phases 17, 21 (rollout A/B/C).

## Milestone 6 — service/JNI control API  ⬜

UI → PlaybackService Binder → NativeEngineBridge → JNI → MasterEffectChain.
Plan Phase 11. NOT direct NativeEngineBridge calls.

## Milestone 7 — Android UI  ⬜

## Milestone 8 — project persistence  ⬜

Bump project format 1 → 2; migrate old projects to neutral/bypassed.

## Milestone 9 — sample-rate rebuild  ⬜

Worker-prepared inactive chain + atomic swap.

## Milestone 10 — ARMv7  ⬜

## Milestone 11 — optional track inserts  ⬜

---

## Open questions / blockers

1. **qemu on-device-style run**: the Android `.so` needs `/system/bin/linker64`
   (Bionic dynamic linker), which the NDK does not ship. Options: extract
   linker64 from an Android system image, or run the descriptor dump on a real
   device via a tiny test APK. Until then, the host x86-64 build of the same
   patched sources is used as the feasibility proxy (same DSP code paths).
2. **Compressor gain-reduction measurement**: at unity input/output gain with
   threshold 0.01 amp and ratio 10, the stereo compressor still passes the
   signal through unchanged (ratio 1.0000). Likely a control-port mapping
   issue (e.g. "Compression mode" / sidechain source left at 0). To resolve in
   Milestone 2 finish-up by dumping all control port names + defaults.
3. **Production build integration (Plan Phase 26)**: the LSP build is currently
   a standalone script. Folding it into `./build.sh` / CMake (Option A/B/C) is
   Milestone-4+ work; for now the integration layer is committed and the LSP
   source tree is gitignored + fetched on demand.
4. **Plan Phase 36 says "stop after this milestone"** for the first coding
   assignment. Milestones 1–2 are effectively Phase 1. Proceeding into
   Milestones 3–6 (effect API + audio-callback insertion) crosses that boundary
   and touches the realtime audio callback — confirm before continuing.

---

## Reproducibility (Plan Phase 27)

```bash
# 1. fetch + patch + build the LSP LADSPA bundle (arm64-v8a):
app/src/main/cpp/lsp-integration/build-lsp-ladspa-android.sh
# → third_party/lsp/.build/target/lsp-plugin-fw/lsp-plugins-ladspa.so

# 2. offline descriptor dump + DSP feasibility test (host x86-64 proxy):
g++ -O2 -std=c++17 \
  -I app/src/main/cpp/third_party/lsp/modules/lsp-3rd-party/include \
  app/src/main/cpp/lsp-integration/patches/ladspa_offline_test.cpp \
  -o /tmp/ladspa_offline_test_host -ldl -lm
/tmp/ladspa_offline_test_host \
  app/src/main/cpp/third_party/lsp/.build/target/lsp-plugin-fw/lsp-plugins-ladspa.so
```

The Piano baseline build (`./build.sh debug`) and unit tests are unchanged by
this milestone — no CMake/JNI/Kotlin changes yet.
