#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../../../../../.." && pwd)"
OUT="${TMPDIR:-/tmp}/shared_delay_routing_test"
g++ -std=c++17 -O2 -Wall -Wextra -Werror -I"$ROOT/app/src/main/cpp" \
  "$ROOT/app/src/main/cpp/effects/SharedStereoDelay.cpp" \
  "$ROOT/app/src/main/cpp/lsp-integration/tests/shared_delay_routing_test.cpp" -o "$OUT"
"$OUT"
