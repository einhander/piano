#pragma once

// Add a segment-local interleaved FX return into its absolute frame location.
// returnBase points at segment start, while track points at frame zero.
inline void addSharedFxReturn(float* track, const float* returnBase,
                              int segmentOffset, int frameCount) noexcept {
    for (int frame = 0; frame < frameCount; ++frame) {
        track[(segmentOffset + frame) * 2] += returnBase[frame * 2];
        track[(segmentOffset + frame) * 2 + 1] += returnBase[frame * 2 + 1];
    }
}
