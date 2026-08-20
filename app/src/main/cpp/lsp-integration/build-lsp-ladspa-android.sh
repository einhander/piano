#!/usr/bin/env bash
#
# Reproducible Android arm64-v8a build of the LSP 1.2.34 headless LADSPA bundle.
#
# Produces:
#   .build/target/lsp-plugin-fw/lsp-plugins-ladspa.so   (AArch64 ELF)
#
# This is the Phase-1 feasibility build. It is intentionally kept as a standalone
# script; Phase 26 will fold it into the repository's ./build.sh / CMake flow.
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

# NDK toolchain variables consumed by LSP's make (CC ?= / CXX ?= / ...).
export CC="${PRE}/bin/aarch64-linux-android${ANDROID_MIN_API}-clang"
export CXX="${PRE}/bin/aarch64-linux-android${ANDROID_MIN_API}-clang++"
export AR="${PRE}/bin/llvm-ar"
export AS="${PRE}/bin/llvm-as"
export LD="${PRE}/bin/ld.lld"
export RANLIB="${PRE}/bin/llvm-ranlib"
export STRIP="${PRE}/bin/llvm-strip"

# One-time configuration (idempotent: re-running is harmless).
make config \
    FEATURES='crosscompile ladspa' \
    ARCHITECTURE='aarch64' \
    EXPORT_SYMBOLS=0 \
    INSTALL_HEADERS=0

# Fetch the LSP submodule tree (idempotent).
make fetch

# Apply the Android compatibility patches (idempotent).
"$SCRIPT_DIR/patches/apply-android-patches.sh"

# Build.
make

echo
echo "=== Artifact ==="
SO=".build/target/lsp-plugin-fw/lsp-plugins-ladspa.so"
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
echo "=== Dynamic dependencies (NEEDED) ==="
"${PRE}/bin/llvm-readelf" -d "$SO" | grep -E "NEEDED|SONAME" || true
echo "=== LADSPA entry point ==="
"${PRE}/bin/llvm-readelf" --dyn-syms "$SO" | grep -E "ladspa_descriptor" || true
