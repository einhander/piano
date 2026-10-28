#pragma once

#include <atomic>
#include <cstdint>
#include <thread>

// Single-attempt callback admission gate. Worker close/wait is blocking;
// callback tryEnter is one lock-free atomic attempt and never waits.
class CallbackAdmissionGate {
public:
    static constexpr uint32_t kGateBit = uint32_t{1} << 31;
    static constexpr uint32_t kReaderMask = ~kGateBit;

    bool tryEnter() noexcept {
        uint32_t state = mState.load(std::memory_order_relaxed);
        if (state & kGateBit) return false;
        return mState.compare_exchange_strong(state, state + 1,
            std::memory_order_acquire, std::memory_order_relaxed);
    }
    void leave() noexcept { mState.fetch_sub(1, std::memory_order_release); }
    void closeAndWait() noexcept {
        mState.fetch_or(kGateBit, std::memory_order_acq_rel);
        while ((mState.load(std::memory_order_acquire) & kReaderMask) != 0)
            std::this_thread::yield();
    }
    void open() noexcept { mState.fetch_and(kReaderMask, std::memory_order_release); }

private:
    std::atomic<uint32_t> mState{0};
};
