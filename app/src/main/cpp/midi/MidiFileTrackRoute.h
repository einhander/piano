#pragma once

// Valid route values: -1 preserves source channel; 0..255 absolute channels;
// 272..496 selects a 16-channel source bank. Invalid values fail closed.
constexpr int normalizeMidiFileTrackRoute(int route) {
    return route == -1 || (route >= 0 && route <= 255) ||
                   (route >= 272 && route <= 496 && (route - 256) % 16 == 0)
               ? route
               : -2;
}
