#pragma once

#include <atomic>
#include <cstdint>
#include <array>

// Single audio-thread producer, single diagnostic worker consumer. Window
// maxima are independently exchanged, so snapshot fields are approximate.
struct CallbackTiming {
    static_assert(std::atomic<uint32_t>::is_always_lock_free,
                  "Callback diagnostic counters must remain lock-free");
    std::atomic<uint32_t> count{0}, frames{0}, maxWallUs{0}, maxGapUs{0}, overBudget{0};
    // Sampled NativeEngine region: synthesized-channel render wall time and
    // callback-thread CPU time. Slot values are window maxes (microseconds).
    std::atomic<uint32_t> stageCount{0}, synthWallUs{0}, engineCpuUs{0};
    // Cumulative callback progress + last published stage. Never exchanged:
    // diagnostic snapshots must expose a callback stuck between two stages.
    std::atomic<uint32_t> callbackEnterTotal{0}, callbackExitTotal{0}, callbackStage{0};
    // Last completed sample's coherent stage breakdown; distinct from max metrics.
    std::atomic<uint32_t> detailSequence{0}, detailEngineCpuUs{0}, detailSynthWallUs{0}, detailSynthCpuUs{0}, detailInstFxCpuUs{0},
        detailDelayCpuUs{0}, detailBusCpuUs{0}, detailMixerCpuUs{0}, detailMasterCpuUs{0},
        detailOtherCpuUs{0};
    uint64_t previousEntryNs = 0; // producer-owned; no atomic64 required

    static uint32_t micros(uint64_t ns) noexcept { return static_cast<uint32_t>(ns / 1000); }
    static void updateMax(std::atomic<uint32_t>& target, uint32_t value) noexcept {
        uint32_t old = target.load(std::memory_order_relaxed);
        while (old < value && !target.compare_exchange_weak(old, value,
                   std::memory_order_relaxed, std::memory_order_relaxed)) {}
    }
    void enter(uint64_t ns) noexcept {
        if (previousEntryNs) updateMax(maxGapUs, micros(ns - previousEntryNs));
        previousEntryNs = ns;
    }
    void finish(uint32_t durationUs, uint32_t numFrames, uint32_t rate) noexcept {
        count.fetch_add(1, std::memory_order_relaxed);
        frames.fetch_add(numFrames, std::memory_order_relaxed);
        updateMax(maxWallUs, durationUs);
        if (rate && static_cast<uint64_t>(durationUs) * rate > static_cast<uint64_t>(numFrames) * 1000000u)
            overBudget.fetch_add(1, std::memory_order_relaxed);
    }
    uint32_t take(std::atomic<uint32_t>& value) noexcept { return value.exchange(0, std::memory_order_acq_rel); }
    void finishStage(uint32_t synthUs, uint32_t cpuUs) noexcept {
        stageCount.fetch_add(1, std::memory_order_relaxed);
        updateMax(synthWallUs, synthUs);
        updateMax(engineCpuUs, cpuUs);
    }
    void finishDetailedStage(uint32_t synthWallUsValue, uint32_t cpuUs, const std::array<uint32_t, 7>& stages) noexcept {
        finishStage(synthWallUsValue, cpuUs);
        detailSequence.fetch_add(1, std::memory_order_acq_rel); // odd: writer active
        detailEngineCpuUs.store(cpuUs, std::memory_order_relaxed);
        detailSynthWallUs.store(synthWallUsValue, std::memory_order_relaxed);
        detailSynthCpuUs.store(stages[0], std::memory_order_relaxed);
        detailInstFxCpuUs.store(stages[1], std::memory_order_relaxed);
        detailDelayCpuUs.store(stages[2], std::memory_order_relaxed);
        detailBusCpuUs.store(stages[3], std::memory_order_relaxed);
        detailMixerCpuUs.store(stages[4], std::memory_order_relaxed);
        detailMasterCpuUs.store(stages[5], std::memory_order_relaxed);
        detailOtherCpuUs.store(stages[6], std::memory_order_relaxed);
        detailSequence.fetch_add(1, std::memory_order_release); // even: published
    }
    void publishStage(uint32_t stage) noexcept { callbackStage.store(stage, std::memory_order_relaxed); }
};
