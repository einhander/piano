#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../../../../../.." && pwd)"
OUT="$ROOT/app/build/instrument_delay_bank_test"
mkdir -p "$ROOT/app/build"
g++ -std=c++17 -O2 -Wall -Wextra -Werror -pthread \
  -I"$ROOT/app/src/main/cpp" \
  "$ROOT/app/src/main/cpp/effects/SharedStereoDelay.cpp" \
  "$ROOT/app/src/main/cpp/effects/InstrumentDelayBank.cpp" \
  "$ROOT/app/src/main/cpp/lsp-integration/tests/instrument_delay_bank_test.cpp" \
  -o "$OUT"
"$OUT"
