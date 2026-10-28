#!/bin/sh
set -eu
ROOT=$(git rev-parse --show-toplevel)
cd "$ROOT"
SO=${LSP_LADSPA_SO:-app/src/main/cpp/third_party/lsp/.build-stage-a-host/target/lsp-plugin-fw/lsp-plugins-ladspa.so}
OUT_DIR=app/build/ladspa-instrument-dsp-test
mkdir -p "$OUT_DIR"
OUT=$(mktemp "$OUT_DIR/ladspa_instrument_dsp_test.XXXXXX")
trap 'rm -f "$OUT"' EXIT HUP INT TERM
${CXX:-g++} -std=c++17 -O2 -Wall -Wextra -I app/src/main/cpp -I app/src/main/cpp/effects \
 app/src/main/cpp/lsp-integration/tests/ladspa_instrument_dsp_test.cpp \
 app/src/main/cpp/effects/ladspa/LadspaEffect.cpp app/src/main/cpp/effects/lsp/LspEffectIds.cpp \
 -ldl -o "$OUT"
"$OUT" "$SO"
