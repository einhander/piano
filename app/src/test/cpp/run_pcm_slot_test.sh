#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/../../.." && pwd)
out="$root/build/verification/pcm_slot_test"
mkdir -p "$(dirname -- "$out")"
${CXX:-c++} -std=c++17 -Wall -Wextra -Werror -fsanitize=address,undefined -fno-omit-frame-pointer -pthread -I"$root/src/main/cpp" \
  "$root/src/main/cpp/audio/PcmSlotPlayer.cpp" \
  "$root/src/test/cpp/PcmSlotPlayerTest.cpp" -o "$out"
"$out"
