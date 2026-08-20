#!/usr/bin/env bash
#
# Applies the Android NDK compatibility patches to the fetched LSP submodule
# tree. Idempotent: safe to run multiple times.
#
# Run AFTER `make config` / `make fetch` and BEFORE `make`.
#
# Usage (from anywhere):
#   app/src/main/cpp/lsp-integration/patches/apply-android-patches.sh
#
set -u

# LSP source tree (fetched upstream; not committed to this repo).
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../../third_party/lsp" && pwd)"
PATCH_DIR="$SCRIPT_DIR"
RT_LIB="$ROOT/modules/lsp-runtime-lib"
PLUG_FW="$ROOT/modules/lsp-plugin-fw"

if [ ! -d "$RT_LIB" ] || [ ! -d "$PLUG_FW" ]; then
    echo "apply-android-patches.sh: LSP submodules not found." >&2
    echo "Run 'make config'/'make fetch' first." >&2
    exit 1
fi

echo ">> Applying lsp-runtime-lib Android patch"
git -C "$RT_LIB" apply --whitespace=nowarn --reverse --check "$PATCH_DIR/lsp-runtime-lib-android.patch" >/dev/null 2>&1 \
    && echo "   (already applied)" \
    || git -C "$RT_LIB" apply --whitespace=nowarn "$PATCH_DIR/lsp-runtime-lib-android.patch"

echo ">> Applying lsp-plugin-fw Android patch"
git -C "$PLUG_FW" apply --whitespace=nowarn --reverse --check "$PATCH_DIR/lsp-plugin-fw-android.patch" >/dev/null 2>&1 \
    && echo "   (already applied)" \
    || git -C "$PLUG_FW" apply --whitespace=nowarn "$PATCH_DIR/lsp-plugin-fw-android.patch"

echo ">> Copying new stub sources into lsp-runtime-lib"
install -m 0644 "$PATCH_DIR/sndfile_stub.h"    "$RT_LIB/include/lsp-plug.in/3rdparty/sndfile_stub.h"
install -m 0644 "$PATCH_DIR/sndfile_stub.cpp"  "$RT_LIB/src/main/mm/sndfile_stub.cpp"
install -m 0644 "$PATCH_DIR/android_posix_shim.cpp" "$RT_LIB/src/main/ipc/android_posix_shim.cpp"
install -m 0644 "$PATCH_DIR/iconv_android_shim.cpp" "$RT_LIB/src/main/io/iconv_android_shim.cpp"

echo ">> Done."
