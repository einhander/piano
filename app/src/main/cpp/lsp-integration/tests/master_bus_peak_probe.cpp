// Diagnostic probe: existing post-Limiter MasterBus is intentionally unchanged.
#include "effects/EffectChain.h"
#include "engine/MasterBus.h"
#include <array>
#include <cmath>
#include <cstdio>
#include <cassert>
int main(int argc, char** argv) {
    assert(argc == 2); piano::EffectChain chain;
    assert(chain.loadBundle(argv[1], 48000, 256) == 3);
    chain.effect(2)->setBypassed(false); MasterBus bus; bus.init(256); bus.setVolume(1);
    std::array<float, 512> samples{}; float before = 0, after = 0;
    for (int block = 0; block < 400; ++block) {
        for (int i = 0; i < 256; ++i) samples[i*2] = samples[i*2+1] = 2.f * std::sin(2 * 3.141592653589793 * 440 * (block*256+i) / 48000.);
        chain.process(samples.data(), 256);
        if (block > 300) for (float value : samples) before = std::fmax(before, std::abs(value));
        bus.process(samples.data(), 256);
        if (block > 300) for (float value : samples) { assert(std::isfinite(value)); after = std::fmax(after, std::abs(value)); }
    }
    std::printf("Limiter peak %.8f; post-MasterBus peak %.8f\n", before, after);
}
