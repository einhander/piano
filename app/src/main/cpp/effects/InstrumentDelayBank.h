#pragma once

#include "SharedStereoDelay.h"
#include <atomic>

// Sixteen independent bounded delay inserts, one per MIDI channel. Prepare on
// worker while audio is quiescent. process() is callback-safe and allocation-free.
class InstrumentDelayBank {
public:
    static constexpr int kChannels = 16;
    static constexpr int kParameterCount = 6;
    bool prepare(double sampleRate, int maxFrames) noexcept;
    bool prepared() const noexcept { return mPrepared; }
    bool setParameter(int channel, int id, float value) noexcept;
    float getParameter(int channel, int id) const noexcept;
    bool getParameterInfo(int id, float& minimum, float& maximum,
                          float& defaultValue, bool& integer, bool& toggled) const noexcept;
    void process(float* const channelBuses[kChannels], int frames, double bpm) noexcept;
    void reset(int channel) noexcept;
private:
    SharedStereoDelay mDelays[kChannels];
    std::atomic<bool> mPrepared{false};
    int mMaxFrames = 0;
};
