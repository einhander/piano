#pragma once

#include <atomic>
#include <cstdint>

// Single audio-thread producer, single diagnostic worker consumer. Window
// maxima are independently exchanged, so snapshot fields are approximate.
struct CallbackTiming {
    static_assert(std::atomic<uint32_t>::is_always_lock_free,
                  "Callback diagnostic counters must remain lock-free");
    std::atomic<uint32_t> count{0}, frames{0}, maxWallUs{0}, maxGapUs{0}, overBudget{0};
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
};
