#pragma once

#include "SharedStereoDelay.h"
#include "../synth/InstrumentChannelContract.h"
#include <array>
#include <atomic>
#include <memory>
#include <mutex>

// Worker owns instances. prepare/resetChannelRange require callback quiescence;
// setParameter/getParameter may run concurrently with process().
class InstrumentDelayBank {
public:
    static constexpr int kChannels = piano::kInstrumentChannelCount;
    static constexpr int kParameterCount = 6;
    bool prepare(double sampleRate, int maxFrames) noexcept;
    bool prepared() const noexcept { return mPrepared.load(std::memory_order_acquire); }
    bool setParameter(int channel, int id, float value) noexcept;
    float getParameter(int channel, int id) const noexcept;
    bool getParameterInfo(int id, float& minimum, float& maximum,
                          float& defaultValue, bool& integer, bool& toggled) const noexcept;
    void process(float* const channelBuses[kChannels], int frames, double bpm) noexcept;
    void reset(int channel) noexcept;
    // Requires callback quiescence. Clears controls and reclaims instances.
    bool resetChannelRange(int first, int count) noexcept;
    int allocatedCount() const noexcept;
    size_t allocatedBytes() const noexcept;
private:
    struct Targets {
        std::atomic<float> values[kParameterCount];
        Targets() noexcept;
    };
    static float defaultValue(int id) noexcept;
    static void applyTargets(SharedStereoDelay& delay, const Targets& targets) noexcept;
    mutable std::mutex mControlMutex;
    std::array<std::unique_ptr<SharedStereoDelay>, kChannels> mOwned{};
    std::array<std::atomic<SharedStereoDelay*>, kChannels> mPublished{};
    std::array<Targets, kChannels> mTargets{};
    std::atomic<bool> mPrepared{false};
    double mSampleRate = 0;
    int mMaxFrames = 0;
};
