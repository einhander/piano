#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
CPP="$ROOT/app/src/main/cpp"
TMP_ROOT="${TMPDIR:-/tmp/opencode}"
BUILD_DIR="$(mktemp -d "$TMP_ROOT/instrument-fx-catalog.XXXXXX")"
trap 'rm -rf "$BUILD_DIR"' EXIT

g++ -std=c++17 -I"$CPP" -I"$CPP/effects" -I"$CPP/effects/lsp" \
  "$ROOT/app/src/main/cpp/lsp-integration/tests/instrument_fx_catalog_dump.cpp" \
  "$CPP/effects/lsp/LspEffectIds.cpp" \
  -o "$BUILD_DIR/instrument_fx_catalog_dump"

"$BUILD_DIR/instrument_fx_catalog_dump" > "$ROOT/app/src/test/resources/instrument_fx_native_catalog.tsv"
