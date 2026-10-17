#include "effects/ladspa/LadspaEffect.h"
#include <cassert>
#include <cmath>
#include <cstdio>
#include <algorithm>

namespace {
float observed[256];
unsigned long sampleRateSeen;
struct Instance { LADSPA_Data* ports[256]{}; };
LADSPA_Handle instantiate(const LADSPA_Descriptor*, unsigned long sr) {
    sampleRateSeen = sr;
    return new Instance{};
}
void connect(LADSPA_Handle h, unsigned long p, LADSPA_Data* v) {
    static_cast<Instance*>(h)->ports[p] = v;
}
void activate(LADSPA_Handle) {}
void cleanup(LADSPA_Handle h) { delete static_cast<Instance*>(h); }
void run(LADSPA_Handle h, unsigned long) {
    auto* i = static_cast<Instance*>(h);
    for (int p = 0; p < 256; ++p) if (i->ports[p]) observed[p] = *i->ports[p];
}
}

int main() {
    LADSPA_PortDescriptor ports[218]{};
    LADSPA_PortRangeHint hints[218]{};
    for (auto& p : ports) p = LADSPA_PORT_CONTROL | LADSPA_PORT_INPUT;
    ports[0] = ports[1] = ports[2] = ports[3] = LADSPA_PORT_AUDIO | LADSPA_PORT_INPUT;
    ports[4] = ports[205] = LADSPA_PORT_CONTROL | LADSPA_PORT_OUTPUT;
    // Default LOW logarithmic; verify proper interpolation and sample-rate scaling.
    hints[10] = {LADSPA_HINT_BOUNDED_BELOW | LADSPA_HINT_BOUNDED_ABOVE | LADSPA_HINT_LOGARITHMIC | LADSPA_HINT_DEFAULT_LOW, 1.f, 100.f};
    // MID linear, HIGH sample-rate bounds, integer rounding, toggled default.
    hints[11] = {LADSPA_HINT_BOUNDED_BELOW | LADSPA_HINT_BOUNDED_ABOVE | LADSPA_HINT_DEFAULT_MIDDLE, 2.f, 10.f};
    hints[12] = {LADSPA_HINT_BOUNDED_BELOW | LADSPA_HINT_BOUNDED_ABOVE | LADSPA_HINT_SAMPLE_RATE | LADSPA_HINT_DEFAULT_MIDDLE, .1f, .5f};
    hints[13] = {LADSPA_HINT_BOUNDED_BELOW | LADSPA_HINT_BOUNDED_ABOVE | LADSPA_HINT_INTEGER | LADSPA_HINT_DEFAULT_MIDDLE, 0.f, 7.f};
    hints[14] = {LADSPA_HINT_TOGGLED | LADSPA_HINT_DEFAULT_1, 0.f, 0.f};
    hints[15] = {LADSPA_HINT_BOUNDED_BELOW | LADSPA_HINT_BOUNDED_ABOVE, -4.f, 8.f}; // NONE -> zero
    hints[16] = {LADSPA_HINT_BOUNDED_BELOW | LADSPA_HINT_BOUNDED_ABOVE | LADSPA_HINT_DEFAULT_MINIMUM, -3.f, 9.f};
    hints[17] = {LADSPA_HINT_BOUNDED_BELOW | LADSPA_HINT_BOUNDED_ABOVE | LADSPA_HINT_DEFAULT_MAXIMUM, -3.f, 9.f};
    hints[18] = {LADSPA_HINT_DEFAULT_0, 0.f, 0.f};
    hints[19] = {LADSPA_HINT_DEFAULT_100, 0.f, 0.f};
    hints[20] = {LADSPA_HINT_DEFAULT_440, 0.f, 0.f};
    hints[21] = {LADSPA_HINT_TOGGLED | LADSPA_HINT_DEFAULT_0, 0.f, 0.f};
    hints[22] = {LADSPA_HINT_BOUNDED_BELOW | LADSPA_HINT_BOUNDED_ABOVE | LADSPA_HINT_DEFAULT_HIGH, 0.f, 8.f};
    ports[205] = LADSPA_PORT_CONTROL | LADSPA_PORT_OUTPUT;
    hints[205] = {LADSPA_HINT_DEFAULT_100, 0.f, 0.f};
    LADSPA_Descriptor d{};
    d.Label = "test"; d.PortCount = 218; d.PortDescriptors = ports; d.PortRangeHints = hints;
    d.instantiate = instantiate; d.connect_port = connect; d.activate = activate; d.cleanup = cleanup; d.run = run;
    piano::ladspa::LadspaEffect effect(piano::lsp::kSlotEq, &d);
    assert(effect.prepare(48000, 64));
    assert(sampleRateSeen == 48000);
    float l[64]{}, r[64]{};
    effect.setBypassed(false); effect.process(l, r, 64);
    assert(std::fabs(observed[10] - std::sqrt(10.f)) < .001f);
    assert(std::fabs(observed[11] - 6.f) < .001f);
    assert(std::fabs(observed[12] - 14400.f) < .01f);
    assert(observed[13] == 4.f && observed[14] == 1.f);
    assert(observed[15] == 0.f && observed[16] == -3.f && observed[17] == 9.f);
    assert(observed[18] == 0.f && observed[19] == 100.f && observed[20] == 440.f && observed[21] == 0.f);
    assert(observed[22] == 6.f); // HIGH interpolation.
    assert(observed[205] == 0.f); // Output controls are not host-initialized inputs.
    int tableCount = 0;
    for (int slot = 0; slot < piano::lsp::kMasterEffectCount; ++slot) {
        const auto* table = piano::lsp::paramTable(slot, tableCount);
        for (int i = 0; i < tableCount; ++i)
            assert(table[i].def >= table[i].min && table[i].def <= table[i].max);
        const auto* ui = piano::lsp::paramDescriptors(slot, tableCount);
        for (int i = 0; i < tableCount; ++i)
            assert(ui[i].defaultValue >= ui[i].minValue && ui[i].defaultValue <= ui[i].maxValue);
    }
    const auto* limRelease = piano::lsp::paramPortFor(piano::lsp::kSlotLimiter, piano::lsp::kParamLimReleaseMs);
    assert(limRelease && limRelease->def == 5.f);
    const auto* limUi = piano::lsp::paramDescriptors(piano::lsp::kSlotLimiter, tableCount);
    bool uiReleaseFive = false;
    for (int i = 0; i < tableCount; ++i)
        if (limUi[i].id == piano::lsp::kParamLimReleaseMs) uiReleaseFive = limUi[i].defaultValue == 5.f;
    assert(uiReleaseFive);
    // Mapped EQ frequency default overrides descriptor-derived value at port 33.
    assert(observed[33] == 1000.f);
    for (int n = 0; n < 64; ++n) l[n] = 2.f, r[n] = 3.f;
    effect.setBypassed(true); effect.process(l, r, 64);
    assert(l[0] == 2.f && r[0] == 3.f);
    assert(effect.prepare(44100, 64) && sampleRateSeen == 44100);
    effect.setBypassed(false); effect.process(l, r, 64);
    assert(std::fabs(observed[12] - 13230.f) < .01f);
    std::puts("LADSPA defaults regression passed");
}
