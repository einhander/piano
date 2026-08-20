# Android (NDK) compatibility patches for LSP Plugins 1.2.34

This document records every patch applied to the vendored LSP source tree so
that the **headless LADSPA** build cross-compiles for Android `arm64-v8a`
with NDK 26.1.10909125.

All patches target code paths that are **not** exercised by LADSPA realtime
DSP processing (IPC, process spawning, shared memory, robust mutexes, audio
file I/O). They disable or stub desktop/POSIX functionality that Bionic libc
does not provide, or that is unnecessary for the in-app master-effect chain.

## Reproducibility

The vendored tree is the LSP meta-repository at tag `1.2.34`. Its submodules
are fetched by the upstream `make` machinery and are **not** tracked by the
meta-repository git, so the patches are stored as files here and applied by a
script before the build:

```
third_party/lsp/patches/
├── lsp-runtime-lib-android.patch   # modifications to lsp-runtime-lib
├── lsp-plugin-fw-android.patch     # modifications to lsp-plugin-fw
├── lsp-common-lib-android.patch    # modifications to lsp-common-lib (qsort_r)
├── android_posix_shim.cpp          # NEW: shm_open/shm_unlink stubs
├── iconv_android_shim.cpp          # NEW: iconv_open/iconv/iconv_close stubs
├── sndfile_stub.h                  # NEW: libsndfile API stub header
├── sndfile_stub.cpp                # NEW: libsndfile API stub implementation
├── apply-android-patches.sh        # applies the above to the fetched tree
└── filter-android-deps.sh          # global dep-list filter (libsndfile/libpthread/librt)
```

`apply-android-patches.sh` is idempotent and is intended to be run after
`make config` / `make fetch` and before `make`.

## Pinned upstream versions (recorded from `.config.mk`)

| Module                  | Version |
|-------------------------|---------|
| lsp (meta)              | 1.2.34  |
| lsp-runtime-lib         | 1.0.35  |
| lsp-plugin-fw           | 1.0.39  |
| lsp-common-lib          | 1.0.48  |
| lsp-dsp-lib             | 1.1.0   |
| lsp-dsp-units           | 1.0.37  |
| lsp-lltl-lib            | 1.0.32  |
| lsp-plugins-shared      | 1.0.38  |
| lsp-3rd-party           | 1.0.29  |
| lsp-plugins-compressor  | 1.0.39  |
| lsp-plugins-limiter     | 1.0.36  |
| lsp-plugins-para-equalizer | (see .config.mk) |

## Patch list

### 1. POSIX shared memory (`shm_open` / `shm_unlink`)
- **Module:** lsp-runtime-lib (`src/main/ipc/SharedMem.cpp`, new
  `src/main/ipc/android_posix_shim.cpp`)
- **Reason:** Bionic libc does not provide POSIX shared memory. The LADSPA DSP
  path never uses cross-process shared memory.
- **Fix:** declare `shm_open`/`shm_unlink` and link no-op stubs that always
  return -1 (failure).
- **Upstream DSP impact:** none.

### 2. Robust pthread mutexes
- **Module:** lsp-runtime-lib (`include/lsp-plug.in/ipc/SharedMutex.h`)
- **Reason:** Bionic does not implement `PTHREAD_MUTEX_ROBUST` /
  `pthread_mutex_consistent`.
- **Fix:** exclude `__ANDROID__` from `LSP_ROBUST_MUTEX_SUPPORTED`, so the
  existing `flock()`-based fallback is used.
- **Upstream DSP impact:** none.

### 3. Thread cancellation API
- **Module:** lsp-runtime-lib (`src/main/ipc/Thread.cpp`)
- **Reason:** Bionic has no thread cancellation API
  (`pthread_setcancelstate` / `pthread_setcanceltype`).
- **Fix:** guard the calls with `#ifndef __ANDROID__`. They are a cleanup
  safety measure only.
- **Upstream DSP impact:** none.

### 4. `secure_getenv`
- **Module:** lsp-runtime-lib (`src/main/runtime/system.cpp`)
- **Reason:** Bionic has no `secure_getenv`.
- **Fix:** fall back to `getenv` on `__ANDROID__`.
- **Upstream DSP impact:** none.

### 5. `getlogin_r`
- **Module:** lsp-runtime-lib (`src/main/runtime/system.cpp`)
- **Reason:** Bionic only provides `getlogin_r` from API 28; the build targets
  minSdk 26. The LADSPA DSP path never needs a user login.
- **Fix:** return `STATUS_NOT_FOUND` on Android. The `getpwuid_r` primary path
  remains (it is available at all API levels).
- **Upstream DSP impact:** none.

### 6. `posix_spawn`
- **Module:** lsp-runtime-lib (`src/main/runtime/system.cpp`,
  `src/main/ipc/Process.cpp`)
- **Reason:** NDK `<spawn.h>` defines `POSIX_SPAWN_SETSID` and declares the
  `posix_spawn*` functions, but marks them `__INTRODUCED_IN(28)`, so they are
  hidden at minSdk 26. Process spawning is irrelevant to LADSPA DSP.
- **Fix:** guard both `posix_spawn` call sites with
  `defined(...) && !defined(__ANDROID__)`; `Process::spawn_process` returns
  `STATUS_NOT_SUPPORTED` on Android.
- **Upstream DSP impact:** none.

