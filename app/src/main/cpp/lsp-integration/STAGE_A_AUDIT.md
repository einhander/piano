# Stage A: LSP source, LADSPA and Artistic Delay audit

## Pin and reproducibility

Meta repository tag `1.2.34`, commit `92e81191771d23c87f952a2ad21d2a8b8cd1cf45`.
`modules.mk` pins these modules; `make fetch` produced:

| Module | Version | Commit |
|---|---:|---|
| `lsp-plugins-art-delay` | 1.0.35 | `87cc41cb3ed77f32aad81fe1ff27a8e24464b8f1` |
| `lsp-plugins-chorus` | 1.0.16 | `eae39ba96e08f33d9c95a031c0a5f10dcb1a45c7` |
| `lsp-plugins-clipper` | 1.0.18 | `735805c57cf45f320d42dfea622ca82b4a4b0826` |

Existing master EQ/compressor/limiter mappings are preserved unchanged in
`patches/LADSPA_DESCRIPTORS.md`.

## Compiled descriptor audit

Host binary `.build-stage-a-host/target/lsp-plugin-fw/lsp-plugins-ladspa.so`
was built from pinned sources. `ladspa_dump.cpp` reports `TOTAL=168` and
confirms:

| Effect | Label | UID | Port count |
|---|---|---:|---:|
| Chorus Stereo | `chorus_stereo` | 5002315 | 182 |
| Artistic Delay Stereo | `art_delay_stereo` | 5002171 | 741 |
| Clipper Stereo | `clipper_stereo` | 5002285 | 58 |

Audio ports are `0` input L, `1` input R, `2` output L, `3` output R. Exact
compiled relevant control ranges/defaults are in `patches/LADSPA_DESCRIPTORS.md`.
ArtDelay has 16 delay processors and repeated line metadata (43-port stride).

Defaults decoder uses masked `LADSPA_HINT_DEFAULT_*` switch, correct arithmetic
and geometric interpolation (LOW weights 0.75/0.25; HIGH 0.25/0.75), sample-rate
scaling once, then integer/toggled normalization. `DEFAULT_NONE` is reported
unspecified and initializes as zero. Shared decoder has focused test
`patches/ladspa_hint_defaults_test.cpp`.

## Build outcomes / tool availability

SDK/NDK/JDK already present under authorized `~/tools`:
`~/tools/android-sdk`, NDK `26.1.10909125`, JDK `~/tools/jdk-17.0.14+7`.
Host `/usr/bin/g++` and make available; `pkg-config` absent. No system packages
installed. Initial host build failed at missing `sndfile.h`; upstream-generated
build config had picked up `LIBSNDFILE`. After applying existing Android
headless dependency filter (drops unsupported file-I/O dependency define; does
not shim or alter DSP), a host build succeeded. No host sndfile shim used.

Host build isolated with upstream-supported overrides, preserving prior
architecture build:

```sh
make BUILDDIR="$PWD/.build-stage-a-host" CONFIG="$PWD/.config-stage-a-host.mk" \
  config FEATURES='ladspa' EXPORT_SYMBOLS=0 INSTALL_HEADERS=0
make BUILDDIR="$PWD/.build-stage-a-host" CONFIG="$PWD/.config-stage-a-host.mk" \
  FEATURES='ladspa' EXPORT_SYMBOLS=0 INSTALL_HEADERS=0
```

For host compile, Android instantiate diagnostic patch was temporarily reversed
and reapplied afterward; its inline non-Android marker stubs collide in one host
translation unit. Android portability patches (including iconv) remained
applied. Host bundle is x86-64 and is a same-source descriptor/DSP proxy, not an
Android ABI binary.

First Android build hit mixed-arch stale `.build` objects. Preserved old ignored
tree (did not delete) by moving it to `.build-stage-a-preserved-20261028` after
confirming destination absent. Then reran:

```sh
ANDROID_SDK_ROOT="$HOME/tools/android-sdk" \
JAVA_HOME="$HOME/tools/jdk-17.0.14+7" \
PATH="$HOME/tools/jdk-17.0.14+7/bin:$PATH" \
bash app/src/main/cpp/lsp-integration/build-lsp-ladspa-android.sh
```

Final Android build exit 0. Artifact `.build/target/lsp-plugin-fw/lsp-plugins-ladspa.so`:
AArch64 ELF64, e_machine 183, Android API 26; exports `ladspa_descriptor`;
NEEDED `libdl.so`, `libc++_shared.so`, `libm.so`, `libc.so`. Validator:
`plugins=198, warnings=0, errors=0`. The 198 validator count is metadata total;
host LADSPA enumeration is 168.

The Android build script enables `LSP_ANDROID_INSTANTIATE_DIAGNOSTIC=1` and the
Android diagnostic patch remains active. Artifact is diagnostic, not production:
markers write to app files and failed init bypasses cleanup. Do not package as
production without a separate diagnostic-off rebuild. Build printed non-fatal
missing-`pkg-config` warnings.

## Artistic Delay callback-safety gate: reject LADSPA path

Pinned `art_delay.cpp` captures `wrapper->executor()` during initialization and
calls `sync_delay()` at start of `process()`. `sync_delay()` calls
`pExecutor->submit(da)` on resize, disable/cleanup and garbage cleanup;
`DelayAllocator::run()` allocates/initializes dynamic delay lines and deletes
old ones. LADSPA `executor()` lazily creates `ipc::NativeExecutor`, while
wrapper `run()` calls plugin processing synchronously on its caller thread.
This delegates memory work asynchronously but does not prove submit is
nonblocking, queue capacity/failure behavior or completion safety. No callback
real-time guarantee found. **Do not use LSP Artistic Delay in Oboe callback.**
Stage D should use bounded preallocated realtime fallback unless executor and
first-use/reconfigure safety are rigorously proven.

## Focused commands

- `make config FEATURES='ladspa' EXPORT_SYMBOLS=0 INSTALL_HEADERS=0`: exit 0.
- `make fetch`: initial run timed out at 120s; continuation fetched required
  modules (and more). All named exact-tag module checkouts present.
- Initial `make FEATURES='ladspa'`: exit 2, missing `sndfile.h`.
- Isolated host build above: exit 0; actual compiled dumper reports 168 entries
  and all three descriptors with ports/ranges/defaults.
- Existing-plugin offline smoke test (`patches/ladspa_offline_test.cpp`): exit
  0; compressor, limiter and EQ instantiated and emitted finite buffers;
  limiter ratio 0.9991. Smoke check only, not full regression assertion (test
  controls yield compressor passthrough).
- Android build command above: exit 0; artifact and validator verified.
- `g++ -std=c++17 -Wall -Wextra ... ladspa_hint_defaults_test.cpp` + run: exit 0.
- `g++ -O2 -std=c++17 -Wall -Wextra ... ladspa_dump.cpp`: exit 0.
- `git diff --check`: passed.

Host DSP impulse/sine behavior for the new plugins and Android runtime/device
loading remain untested. Stage A establishes metadata/build only; no runtime
parameter binding approval.
