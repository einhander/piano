// Run from repository root:
// g++ -std=c++17 -pthread app/src/main/cpp/tests/audio_cpu_policy_test.cpp -o tmp/audio_cpu_policy_test && tmp/audio_cpu_policy_test
#include "../audio/AudioCpuPolicy.h"
#include <cassert>

int main() {
    using piano::audio::selectFastCpus;
    assert((selectFastCpus({0, 1, 2, 3}, {512, 512, 1024, 1024}) == std::vector<int>{2, 3}));
    assert((selectFastCpus({0, 2}, {512, 512, 1024, 1024}) == std::vector<int>{2}));
    assert(selectFastCpus({0, 1}, {512, 512}).empty());
    assert(selectFastCpus({0, 1}, {512, 0}).empty());
    assert(selectFastCpus({}, {}).empty());
    assert(selectFastCpus({4}, {512}).empty());
    auto& policy = piano::audio::AudioCpuPolicy::instance();
    policy.configure(false, 4);
    policy.configure(true, 2);
    assert(policy.cores() == 4);
    cpu_set_t mask;
    assert(!policy.mask(mask));
    assert(policy.diagnostics().find("fast_requested=0") != std::string::npos);
    policy.recordSynth(0, 4, false, 0);
    policy.recordSynth(1, 4, false, 0);
    assert(policy.diagnostics().find("synth_actual=4/4") != std::string::npos);
}
