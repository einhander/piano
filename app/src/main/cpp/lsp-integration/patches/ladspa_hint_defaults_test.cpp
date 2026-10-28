#include <cassert>
#include "ladspa_hint_defaults.h"

int main() {
    assert(ladspa_hint_default(LADSPA_HINT_BOUNDED_BELOW | LADSPA_HINT_BOUNDED_ABOVE | LADSPA_HINT_DEFAULT_LOW, 0, 100, 48000) == 25);
    assert(ladspa_hint_default(LADSPA_HINT_BOUNDED_BELOW | LADSPA_HINT_BOUNDED_ABOVE | LADSPA_HINT_DEFAULT_HIGH, 0, 100, 48000) == 75);
    assert(std::fabs(ladspa_hint_default(LADSPA_HINT_BOUNDED_BELOW | LADSPA_HINT_BOUNDED_ABOVE | LADSPA_HINT_LOGARITHMIC | LADSPA_HINT_DEFAULT_LOW, 1, 100, 48000)-std::pow(100.0f, 0.25f)) < 1e-5f);
    assert(ladspa_hint_default(LADSPA_HINT_SAMPLE_RATE | LADSPA_HINT_BOUNDED_BELOW | LADSPA_HINT_BOUNDED_ABOVE | LADSPA_HINT_DEFAULT_MIDDLE, 0.0f, 1.0f, 48000) == 24000);
    assert(ladspa_hint_default(LADSPA_HINT_DEFAULT_1 | LADSPA_HINT_SAMPLE_RATE, 0, 0, 48000) == 48000);
    assert(ladspa_hint_default(LADSPA_HINT_DEFAULT_1 | LADSPA_HINT_TOGGLED, 0, 0, 48000) == 1);
}
