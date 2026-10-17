#!/bin/sh
set -eu
ROOT=$(git rev-parse --show-toplevel)
cd "$ROOT"
OUT_DIR=app/build/spectrum-capture-ring-test
mkdir -p "$OUT_DIR"
OUT=$(mktemp "$OUT_DIR/test.XXXXXX")
trap 'rm -f "$OUT"' EXIT HUP INT TERM
${CXX:-g++} -std=c++17 -O2 -Wall -Wextra -I app/src/main/cpp \
  app/src/main/cpp/lsp-integration/tests/spectrum_capture_ring_test.cpp -o "$OUT"
"$OUT"
printf '%s\n' 'Spectrum capture ring regression passed'
