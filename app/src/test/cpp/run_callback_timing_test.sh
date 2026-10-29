#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/../../.." && pwd)
out=$(mktemp)
trap 'rm -f "$out"' EXIT
${CXX:-c++} -std=c++17 -Wall -Wextra -Werror -I"$root/src/main/cpp" \
  "$root/src/test/cpp/CallbackTimingTest.cpp" -o "$out"
"$out"
