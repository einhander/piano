#include "../synth/FluidSynthEngine.h"
#include <cassert>
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <memory>
#include <vector>

static double energy(const std::vector<float>& samples) {
    double e = 0.0;
    for (float x : samples) e += static_cast<double>(x) * x;
    return e;
}

int main(int argc, char** argv) {
    assert(argc == 2);
    auto engine = std::make_unique<FluidSynthEngine>();
    assert(engine->init(48000, 256));
    const int sfid = engine->loadSoundFont(argv[1]);
    assert(sfid >= 0);
    engine->setReverb(false);
    engine->setChorus(false);
    const auto presets = engine->getInstruments();
    assert(!presets.empty());
    const auto preset = presets.front();

    // Absolute channels 0, 16, 127 retain independent accepted offsets.
    assert(engine->setChannelRelease(0, 2400.0f));
    assert(engine->setChannelRelease(16, -2400.0f));
    assert(engine->setChannelRelease(127, 900.0f));
    assert(engine->getChannelRelease(0) == 2400.0f);
    assert(engine->getChannelRelease(16) == -2400.0f);
    assert(engine->getChannelRelease(127) == 900.0f);
    assert(!engine->setChannelRelease(-1, 0));
    assert(!engine->setChannelRelease(128, 0));
    assert(!engine->setChannelRelease(0, NAN));
    assert(!engine->setChannelRelease(0, INFINITY));
    assert(!engine->setChannelRelease(0, 2400.1f));
    assert(engine->getChannelRelease(0) == 2400.0f);

    // Drain accepted commands to keep a configured soundfont synth alive.
    engine->beginSynthAccess(); engine->processCommands(); engine->endSynthAccess();

    // Fill the command queue without draining it. A rejected write must not
    // alter desired state; queue capacity is 256, third distinct channel retained.
    int accepted = 0;
    float lastAccepted = 0.0f;
    for (int i = 0; i < 400; ++i) {
        const float value = static_cast<float>(i);
        if (engine->setChannelRelease(15, value)) { ++accepted; lastAccepted = value; }
    }
    assert(accepted > 0 && accepted < 400);
    assert(engine->getChannelRelease(15) == lastAccepted);

    engine->beginSynthAccess(); engine->processCommands(); engine->endSynthAccess();
    // Reset range clears desired release for every channel in range.
    assert(engine->setChannelRelease(0, 1200));
    assert(engine->setChannelRelease(16, -1200));
    assert(engine->resetChannelRange(0, 16));
    assert(engine->getChannelRelease(0) == 0.0f);
    assert(engine->getChannelRelease(15) == 0.0f);
    assert(engine->getChannelRelease(16) == -1200.0f);
    engine->beginSynthAccess(); engine->processCommands(); engine->endSynthAccess();

    // Render envelope-tail comparison directly against bundled FluidSynth via
    // engine API: program 0 may choose whichever preset SF2 supplies at bank 0.
    auto renderTail = [&engine, &preset](float releaseOffset, int resetControllers = 0) {
        constexpr int channel = 0, chunkFrames = 512;
        assert(engine->resetChannelRange(0, 128));
        assert(engine->setChannelProgram(channel, preset.bank, preset.program));
        assert(engine->setChannelRelease(channel, releaseOffset));
        engine->beginSynthAccess(); engine->processCommands(); engine->endSynthAccess();
        if (resetControllers == 1) {
            engine->controlChange(channel, 121, 0);
            engine->beginSynthAccess(); engine->processCommands(); engine->endSynthAccess();
        } else if (resetControllers == 2) {
            MidiMessage cc{}; cc.status = 0xB0; cc.data1 = 121; cc.channel = channel;
            engine->beginSynthAccess(); engine->processOneMidi(cc); engine->endSynthAccess();
        }
        engine->noteOn(channel, 60, 120);
        engine->beginSynthAccess(); engine->processCommands(); engine->endSynthAccess();
        std::vector<float> block(chunkFrames * 2);
        auto renderFrames = [&](int frames) {
            double result = 0;
            while (frames > 0) {
                const int count = std::min(frames, chunkFrames);
                std::fill(block.begin(), block.end(), 0.0f);
                engine->beginSynthAccess(); engine->render(block.data(), count); engine->endSynthAccess();
                result += energy(std::vector<float>(block.begin(), block.begin() + count * 2));
                frames -= count;
            }
            return result;
        };
        const double sustainEnergy = renderFrames(12000); // allow attack/decay before note-off
        engine->noteOff(channel, 60);
        engine->beginSynthAccess(); engine->processCommands(); engine->endSynthAccess();
        const double tailEnergy = renderFrames(48000);
        assert(sustainEnergy > 0.0);
        return tailEnergy;
    };
    const double shortTail = renderTail(-2400.0f);
    const double defaultTail = renderTail(0.0f);
    const double longTail = renderTail(2400.0f);
    std::printf("release tail energy short=%g default=%g long=%g\n", shortTail, defaultTail, longTail);
    assert(shortTail < defaultTail && defaultTail < longTail);
    assert(renderTail(2400.0f, 1) > defaultTail * 1.5);
    assert(renderTail(2400.0f, 2) > defaultTail * 1.5);

    // Slot swap/reload replays desired offsets, including channels above 15.
    assert(engine->setChannelRelease(127, 1600.0f));
    assert(engine->loadSoundFont(argv[1]) >= 0);
    assert(engine->getChannelRelease(127) == 1600.0f);
    return 0;
}
