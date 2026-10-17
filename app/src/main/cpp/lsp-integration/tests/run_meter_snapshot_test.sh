#!/bin/sh
set -eu
ROOT=$(git rev-parse --show-toplevel)
cd "$ROOT"
OUT_DIR=app/build/meter-snapshot-test
mkdir -p "$OUT_DIR"
OUT=$(mktemp "$OUT_DIR/meter_snapshot_test.XXXXXX")
trap 'rm -f "$OUT"' EXIT HUP INT TERM
${CXX:-g++} -std=c++17 -O2 -Wall -Wextra \
  -I app/src/main/cpp -I app/src/main/cpp/effects \
  app/src/main/cpp/lsp-integration/tests/meter_snapshot_test.cpp \
  app/src/main/cpp/effects/ladspa/LadspaEffect.cpp \
  app/src/main/cpp/effects/lsp/LspEffectIds.cpp \
  -o "$OUT"
"$OUT"
printf '%s\n' 'LADSPA meter snapshot regression passed'
