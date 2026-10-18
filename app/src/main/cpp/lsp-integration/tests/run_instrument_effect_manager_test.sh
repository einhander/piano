#!/bin/sh
set -eu
ROOT=$(git rev-parse --show-toplevel)
cd "$ROOT"
OUT_DIR=app/build/instrument-effect-manager-test
mkdir -p "$OUT_DIR"
OUT=$(mktemp "$OUT_DIR/test.XXXXXX")
trap 'rm -f "$OUT"' EXIT HUP INT TERM
${CXX:-g++} -std=c++17 -O2 -Wall -Wextra -I app/src/main/cpp -I app/src/main/cpp/effects \
  app/src/main/cpp/lsp-integration/tests/instrument_effect_manager_test.cpp \
  app/src/main/cpp/effects/InstrumentEffectManager.cpp \
  app/src/main/cpp/effects/lsp/LspEffectIds.cpp \
  app/src/main/cpp/effects/lsp/LspEffectFactory.cpp \
  app/src/main/cpp/effects/ladspa/LadspaEffect.cpp \
  app/src/main/cpp/effects/ladspa/LadspaRegistry.cpp \
  -ldl -o "$OUT"
"$OUT"