### 7. `dlmopen` / `LM_ID_NEWLM`
- **Module:** lsp-runtime-lib (`src/main/ipc/Library.cpp`)
- **Reason:** Bionic has no `dlmopen` / separate link namespaces.
- **Fix:** on Android always `dlopen` into the current namespace.
- **Upstream DSP impact:** none.

### 8. iconv
- **Module:** lsp-runtime-lib (`include/lsp-plug.in/io/charset.h`, new
  `src/main/io/iconv_android_shim.cpp`)
- **Reason:** Bionic ships `<iconv.h>` with the `iconv_t` typedef but does not
  declare the `iconv`/`iconv_open`/`iconv_close` functions.
- **Fix:** declare them and link a no-op shim.
- **Upstream DSP impact:** none.

### 9. libsndfile stub
- **Module:** lsp-runtime-lib (`include/lsp-plug.in/mm/InAudioFileStream.h`,
  `include/lsp-plug.in/mm/OutAudioFileStream.h`, new
  `include/lsp-plug.in/3rdparty/sndfile_stub.h`,
  `src/main/mm/sndfile_stub.cpp`)
- **Reason:** libsndfile dev headers are not available for Android, and the
  LADSPA DSP path does not load audio files from disk.
- **Fix:** the two `*AudioFileStream.h` headers prefer the vendored stub on
  `__ANDROID__` even when `USE_LIBSNDFILE` is still emitted (the global dep
  filter, item 13, removes it for the build, but the guard is defensive).
  The stub header provides `SF_FORMAT_*`/`SF_ENDIAN_*`/`SF_ERR_*`/`SFM_*`
  constants and `sf_*` function declarations, backed by stub implementations
  that report failure / return 0.
- **Upstream DSP impact:** none.

### 10. Drop libsndfile from the build dependency graph
- **Modules:** lsp-runtime-lib (`dependencies.mk`), lsp-plugin-fw
  (`dependencies.mk`, `src/Makefile`)
- **Reason:** removes the `-DUSE_LIBSNDFILE` define and `-lsndfile` link flag.
- **Fix:** when `ANDROID_TARGET=1`, filter `LIBSNDFILE` out of
  `DEPENDENCIES` / `TEST_DEPENDENCIES` (runtime-lib) and out of
  `LINUX_DEPENDENCIES_LADSPA` (plugin-fw). The
  `include/lsp-plug.in/mm/*AudioFileStream.h` headers then pull in the stub.
- **Upstream DSP impact:** none.

### 11. Drop libpthread / librt from the LADSPA link set
- **Module:** lsp-plugin-fw (`dependencies.mk`)
- **Reason:** Bionic libc has `pthread` and `rt` built into libc; there is no
  `libpthread`/`librt` for the NDK linker to find, so `-lpthread`/`-lrt` fail
  at link time.
- **Fix:** when `ANDROID_TARGET=1`, filter `LIBPTHREAD`/`LIBRT` out of
  `LINUX_DEPENDENCIES_LADSPA`. The `LIBDL` dependency is retained
  (`-ldl` resolves fine in Bionic).
- **Upstream DSP impact:** none.

### 12. `qsort_r` fallback (lsp-common-lib)
- **Module:** lsp-common-lib (`src/main/stdlib.cpp`)
- **Reason:** Bionic's `qsort_r()` has the BSD argument order and is only
  available from API 28; the build targets minSdk 26 and the LSP common-lib
  uses the glibc-style signature (compar before arg). On `__ANDROID__` the
  GNU branch would call a hidden/absent `::qsort_r`.
- **Fix:** add an `__ANDROID__` branch that adapts via a thread-local thunk
  and falls back to the universally-available `qsort()`.
- **Upstream DSP impact:** none.

### 13. Global dependency-list filter (libsndfile / libpthread / librt)
- **Modules:** every submodule's `dependencies.mk` + the meta
  `dependencies.mk` (via `filter-android-deps.sh`)
- **Reason:** each LSP module adds `LIBSNDFILE`/`LIBPTHREAD`/`LIBRT` to its
  own `LINUX_DEPENDENCIES` (which Android pulls in as `PLATFORM=Linux`), and
  the host resource/meta pass (compiled with `g++`) also emits
  `-DUSE_LIBSNDFILE`, which makes `*AudioFileStream.h` include `<sndfile.h>`
  that is absent on the build host. Per-module patches (items 10/11) only
  cover lsp-runtime-lib and lsp-plugin-fw.
- **Fix:** `filter-android-deps.sh` appends an idempotent filter block to
  every `dependencies.mk`, trimming `LIBSNDFILE`/`LIBPTHREAD`/`LIBRT` from
  `DEPENDENCIES`, `TEST_DEPENDENCIES` and `ALL_DEPENDENCIES`. Applied
  unconditionally because both the host meta pass and the target cross-build
  compile the same patched sources.
- **Upstream DSP impact:** none.

## Resulting artifact

```
.build/target/lsp-plugin-fw/lsp-plugins-ladspa.so
```

- ELF64, `e_machine = 183` (aarch64), little-endian.
- Exports the single LADSPA entry point `ladspa_descriptor`.
- NEEDED shared libraries: `libdl.so`, `libc++_shared.so`, `libm.so`,
  `libc.so` — only Android/NDK runtime libraries. No `libpthread`/`librt`/
  `libsndfile`/`libX11`/`libcairo`/`libjack`/`libpipewire`.
- Plugin metadata validated by the host validator:
  `plugins=198, warnings=0, errors=0`.
