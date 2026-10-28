#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../../../../../.." && pwd)"
OUT="${TMPDIR:-/tmp}/sample_rate_gate_test"
g++ -std=c++17 -O2 -Wall -Wextra -Werror -I"$ROOT/app/src/main/cpp" \
  "$ROOT/app/src/main/cpp/lsp-integration/tests/sample_rate_gate_test.cpp" -o "$OUT"
"$OUT"
