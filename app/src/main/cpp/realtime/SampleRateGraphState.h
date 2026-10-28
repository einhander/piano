#pragma once

#include <atomic>
#include <cstdint>

// Worker-published graph readiness and retry state. Callback reads readiness
// once before touching any sample-rate-dependent component.
class SampleRateGraphState {
public:
    void begin(int rate) noexcept {
        mReady.store(false, std::memory_order_release);
        mStatus.store(1, std::memory_order_release);
        mPendingRate.store(rate, std::memory_order_release);
    }
    void fail(int status) noexcept { mStatus.store(status, std::memory_order_release); }
    void complete() noexcept {
        mPendingRate.store(0, std::memory_order_release);
        mStatus.store(0, std::memory_order_release);
        mReady.store(true, std::memory_order_release);
    }
    void initialize(bool ready, int status) noexcept {
        mStatus.store(status, std::memory_order_relaxed);
        mReady.store(ready, std::memory_order_release);
    }
    bool ready() const noexcept { return mReady.load(std::memory_order_acquire); }
    int status() const noexcept { return mStatus.load(std::memory_order_acquire); }
    int pendingRate() const noexcept { return mPendingRate.load(std::memory_order_acquire); }
private:
    std::atomic<bool> mReady{false};
    std::atomic<int32_t> mStatus{1};
    std::atomic<int32_t> mPendingRate{0};
};

using SampleRatePrepareStage = bool (*)(void*, int);
using SampleRateActivateStage = void (*)(void*, int);

// Actual finalizer used by NativeEngine under FluidSynth's callback gate.
// No rate is published until both fallible preparations complete.
inline bool finalizeSampleRateGraph(SampleRateGraphState& state, int rate, void* context,
                                   SampleRatePrepareStage inserts,
                                   SampleRatePrepareStage delay,
                                   SampleRateActivateStage activate) noexcept {
    try {
        if (!inserts || !inserts(context, rate)) { state.fail(3); return false; }
        if (!delay || !delay(context, rate)) { state.fail(4); return false; }
        if (!activate) { state.fail(5); return false; }
        activate(context, rate);
        state.complete();
        return true;
    } catch (...) {
        state.fail(5);
        return false;
    }
}
