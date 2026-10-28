# LSP LADSPA prebuilt shared libraries

This directory holds the cross-compiled LSP LADSPA bundle consumed by the
Milestone-3+ CMake integration. The `.so` files are **not** committed (they are
~9 MB and fully reproducible) — see `.gitignore`.

## Reproducible production regeneration

```bash
export JAVA_HOME="$HOME/tools/jdk-17.0.14+7"
export ANDROID_SDK_ROOT="$HOME/tools/android-sdk"
export PATH="$JAVA_HOME/bin:$PATH"

# Fresh isolated outputs avoid stale ABI/diagnostic objects. Never clean or
# overwrite prior ignored build trees; choose a unique output/config per build.
LSP_BUILDDIR=.build-stage-a-production-20261028 \
LSP_CONFIG=.config-stage-a-production-20261028.mk \
LSP_ANDROID_INSTANTIATE_DIAGNOSTIC=0 \
bash app/src/main/cpp/lsp-integration/build-lsp-ladspa-android.sh

# Review script's ELF/dependency/export checks, then preserve an existing
# prebuilt before replacing it. Destination is ignored by git.
test ! -e app/src/main/cpp/lsp-integration/prebuilt/arm64-v8a/liblsp-plugins-ladspa.so
mkdir -p app/src/main/cpp/lsp-integration/prebuilt/arm64-v8a
cp app/src/main/cpp/third_party/lsp/.build-stage-a-production-20261028/target/lsp-plugin-fw/lsp-plugins-ladspa.so \
   app/src/main/cpp/lsp-integration/prebuilt/arm64-v8a/liblsp-plugins-ladspa.so
sha256sum app/src/main/cpp/lsp-integration/prebuilt/arm64-v8a/liblsp-plugins-ladspa.so
```

Diagnostic mode is opt-in and must never be packaged. CI explicitly sets it to
`0`. Production outputs are built from LSP meta tag `1.2.34` and module pins
recorded in `../STAGE_A_AUDIT.md`; regenerate using NDK `26.1.10909125`.

## License/source notices

The pinned Artistic Delay, Chorus and Clipper module roots each contain
`COPYING` and `COPYING.LESSER` license texts. Preserve these files and inspect
relevant source headers for terms when redistributing. Existing integration
attribution and upstream notices remain in `patches/ANDROID_PATCHES.md` and
`patches/LADSPA_DESCRIPTORS.md`. Retain those
notices and provide corresponding pinned source/rebuild instructions with
distribution. This note records files checked; it is not a legal-compliance
determination. The native bounded delay implementation is project-authored and
does not reuse Artistic Delay DSP.

## Expected layout

```
prebuilt/
└── arm64-v8a/
    └── liblsp-plugins-ladspa.so   # AArch64 ELF, exports ladspa_descriptor
```

The file is named `liblsp-plugins-ladspa.so` (with the `lib` prefix) so that
Android's package manager extracts / page-maps it from the APK into the app's
`nativeLibraryDir`. The runtime loads it by absolute path via `dlopen` (see
`MainActivity.loadMasterEffectBundle`), so no `System.loadLibrary` registration
is needed.

The `.so`'s NEEDED libraries are Android runtime only (`libdl`, `libc++_shared`,
`libm`, `libc`). `libc++_shared.so` must be packaged into the APK from the NDK
(`$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so`).
