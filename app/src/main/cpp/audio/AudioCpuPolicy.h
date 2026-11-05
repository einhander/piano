#pragma once
#include "FastCpuSelection.h"
#include <atomic>
#include <cerrno>
#include <fstream>
#include <mutex>
#include <sched.h>
#include <sstream>
#include <string>
#include <unistd.h>

namespace piano::audio {
/** All methods except atomic reads run on control/diagnostic workers. */
class AudioCpuPolicy {
public:
    static AudioCpuPolicy& instance() { static AudioCpuPolicy policy; return policy; }
    void configure(bool fast, int cores) {
        std::lock_guard<std::mutex> lock(mutex);
        if (latched) return;
        latched = true;
        requestedFast = fast;
        requestedCores = (cores == 2 || cores == 4) ? cores : 1;
        if (!fast) { topologyStatus = "off"; return; }
        cpu_set_t allowedMask;
        CPU_ZERO(&allowedMask);
        if (sched_getaffinity(0, sizeof(allowedMask), &allowedMask) != 0) {
            topologyStatus = "allowed-mask-error:" + std::to_string(errno); return;
        }
        std::vector<int> allowed;
        std::vector<long> capacities(CPU_SETSIZE, 0), frequencies(CPU_SETSIZE, 0);
        for (int cpu = 0; cpu < CPU_SETSIZE; ++cpu) if (CPU_ISSET(cpu, &allowedMask)) {
            allowed.push_back(cpu);
            const std::string base = "/sys/devices/system/cpu/cpu" + std::to_string(cpu);
            std::ifstream(base + "/cpu_capacity") >> capacities[cpu];
            std::ifstream(base + "/cpufreq/cpuinfo_max_freq") >> frequencies[cpu];
        }
        auto fastCpus = selectFastCpus(allowed, capacities);
        topologyStatus = "capacity";
        if (fastCpus.empty()) { fastCpus = selectFastCpus(allowed, frequencies); topologyStatus = "max-frequency"; }
        CPU_ZERO(&fastMask);
        for (int cpu : fastCpus) CPU_SET(cpu, &fastMask);
        available = !fastCpus.empty();
        if (!available) topologyStatus = "unavailable-or-homogeneous";
    }
    int cores() const { return requestedCores; }
    void ensureConfigured() { configure(false, 1); }
    bool mask(cpu_set_t& out) const {
        std::lock_guard<std::mutex> lock(mutex);
        if (!available) return false;
        out = fastMask; return true;
    }
    void recordSynth(int slot, int effective, bool affinityApplied, int affinityError, int restoreError = 0) {
        if (slot >= 0 && slot < 2) {
            actualCores[slot].store(effective);
            workerAffinity[slot].store(affinityApplied);
            workerError[slot].store(affinityError);
            creatorRestoreError[slot].store(restoreError);
        }
    }
    static std::string maskText(const cpu_set_t& mask) {
        std::string text;
        for (int cpu = 0; cpu < CPU_SETSIZE; ++cpu) if (CPU_ISSET(cpu, &mask)) {
            if (!text.empty()) text += ',';
            text += std::to_string(cpu);
        }
        return text.empty() ? "none" : text;
    }
    std::string diagnostics() const {
        std::lock_guard<std::mutex> lock(mutex);
        std::ostringstream out;
        out << "cpu_experimental={latched=" << latched << ",fast_requested=" << requestedFast
            << ",topology=" << topologyStatus << ",fast_mask=" << maskText(fastMask)
            << ",synth_requested=" << requestedCores << ",synth_actual=" << actualCores[0].load()
            << '/' << actualCores[1].load() << ",worker_affinity=" << workerAffinity[0].load()
            << '/' << workerAffinity[1].load() << ",worker_errno=" << workerError[0].load()
            << '/' << workerError[1].load() << ",creator_restore_errno=" << creatorRestoreError[0].load()
            << '/' << creatorRestoreError[1].load() << '}';
        return out.str();
    }
private:
    mutable std::mutex mutex;
    bool latched = false, requestedFast = false, available = false;
    int requestedCores = 1;
    cpu_set_t fastMask{};
    std::string topologyStatus = "not-configured";
    std::atomic<int> actualCores[2]{{0}, {0}}, workerError[2]{{0}, {0}};
    std::atomic<int> creatorRestoreError[2]{{0}, {0}};
    std::atomic<bool> workerAffinity[2]{{false}, {false}};
};

// pthread workers inherit creator affinity. Never constructed on audio callback.
class SynthCreationAffinity {
public:
    SynthCreationAffinity() {
        cpu_set_t target;
        if (!AudioCpuPolicy::instance().mask(target)) return;
        if (sched_getaffinity(0, sizeof(original), &original) != 0) { error = errno; return; }
        if (sched_setaffinity(0, sizeof(target), &target) != 0) { error = errno; return; }
        applied = true;
    }
    void restore() {
        if (!applied || restored) return;
        if (sched_setaffinity(0, sizeof(original), &original) != 0) restoreError = errno;
        else { restored = true; restoreError = 0; }
    }
    ~SynthCreationAffinity() { restore(); }
    bool applied = false;
    int error = 0;
    int restoreError = 0;
private:
    bool restored = false;
    cpu_set_t original{};
};
}
