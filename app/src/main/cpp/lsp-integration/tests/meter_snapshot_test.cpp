// Offline regression for LADSPA output-control meter snapshotting.
#include "effects/ladspa/LadspaEffect.h"
#include <cassert>
#include <cmath>

static float* ports[10];
static LADSPA_Handle instantiate(const LADSPA_Descriptor*, unsigned long) { return reinterpret_cast<void*>(1); }
static void connectPort(LADSPA_Handle, unsigned long p, LADSPA_Data* data) { ports[p] = data; }
static void run(LADSPA_Handle, unsigned long) {
    *ports[4] = 0.25f; *ports[5] = 0.5f;
    *ports[6] = 0.75f; *ports[7] = 1.0f;
    *ports[8] = 0.125f; *ports[9] = 0.0625f;
}
int main() {
    static const LADSPA_PortDescriptor pd[10] = {
        LADSPA_PORT_AUDIO | LADSPA_PORT_INPUT, LADSPA_PORT_AUDIO | LADSPA_PORT_INPUT,
        LADSPA_PORT_AUDIO | LADSPA_PORT_OUTPUT, LADSPA_PORT_AUDIO | LADSPA_PORT_OUTPUT,
        LADSPA_PORT_CONTROL | LADSPA_PORT_OUTPUT, LADSPA_PORT_CONTROL | LADSPA_PORT_OUTPUT,
        LADSPA_PORT_CONTROL | LADSPA_PORT_OUTPUT, LADSPA_PORT_CONTROL | LADSPA_PORT_OUTPUT,
        LADSPA_PORT_CONTROL | LADSPA_PORT_OUTPUT, LADSPA_PORT_CONTROL | LADSPA_PORT_OUTPUT};
    static const char* names[10] = {"Audio In L", "Audio In R", "Audio Out L", "Audio Out R",
        "Input level meter Left (gain)", "Input level meter Right (gain)",
        "Output level meter Left (gain)", "Output level meter Right (gain)",
        "Reduction level meter Left (gain)", "Reduction level meter Right (gain)"};
    LADSPA_Descriptor d{};
    d.PortCount = 10; d.PortDescriptors = pd; d.PortNames = names;
    d.instantiate = instantiate; d.connect_port = connectPort; d.run = run;
    d.Label = "test";
    piano::ladspa::LadspaEffect effect(piano::lsp::kSlotCompressor, &d);
    assert(effect.prepare(48000, 16));
    float l[16]{}, r[16]{};
    effect.setBypassed(false); effect.process(l, r, 16);
    auto m = effect.meterSnapshot();
    assert(m[0] == .25f && m[1] == .5f && m[2] == .75f && m[3] == 1.f && m[4] == .125f && m[5] == .0625f);
    effect.process(l, r, 16);
    m = effect.meterSnapshot();
    assert(m[4] == .125f && m[5] == .0625f);

    names[9] = "Unrecognized meter";
    piano::ladspa::LadspaEffect partial(piano::lsp::kSlotCompressor, &d);
    assert(partial.prepare(48000, 16));
    partial.setBypassed(false); partial.process(l, r, 16);
    m = partial.meterSnapshot();
    assert(m[4] == .125f && std::isnan(m[5]));

    names[8] = "Gain reduction level meter Left (gain)";
    names[9] = "Gain reduction level meter Right (gain)";
    piano::ladspa::LadspaEffect limiter(piano::lsp::kSlotLimiter, &d);
    assert(limiter.prepare(48000, 16));
    limiter.setBypassed(false); limiter.process(l, r, 16);
    m = limiter.meterSnapshot();
    assert(m[0] == .25f && m[1] == .5f && m[2] == .75f && m[3] == 1.f && m[4] == .125f && m[5] == .0625f);
}
