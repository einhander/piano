#pragma once

#include <array>
#include <atomic>
#include <cstdint>
#include <type_traits>

namespace piano {

// Fixed-capacity SPSC ring of interleaved stereo frames. One audio producer,
// one worker consumer. Counter subtraction remains valid across uint32 wrap.
template <uint32_t Capacity = 8192>
class SpectrumCaptureRing {
    static_assert(Capacity != 0 && (Capacity & (Capacity - 1)) == 0,
                  "capacity must be a power of two");
    static_assert(std::atomic<uint32_t>::is_always_lock_free,
                  "callback counters must be lock-free");
public:
    static constexpr uint32_t kCapacity = Capacity;

    bool write(const float* stereo, uint32_t frames) noexcept {
        const uint32_t w = mWrite.load(std::memory_order_relaxed);
        const uint32_t r = mRead.load(std::memory_order_acquire);
        uint32_t n = frames;
        const uint32_t free = Capacity - (w - r);
        if (n > free) n = free;
        for (uint32_t i = 0; i < n; ++i) {
            const uint32_t p = ((w + i) & (Capacity - 1)) * 2;
            mSamples[p] = stereo[i * 2];
            mSamples[p + 1] = stereo[i * 2 + 1];
        }
        mWrite.store(w + n, std::memory_order_release);
        if (n != frames) mDropped.fetch_add(frames - n, std::memory_order_relaxed);
        return n == frames;
    }
    uint32_t read(float* out, uint32_t frames) noexcept {
        const uint32_t r = mRead.load(std::memory_order_relaxed);
        const uint32_t w = mWrite.load(std::memory_order_acquire);
        uint32_t n = w - r;
        if (n > frames) n = frames;
        for (uint32_t i = 0; i < n; ++i) {
            const uint32_t p = ((r + i) & (Capacity - 1)) * 2;
            out[i * 2] = mSamples[p];
            out[i * 2 + 1] = mSamples[p + 1];
        }
        mRead.store(r + n, std::memory_order_release);
        return n;
    }
    // Consumer only; use before enabling to discard stale frames.
    void clear() noexcept { mRead.store(mWrite.load(std::memory_order_acquire), std::memory_order_release); }
    uint32_t dropped() const noexcept { return mDropped.load(std::memory_order_relaxed); }
private:
    alignas(64) std::array<float, Capacity * 2> mSamples{};
    alignas(64) std::atomic<uint32_t> mWrite{0};
    alignas(64) std::atomic<uint32_t> mRead{0};
    std::atomic<uint32_t> mDropped{0};
};
}
