#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <vector>

// Original, bounded stereo feedback delay for shared-send/return routing.
// prepare() allocates on a worker while audio is quiescent. process() is
// allocation-free and callback-owned. It consumes send only (never dry audio).
class SharedStereoDelay {
public:
    static constexpr float kMaxFeedback = 0.90f;
    static constexpr float kMaxWet = 1.0f;
    static constexpr float kMaxTimeMs = 2000.0f;
    // Internal guard against unbounded pathological input / feedback accumulation.
    static constexpr float kMaxInternalSample = 16.0f;

    // Allocates exactly two seconds per channel plus guard frames. Call only
    // while callback is quiescent; maxFrames retained as validated contract.
    bool prepare(double sampleRate, int maxFrames);

    // Inputs are mono-channel send planes; outputs are wet-only planes. Always
    // call for each audio segment, including silence, to keep delay tails live.
    void process(const float* sendL, const float* sendR,
                 float* wetL, float* wetR, int frames, double bpm) noexcept;

    void setEnabled(bool enabled) noexcept { mEnabled.store(enabled, std::memory_order_relaxed); }
    bool isEnabled() const noexcept { return mEnabled.load(std::memory_order_relaxed); }
    void setTimeMs(float value) noexcept;
    float timeMs() const noexcept { return mTimeMs.load(std::memory_order_relaxed); }
    void setFeedback(float value) noexcept;
    float feedback() const noexcept { return mFeedback.load(std::memory_order_relaxed); }
    void setWet(float value) noexcept;
    float wet() const noexcept { return mWet.load(std::memory_order_relaxed); }
    void setSync(bool enabled) noexcept { mSync.store(enabled, std::memory_order_relaxed); }
    bool sync() const noexcept { return mSync.load(std::memory_order_relaxed); }
    // division: 0=quarter, 1=eighth, 2=dotted eighth, 3=sixteenth,
    // 4=eighth-note triplet.
    void setDivision(int division) noexcept;
    int division() const noexcept { return mDivision.load(std::memory_order_relaxed); }
    // Explicit reset is asynchronous. Callback clears at most 2 seconds/channel.
    void requestReset() noexcept { mResetRequested.store(true, std::memory_order_release); }

private:
    static float finiteClamp(float value, float fallback, float lo, float hi) noexcept;
    float desiredDelayMs(double bpm) const noexcept;
    void beginTimeTransition(float delayFrames) noexcept;

    std::vector<float> mLeft, mRight;
    std::vector<uint64_t> mLeftGeneration, mRightGeneration;
    std::size_t mCapacity = 0;
    std::size_t mWrite = 0;
    uint64_t mGeneration = 1; // callback-owned lazy-clear epoch
    double mSampleRate = 0.0;
    int mMaxFrames = 0;
    std::atomic<bool> mEnabled{false};
    std::atomic<float> mTimeMs{250.0f};
    std::atomic<float> mFeedback{0.35f};
    std::atomic<float> mWet{0.30f};
    std::atomic<bool> mSync{false};
    std::atomic<int> mDivision{0};
    std::atomic<bool> mResetRequested{false};

    // Audio-thread state only.
    float mCurrentDelayFrames = 1.0f;
    float mOldDelayFrames = 1.0f;
    float mNewDelayFrames = 1.0f;
    int mTimeFadeRemaining = 0;
    float mInputGain = 0.0f;
    float mFeedbackState = 0.35f;
    float mWetState = 0.30f;
    bool mEnabledState = false;
    float mTimeFadeProgress = 0.0f;
    static constexpr float kSlewPerSample = 1.0f / 128.0f;
    static constexpr float kTimeFadeProgressPerSample = 1.0f / 256.0f;
};

// Production routing primitive: sum the 16 post-insert stereo buses at frame
// into one shared send. Channel pointers address interleaved stereo buffers.
void sumSharedDelaySends(const float* const channelBuses[16], const float levels[16],
                         int frame, float& left, float& right) noexcept;
