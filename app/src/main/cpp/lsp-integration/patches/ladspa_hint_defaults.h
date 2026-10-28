#pragma once

#include <cmath>
#include "ladspa/ladspa.h"

inline float ladspa_hint_default(LADSPA_PortRangeHintDescriptor hints,
                                 float lower, float upper, float sample_rate) {
    const float scale = (hints & LADSPA_HINT_SAMPLE_RATE) ? sample_rate : 1.0f;
    const float lo = lower * scale;
    const float hi = upper * scale;
    float value = 0.0f;
    switch (hints & LADSPA_HINT_DEFAULT_MASK) {
        case LADSPA_HINT_DEFAULT_MINIMUM: value = lo; break;
        case LADSPA_HINT_DEFAULT_LOW:
        case LADSPA_HINT_DEFAULT_MIDDLE:
        case LADSPA_HINT_DEFAULT_HIGH: {
            const auto mode = hints & LADSPA_HINT_DEFAULT_MASK;
            const float low_weight = mode == LADSPA_HINT_DEFAULT_LOW ? 0.75f
                : (mode == LADSPA_HINT_DEFAULT_HIGH ? 0.25f : 0.5f);
            const float high_weight = 1.0f - low_weight;
            value = (hints & LADSPA_HINT_LOGARITHMIC) && lo > 0.0f && hi > 0.0f
                ? std::pow(lo, low_weight) * std::pow(hi, high_weight)
                : lo * low_weight + hi * high_weight;
            break;
        }
        case LADSPA_HINT_DEFAULT_MAXIMUM: value = hi; break;
        case LADSPA_HINT_DEFAULT_0: value = 0.0f; break;
        case LADSPA_HINT_DEFAULT_1: value = scale; break;
        case LADSPA_HINT_DEFAULT_100: value = 100.0f * scale; break;
        case LADSPA_HINT_DEFAULT_440: value = 440.0f * scale; break;
        case LADSPA_HINT_DEFAULT_NONE:
        default: value = 0.0f; break;
    }
    if (hints & LADSPA_HINT_INTEGER) value = std::round(value);
    if (hints & LADSPA_HINT_TOGGLED) value = value >= 0.5f ? 1.0f : 0.0f;
    return value;
}
