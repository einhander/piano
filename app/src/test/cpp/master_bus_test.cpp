// Host test command: c++ -std=c++17 -Wall -Wextra -Werror -fsanitize=address,undefined -fno-omit-frame-pointer -Iapp/src/main/cpp app/src/test/cpp/master_bus_test.cpp app/src/main/cpp/engine/MasterBus.cpp -o build/verification/master_bus_test && build/verification/master_bus_test
#include "engine/MasterBus.h"
#include <algorithm>
#include <cassert>
#include <cmath>
#include <cfloat>
#include <limits>
#include <vector>

static bool close(float a, float b, float tolerance = 2e-6f) {
    return std::fabs(a - b) <= tolerance;
}

int main() {
    MasterBus bus;
    bus.init(64);

    // Zero, odd, normal and oversized blocks. One-frame guards on both sides
    // detect accidental writes before data and past actual frames.
    for (int frames : {0, 1, 3, 17, 64, 65, 128, 193, 256, 513, 2048}) {
        std::vector<float> guarded(static_cast<size_t>(frames) * 2 + 2, 0.25f);
        guarded.front() = 123.0f;
        guarded.back() = 456.0f;
        bus.process(guarded.data() + 1, frames);
        assert(guarded.front() == 123.0f && guarded.back() == 456.0f);
        for (int i = 1; i <= frames * 2; ++i)
            assert(std::isfinite(guarded[i]) && std::fabs(guarded[i]) <= 1.0f);
    }

    bus.resetMeter();
    std::vector<float> impulse(8, 0.0f);
    impulse[3] = 0.8f; // frame 1, right channel; catches decimated-meter bug.
    bus.process(impulse.data(), 4);
    const float impulsePeak = std::fabs(impulse[3]);
    assert(close(bus.getPeakMeter(), impulsePeak));
    const float heldPeak = bus.getPeakMeter();
    float silence[6]{};
    bus.process(silence, 3);
    assert(close(bus.getPeakMeter(), heldPeak));

    bus.resetMeter();
    std::vector<float> known(10);
    for (int frame = 0; frame < 5; ++frame) {
        known[2 * frame] = 0.2f;
        known[2 * frame + 1] = 0.6f;
    }
    bus.process(known.data(), 5);
    double expectedSquares = 0.0;
    float expectedPeak = 0.0f;
    for (float sample : known) {
        expectedSquares += static_cast<double>(sample) * sample;
        expectedPeak = std::max(expectedPeak, std::fabs(sample));
    }
    assert(close(bus.getBlockRms(), static_cast<float>(std::sqrt(expectedSquares / known.size()))));
    assert(close(bus.getPeakMeter(), expectedPeak));

    // Count post-volume, pre-limiter stereo channel samples strictly above 1.
    bus.resetMeter();
    float countA[] = {1.0f, -1.0f, 2.0f, -2.0f};
    bus.process(countA, 2);
    assert(bus.getPreLimiterClipSamples() == 2);
    float countB[] = {1.5f, 0.5f};
    bus.process(countB, 1);
    assert(bus.getPreLimiterClipSamples() == 3); // cumulative across callbacks.
    bus.resetMeter();
    bus.setVolume(0.5f);
    float scaled[] = {2.0f, -2.0f};
    bus.process(scaled, 1);
    assert(bus.getPreLimiterClipSamples() == 0); // volume makes both exactly ±1.
    bus.setVolume(1.0f);
    bus.resetMeter();
    float muteCheck[] = {0.75f, -0.75f};
    bus.setVolume(0.0f);
    bus.process(muteCheck, 1);
    assert(muteCheck[0] == 0.0f && muteCheck[1] == 0.0f);
    bus.setVolume(1.0f);

    bus.resetMeter();
    float exceptional[] = {FLT_MAX, -FLT_MAX, INFINITY, -INFINITY, NAN, 0.0f};
    bus.process(exceptional, 3);
    for (float sample : exceptional) assert(std::isfinite(sample) && std::fabs(sample) <= 1.0f);
    assert(bus.getPreLimiterClipSamples() == 2);
    assert(bus.getNonFiniteSamples() == 3);

    // Soft clip must be symmetric and monotonic, never boosted above unity.
    float sweep[202];
    for (int i = 0; i < 101; ++i) sweep[2 * i] = sweep[2 * i + 1] = -5.0f + i * 0.1f;
    bus.process(sweep, 101);
    for (int i = 0; i < 101; ++i) {
        assert(std::fabs(sweep[2 * i]) <= 1.0f);
        assert(close(sweep[2 * i], sweep[2 * i + 1]));
        if (i) assert(sweep[2 * i] >= sweep[2 * (i - 1)]);
    }

    bus.resetMeter();
    assert(bus.getPeakMeter() == 0.0f && bus.getBlockRms() == 0.0f);
    assert(bus.getPreLimiterClipSamples() == 0 && bus.getNonFiniteSamples() == 0);
    float nextInterval[] = {0.1f, -0.1f};
    bus.process(nextInterval, 1);
    assert(bus.getPreLimiterClipSamples() == 0 && bus.getNonFiniteSamples() == 0);
    bus.init(8); // Reinitialization must be safe and preserve no stale storage.
    float reinitialized[] = {0.2f, -0.2f};
    bus.process(reinitialized, 1);
    assert(std::isfinite(reinitialized[0]) && std::fabs(reinitialized[0]) <= 1.0f);
}
