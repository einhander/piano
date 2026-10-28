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
COMMON_LIB="$ROOT/modules/lsp-common-lib"

if [ ! -d "$RT_LIB" ] || [ ! -d "$PLUG_FW" ] || [ ! -d "$COMMON_LIB" ]; then
    echo "apply-android-patches.sh: LSP submodules not found." >&2
    echo "Run 'make config'/'make fetch' first." >&2
    exit 1
fi

apply_patch_once() {
    local module="$1" patch="$2" sentinel="$3" marker="$4"
    if git -C "$module" apply --whitespace=nowarn --reverse --check "$patch" >/dev/null 2>&1; then
        echo "   (already applied)"
    elif grep -Fq "$marker" "$module/$sentinel"; then
        # Other deterministic build filtering edits may make a full reverse
        # check fail even though this patch's source changes are present.
        echo "   (source marker already present)"
    else
        git -C "$module" apply --whitespace=nowarn "$patch"
    fi
}

echo ">> Applying lsp-common-lib Android patch"
apply_patch_once "$COMMON_LIB" "$PATCH_DIR/lsp-common-lib-android.patch" dependencies.mk 'ANDROID_TARGET'

echo ">> Applying lsp-runtime-lib Android patch"
apply_patch_once "$RT_LIB" "$PATCH_DIR/lsp-runtime-lib-android.patch" include/lsp-plug.in/io/charset.h 'Bionic ships <iconv.h>'

echo ">> Applying lsp-plugin-fw Android patch"
apply_patch_once "$PLUG_FW" "$PATCH_DIR/lsp-plugin-fw-android.patch" dependencies.mk 'Bionic libc also has pthread and rt built in'

DIAGNOSTIC="${LSP_ANDROID_INSTANTIATE_DIAGNOSTIC:-0}"
case "$DIAGNOSTIC" in
    1)
        echo ">> Enabling OPTIONAL LADSPA instantiate diagnostics (not for release)"
        git -C "$PLUG_FW" apply --whitespace=nowarn --reverse --check "$PATCH_DIR/lsp-plugin-fw-android-diag.patch" >/dev/null 2>&1 \
            && echo "   (already applied)" \
            || git -C "$PLUG_FW" apply --whitespace=nowarn "$PATCH_DIR/lsp-plugin-fw-android-diag.patch"
        ;;
    0)
        echo ">> Ensuring LADSPA instantiate diagnostics are disabled"
        if git -C "$PLUG_FW" apply --whitespace=nowarn --reverse --check "$PATCH_DIR/lsp-plugin-fw-android-diag.patch" >/dev/null 2>&1; then
            git -C "$PLUG_FW" apply --reverse --whitespace=nowarn "$PATCH_DIR/lsp-plugin-fw-android-diag.patch"
        else
            echo "   (not applied)"
        fi
        ;;
    *) echo "LSP_ANDROID_INSTANTIATE_DIAGNOSTIC must be 0 or 1" >&2; exit 2 ;;
esac

echo ">> Copying new stub sources into lsp-runtime-lib"
# The vendored stub header lives under include/lsp-plug.in/3rdparty/, which
# does not exist in the upstream tree; create the destination dirs first so
# `install` succeeds on a fresh fetch.
mkdir -p "$RT_LIB/include/lsp-plug.in/3rdparty" \
         "$RT_LIB/src/main/mm" \
         "$RT_LIB/src/main/ipc" \
         "$RT_LIB/src/main/io"
install -m 0644 "$PATCH_DIR/sndfile_stub.h"    "$RT_LIB/include/lsp-plug.in/3rdparty/sndfile_stub.h"
install -m 0644 "$PATCH_DIR/sndfile_stub.cpp"  "$RT_LIB/src/main/mm/sndfile_stub.cpp"
install -m 0644 "$PATCH_DIR/android_posix_shim.cpp" "$RT_LIB/src/main/ipc/android_posix_shim.cpp"
install -m 0644 "$PATCH_DIR/iconv_android_shim.cpp" "$RT_LIB/src/main/io/iconv_android_shim.cpp"

echo ">> Filtering Android-incompatible desktop deps (libsndfile/libpthread/librt)"
"$PATCH_DIR/filter-android-deps.sh"

echo ">> Done."
