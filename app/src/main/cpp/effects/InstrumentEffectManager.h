#pragma once

#include "AudioEffect.h"
#include <array>
#include <memory>
#include <atomic>

namespace piano {

// Worker-thread configured, callback-thread processed channel effect racks.
// Instances are built lazily and remain immutable until manager destruction.
class InstrumentEffectManager {
public:
    using EffectFactory = std::unique_ptr<AudioEffect> (*)(int slot);
    enum class PrepareStatus : uint8_t { Unprepared, Ready, InvalidArguments, AllocationFailed, ReprepareRequiresQuiescence };
    static constexpr int kChannels = 16;
    static constexpr int kEffects = 3;
    explicit InstrumentEffectManager(EffectFactory factory = nullptr) noexcept;
    ~InstrumentEffectManager();
    InstrumentEffectManager(const InstrumentEffectManager&) = delete;
    InstrumentEffectManager& operator=(const InstrumentEffectManager&) = delete;

    bool prepare(double sampleRate, int maxFrames);
    // Worker-thread only. Caller must stop callbacks for entire call; waits for
    // concurrent control-thread instantiate/parameter setup to finish.
    bool reprepareQuiescent(double sampleRate, int maxFrames);
    PrepareStatus prepareStatus() const noexcept { return mPrepareStatus.load(std::memory_order_acquire); }
    // Request a channel/slot. Factory and prepare run on caller (control) thread.
    bool instantiate(int channel, int slot);
    bool rackEnabled(int channel) const noexcept;
    bool effectEnabled(int channel, int slot) const noexcept;
    bool setEffectEnabledChecked(int channel, int slot, bool enabled);
    bool setParameterChecked(int channel, int slot, uint32_t id, float value) noexcept;
    bool getParameterChecked(int channel, int slot, uint32_t id, float& value) const noexcept;
    int paramCount(int slot) const noexcept;
    bool paramInfo(int slot, int index, uint32_t& id, float& minValue, float& maxValue,
                   float& defaultValue, bool& logarithmic, bool& integer, bool& toggled) const noexcept;
    const char* paramName(int slot, int index) const noexcept;
    bool setParameterBeforeInstantiate(int channel, int slot, uint32_t id, float value) noexcept;
    void setRackEnabled(int channel, bool enabled) noexcept;
    void setEffectEnabled(int channel, int slot, bool enabled) noexcept;
    void setParameter(int channel, int slot, uint32_t id, float value) noexcept;
    float getParameter(int channel, int slot, uint32_t id) const noexcept;
    bool isAvailable(int channel, int slot) const noexcept;
    void process(float* const buses[kChannels], int frames) noexcept;

private:
    std::array<std::array<std::unique_ptr<AudioEffect>, kEffects>, kChannels> mEffects{};
    std::array<std::array<std::atomic<AudioEffect*>, kEffects>, kChannels> mPublished{};
    std::array<std::atomic<bool>, kChannels> mRackEnabled{};
    std::array<std::array<std::atomic<bool>, kEffects>, kChannels> mEffectEnabled{};
    std::array<std::array<std::array<std::atomic<float>, 512>, kEffects>, kChannels> mPending{};
    std::array<std::array<std::array<std::atomic<bool>, 512>, kEffects>, kChannels> mPendingSet{};
    // Callback-thread-owned wet gains; deliberately non-atomic.
    std::array<std::array<float, kEffects>, kChannels> mWetMix{};
    std::array<float*, kChannels> mLeft{}, mRight{};
    double mSampleRate = 0.0;
    int mMaxFrames = 0;
    bool mPrepared = false;
    std::atomic<PrepareStatus> mPrepareStatus{PrepareStatus::Unprepared};
    std::atomic_flag mControlBusy = ATOMIC_FLAG_INIT;
    EffectFactory mFactory = nullptr;
};
}
