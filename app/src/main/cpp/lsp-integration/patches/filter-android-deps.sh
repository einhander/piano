#!/usr/bin/env bash
#
# Filters Android-incompatible desktop libraries out of every LSP submodule's
# dependency list so they never emit a -DUSE_<lib> define or a -l<lib> link
# flag for the headless LADSPA cross-build (and its host resource/meta pass).
#
# Affected libraries:
#   LIBSNDFILE  - not available on Bionic; the LADSPA DSP path never loads
#                 audio files from disk, and libsndfile dev headers are absent
#                 on the build host, so the host pass would fail on <sndfile.h>.
#   LIBPTHREAD  - built into Bionic libc; NDK has no libpthread to link.
#   LIBRT       - built into Bionic libc; NDK has no librt to link.
#
# The filter is applied regardless of host/target: both the host build (which
# generates resource/metadata artifacts) and the target cross-build must avoid
# these, because the same patched sources are compiled by both passes.
#
# Idempotent: safe to run multiple times (guarded by a marker comment).
#
set -u

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../../third_party/lsp" && pwd)"

MARKER="# --- LSP_ANDROID_DEP_FILTER ---"
FILTER_BLOCK='
# --- LSP_ANDROID_DEP_FILTER ---
# Android (Bionic) cross-build: libsndfile is not available (no dev headers on
# the host either, and the LADSPA DSP path never loads audio files), and
# libpthread/librt are built into Bionic libc so the NDK linker cannot find
# them. Drop all three from every dependency list so no -DUSE_<lib> define or
# -l<lib> flag is emitted. Applied unconditionally because both the host
# resource/meta pass and the target cross-build compile these same sources.
DEPENDENCIES        := $(filter-out LIBSNDFILE LIBPTHREAD LIBRT,$(DEPENDENCIES))
TEST_DEPENDENCIES   := $(filter-out LIBSNDFILE LIBPTHREAD LIBRT,$(TEST_DEPENDENCIES))
ALL_DEPENDENCIES    := $(filter-out LIBSNDFILE LIBPTHREAD LIBRT,$(ALL_DEPENDENCIES))
# --- end LSP_ANDROID_DEP_FILTER ---'

count=0

patch_dep_mk() {
    local f="$1"
    [ -f "$f" ] || return 0
    if grep -qF "$MARKER" "$f"; then
        return 0
    fi
    # Append the filter block at the end of the dependencies.mk file. Because
    # make evaluates the whole file, this runs after every DEPENDENCIES += in
    # the same file and trims the unwanted libs from all dependency variables.
    printf '%s\n' "$FILTER_BLOCK" >> "$f"
    count=$((count + 1))
    echo "   filtered: ${f#$ROOT/}"
}

echo ">> Filtering Android-incompatible desktop deps from LSP dependency lists"

# Meta-repository dependencies.mk (used by the plugin modules' src/Makefile via
# include $(ROOTDIR)/dependencies.mk).
patch_dep_mk "$ROOT/dependencies.mk"

# Every submodule's own dependencies.mk.
for f in "$ROOT"/modules/*/dependencies.mk; do
    patch_dep_mk "$f"
done

echo ">> Done ($count files updated)."
