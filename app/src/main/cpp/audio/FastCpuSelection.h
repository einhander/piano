#pragma once
#include <vector>
#include <algorithm>

namespace piano::audio {
// A partial/unknown topology must not silently select arbitrary CPU numbers.
inline std::vector<int> selectFastCpus(const std::vector<int>& allowed,
                                       const std::vector<long>& capacities) {
    if (allowed.empty()) return {};
    long minimum = 0, maximum = 0;
    for (int cpu : allowed) {
        if (cpu < 0 || static_cast<size_t>(cpu) >= capacities.size() || capacities[cpu] <= 0) return {};
        const long value = capacities[cpu];
        minimum = minimum == 0 ? value : std::min(minimum, value);
        maximum = std::max(maximum, value);
    }
    if (minimum == maximum) return {}; // homogeneous or only one cluster allowed
    std::vector<int> fast;
    for (int cpu : allowed) if (capacities[cpu] == maximum) fast.push_back(cpu);
    return fast;
}
}
