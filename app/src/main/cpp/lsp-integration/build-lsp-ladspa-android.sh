#!/usr/bin/env bash
#
# Reproducible Android arm64-v8a build of the LSP 1.2.34 headless LADSPA bundle.
#
# Produces:
#   ${LSP_BUILDDIR:-.build}/target/lsp-plugin-fw/lsp-plugins-ladspa.so
#   (AArch64 ELF). Use fresh isolated LSP_BUILDDIR and LSP_CONFIG per build.
#
# Production diagnostics are disabled by default. Set
# LSP_ANDROID_INSTANTIATE_DIAGNOSTIC=1 only for explicit troubleshooting builds.
#
# Prerequisites:
#   - Android NDK 26.1.10909125 (ANDROID_SDK_ROOT/ndk/<ver>)
#   - host g++  (for the resource/meta host pass; LSP runs a host build first)
#   - the LSP submodule tree already fetched (`make fetch`/`make config` once)
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
LSP_ROOT="$(cd "$SCRIPT_DIR/../third_party/lsp" && pwd)"
cd "$LSP_ROOT"

BUILDDIR="${LSP_BUILDDIR:-.build}"
CONFIG="${LSP_CONFIG:-.config.mk}"
DIAGNOSTIC="${LSP_ANDROID_INSTANTIATE_DIAGNOSTIC:-0}"
case "$DIAGNOSTIC" in 0|1) ;; *) echo "LSP_ANDROID_INSTANTIATE_DIAGNOSTIC must be 0 or 1" >&2; exit 2 ;; esac
[[ "$BUILDDIR" = /* ]] || BUILDDIR="$LSP_ROOT/$BUILDDIR"
[[ "$CONFIG" = /* ]] || CONFIG="$LSP_ROOT/$CONFIG"
mkdir -p "$BUILDDIR"

NDK_VER="26.1.10909125"
ANDROID_MIN_API="26"

: "${ANDROID_SDK_ROOT:=${ANDROID_HOME:-$HOME/Android/Sdk}}"
NDK="${ANDROID_SDK_ROOT}/ndk/${NDK_VER}"
PRE="${NDK}/toolchains/llvm/prebuilt/linux-x86_64"

if [ ! -x "${PRE}/bin/aarch64-linux-android${ANDROID_MIN_API}-clang++" ]; then
    echo "build-lsp-ladspa-android.sh: NDK clang++ not found at ${PRE}" >&2
    echo "Install NDK ${NDK_VER} via Android SDK Manager." >&2
    exit 1
fi

export ANDROID_TARGET=1
export ANDROID_API="${ANDROID_MIN_API}"
export ANDROID_ABI="arm64-v8a"

export CXXFLAGS="${CXXFLAGS:-}"
if [ "$DIAGNOSTIC" = 1 ]; then
    export CXXFLAGS="${CXXFLAGS} -DLSP_ANDROID_INSTANTIATE_DIAGNOSTIC=1"
fi

# NDK toolchain variables consumed by LSP's make (CC ?= / CXX ?= / ...).
export CC="${PRE}/bin/aarch64-linux-android${ANDROID_MIN_API}-clang"
export CXX="${PRE}/bin/aarch64-linux-android${ANDROID_MIN_API}-clang++"
export AR="${PRE}/bin/llvm-ar"
export AS="${PRE}/bin/llvm-as"
export LD="${PRE}/bin/ld.lld"
export RANLIB="${PRE}/bin/llvm-ranlib"
export STRIP="${PRE}/bin/llvm-strip"

# One-time configuration (idempotent: re-running is harmless).
make BUILDDIR="$BUILDDIR" CONFIG="$CONFIG" config \
    FEATURES='crosscompile ladspa' \
    ARCHITECTURE='aarch64' \
    EXPORT_SYMBOLS=0 \
    INSTALL_HEADERS=0 \
    LSP_ANDROID_INSTANTIATE_DIAGNOSTIC="$DIAGNOSTIC"

# Fetch the LSP submodule tree (idempotent).
make CONFIG="$CONFIG" fetch

# Apply the Android compatibility patches (idempotent).
"$SCRIPT_DIR/patches/apply-android-patches.sh"

# Build.
# Ensure build graph observes configuration choice; isolated output directory
# prevents stale production/diagnostic object reuse.
make BUILDDIR="$BUILDDIR" CONFIG="$CONFIG" \
    FEATURES='crosscompile ladspa' ARCHITECTURE='aarch64' \
    EXPORT_SYMBOLS=0 INSTALL_HEADERS=0 \
    LSP_ANDROID_INSTANTIATE_DIAGNOSTIC="$DIAGNOSTIC"

echo
echo "=== Artifact ==="
SO="$BUILDDIR/target/lsp-plugin-fw/lsp-plugins-ladspa.so"
ls -lh "$SO"
python3 - "$SO" <<'PY'
import struct, sys
p = sys.argv[1]
with open(p, "rb") as f:
    d = f.read(20)
assert d[:4] == b"\x7fELF", "not an ELF"
print(f"ELF class={'64' if d[4]==2 else '32'} data={'LE' if d[5]==1 else 'BE'} "
      f"e_machine={struct.unpack('<H',d[18:20])[0]} (183=aarch64)")
PY
if [ "$(python3 - "$SO" <<'PY'
import struct,sys
with open(sys.argv[1], 'rb') as f: d=f.read(20)
print(struct.unpack('<H', d[18:20])[0])
PY
)" != 183 ]; then echo "Expected AArch64 ELF" >&2; exit 1; fi
echo "=== Dynamic dependencies (NEEDED) ==="
"${PRE}/bin/llvm-readelf" -d "$SO" | grep -E "NEEDED|SONAME"
echo "=== LADSPA entry point ==="
"${PRE}/bin/llvm-readelf" --dyn-syms "$SO" | grep -E "ladspa_descriptor"
if [ "$DIAGNOSTIC" = 0 ] && strings "$SO" | grep -E 'WI_ENTER|I_ENTER|lsp_prepare_marker'; then
    echo "Diagnostic marker found in production artifact" >&2
    exit 1
fi
