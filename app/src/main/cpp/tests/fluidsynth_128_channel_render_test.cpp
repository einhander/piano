#include <fluidsynth.h>
#include <array>
#include <cassert>
#include <cmath>
#include <cstdio>
#include <algorithm>
#include <chrono>
#include <vector>

// Host integration test against bundled FluidSynth. Diagnostic contract has
// 16 output buses: channel 15 renders; extended channel 16 is rejected by
// production FluidSynthEngine (this test checks actual FluidSynth group layout).
int main(int argc, char** argv) {
    assert(argc == 2);
    constexpr int channels = 16;
    constexpr int midiChannels = 128;
    constexpr int frames = 4096;
    fluid_settings_t* settings = new_fluid_settings();
    assert(settings);
    fluid_settings_setnum(settings, "synth.sample-rate", 48000.0);
    fluid_settings_setint(settings, "synth.audio-groups", channels);
    fluid_settings_setint(settings, "synth.audio-channels", channels);
    fluid_settings_setint(settings, "synth.midi-channels", midiChannels);
    fluid_settings_setint(settings, "synth.effects-groups", 1);
    fluid_settings_setint(settings, "synth.cpu-cores", 1);
    fluid_synth_t* synth = new_fluid_synth(settings);
    assert(synth);
    const int sfid = fluid_synth_sfload(synth, argv[1], 1);
    assert(sfid >= 0);

    constexpr int active[] = {0, 15};
    constexpr int notes[] = {48, 60};
    for (int i = 0; i < 2; ++i) {
        fluid_synth_program_select(synth, active[i], sfid, 0, 0);
        assert(fluid_synth_noteon(synth, active[i], notes[i], 110) == FLUID_OK);
    }

    std::vector<float> dry(static_cast<size_t>(channels) * 2 * frames, 0.0f);
    std::vector<float> fx(static_cast<size_t>(2) * frames, 0.0f);
    std::array<float*, channels * 2> dryPtrs{};
    for (int i = 0; i < channels * 2; ++i) dryPtrs[i] = dry.data() + static_cast<size_t>(i) * frames;
    float* fxPtrs[] = {fx.data(), fx.data() + frames};
    assert(fluid_synth_process(synth, frames, 2, fxPtrs, channels * 2, dryPtrs.data()) == FLUID_OK);

    double energy[channels]{};
    for (int ch = 0; ch < channels; ++ch) {
        for (int i = 0; i < frames; ++i) {
            const float l = dry[static_cast<size_t>(ch * 2) * frames + i];
            const float r = dry[static_cast<size_t>(ch * 2 + 1) * frames + i];
            energy[ch] += static_cast<double>(l) * l + static_cast<double>(r) * r;
        }
    }
    for (int ch : active) assert(energy[ch] > 1e-5);
    for (int ch = 0; ch < channels; ++ch) {
        if (ch != 0 && ch != 15) assert(energy[ch] < 1e-12);
    }
    assert(energy[0] != energy[15]);

    // Focused diagnostic render benchmark, using the production 16-group layout.
    constexpr int block = 64;
    constexpr int iterations = 1000;
    constexpr int warmup = 100;
    std::vector<float> benchDry(static_cast<size_t>(channels) * 2 * block);
    std::vector<float> benchFx(static_cast<size_t>(2) * block);
    std::array<float*, channels * 2> benchDryPtrs{};
    for (int i = 0; i < channels * 2; ++i) benchDryPtrs[i] = benchDry.data() + static_cast<size_t>(i) * block;
    float* benchFxPtrs[] = {benchFx.data(), benchFx.data() + block};
    const auto benchmark = [&](int groups) {
        // Settings are construction-time; build fresh synth with requested groups.
        fluid_settings_setint(settings, "synth.audio-groups", groups);
        fluid_settings_setint(settings, "synth.audio-channels", groups);
        // Settings are construction-time; reconfigure through a fresh synth.
        fluid_synth_t* measured = new_fluid_synth(settings);
        assert(measured);
        const int measuredSfid = fluid_synth_sfload(measured, argv[1], 1);
        assert(measuredSfid >= 0);
        for (int i = 0; i < 2; ++i) {
            fluid_synth_program_select(measured, active[i], measuredSfid, 0, 0);
            assert(fluid_synth_noteon(measured, active[i], notes[i], 110) == FLUID_OK);
        }
        for (int i = 0; i < warmup; ++i)
            assert(fluid_synth_process(measured, block, 2, benchFxPtrs, groups * 2, benchDryPtrs.data()) == FLUID_OK);
        std::vector<double> samples;
        samples.reserve(iterations);
        for (int i = 0; i < iterations; ++i) {
            const auto start = std::chrono::steady_clock::now();
            assert(fluid_synth_process(measured, block, 2, benchFxPtrs, groups * 2, benchDryPtrs.data()) == FLUID_OK);
            samples.push_back(std::chrono::duration<double, std::micro>(std::chrono::steady_clock::now() - start).count());
        }
        std::sort(samples.begin(), samples.end());
        std::printf("FluidSynth %d groups, 3 voices, 64 frames: median %.2f us, p95 %.2f us\n",
                    groups, samples[iterations / 2], samples[iterations * 95 / 100]);
        delete_fluid_synth(measured);
    };
    benchmark(16);

    delete_fluid_synth(synth);
    delete_fluid_settings(settings);
    return 0;
}
