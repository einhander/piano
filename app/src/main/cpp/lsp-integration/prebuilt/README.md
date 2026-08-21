# LSP LADSPA prebuilt shared libraries

This directory holds the cross-compiled LSP LADSPA bundle consumed by the
Milestone-3+ CMake integration. The `.so` files are **not** committed (they are
~9 MB and fully reproducible) — see `.gitignore`.

## Regenerate

```bash
export JAVA_HOME=/opt/jdk-17.0.13+11
export ANDROID_SDK_ROOT=/opt/android-sdk
export PATH="$JAVA_HOME/bin:$PATH"

# Builds the arm64-v8a bundle and the build script does NOT copy it here; copy
# it after a successful build (note the lib*.so naming convention required by
# Android's package manager for extraction/page-mapping):
bash app/src/main/cpp/lsp-integration/build-lsp-ladspa-android.sh
cp app/src/main/cpp/third_party/lsp/.build/target/lsp-plugin-fw/lsp-plugins-ladspa.so \
   app/src/main/cpp/lsp-integration/prebuilt/arm64-v8a/liblsp-plugins-ladspa.so
```

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
