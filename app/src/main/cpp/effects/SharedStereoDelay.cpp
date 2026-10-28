#include "SharedStereoDelay.h"

#include <algorithm>
#include <cmath>

float SharedStereoDelay::finiteClamp(float value, float fallback, float lo, float hi) noexcept {
    if (!std::isfinite(value)) value = fallback;
    return std::max(lo, std::min(hi, value));
}

bool SharedStereoDelay::prepare(double sampleRate, int maxFrames) {
    // Keep size arithmetic representable and reject absurd rates/capacities.
    if (!std::isfinite(sampleRate) || sampleRate < 1.0 || sampleRate > 384000.0 ||
        maxFrames < 1 || maxFrames > 65536) return false;
    const auto capacity = static_cast<std::size_t>(std::ceil(sampleRate * 2.0)) + 2;
    try {
        std::vector<float> left(capacity, 0.0f), right(capacity, 0.0f);
        mLeft.swap(left);
        mRight.swap(right);
    } catch (...) {
        return false;
    }
    mCapacity = capacity;
    mSampleRate = sampleRate;
    mMaxFrames = maxFrames;
    mWrite = 0;
    mCurrentDelayFrames = mOldDelayFrames = mNewDelayFrames =
        std::max(1.0f, std::min(static_cast<float>(sampleRate * 2.0),
                               timeMs() * static_cast<float>(sampleRate / 1000.0)));
    mTimeFadeRemaining = 0;
    mInputGain = 0.0f;
    mWetState = wet();
    mFeedbackState = feedback();
    mEnabledState = false;
    mTimeFadeProgress = 0.0f;
    return true;
}

void SharedStereoDelay::setTimeMs(float value) noexcept {
    mTimeMs.store(finiteClamp(value, 250.0f, 1.0f, kMaxTimeMs), std::memory_order_relaxed);
}
void SharedStereoDelay::setFeedback(float value) noexcept {
    mFeedback.store(finiteClamp(value, 0.35f, 0.0f, kMaxFeedback), std::memory_order_relaxed);
}
void SharedStereoDelay::setWet(float value) noexcept {
    mWet.store(finiteClamp(value, 0.30f, 0.0f, kMaxWet), std::memory_order_relaxed);
}
void SharedStereoDelay::setDivision(int value) noexcept {
    mDivision.store(std::max(0, std::min(4, value)), std::memory_order_relaxed);
}

float SharedStereoDelay::desiredDelayMs(double bpm) const noexcept {
    float ms = mTimeMs.load(std::memory_order_relaxed);
    if (mSync.load(std::memory_order_relaxed)) {
        if (!std::isfinite(bpm) || bpm <= 0.0) bpm = 120.0;
        bpm = std::min(bpm, 1000.0);
        static constexpr double factors[] = {1.0, 0.5, 0.75, 0.25, 1.0 / 3.0};
        const int d = std::max(0, std::min(4, mDivision.load(std::memory_order_relaxed)));
        ms = static_cast<float>((60000.0 / bpm) * factors[d]);
    }
    return finiteClamp(ms, 250.0f, 1.0f, kMaxTimeMs);
}

void SharedStereoDelay::beginTimeTransition(float delayFrames) noexcept {
    delayFrames = std::max(1.0f, std::min(delayFrames, static_cast<float>(mCapacity - 2)));
    if (std::fabs(delayFrames - mNewDelayFrames) < 0.5f) return;
    mOldDelayFrames = mCurrentDelayFrames;
    mNewDelayFrames = delayFrames;
    mTimeFadeProgress = 0.0f;
    mTimeFadeRemaining = static_cast<int>(1.0f / kTimeFadeProgressPerSample);
}

void SharedStereoDelay::process(const float* sendL, const float* sendR,
                                float* wetL, float* wetR, int frames, double bpm) noexcept {
    if (frames <= 0 || !wetL || !wetR || mCapacity < 4) return;
    // prepare(maxFrames) establishes caller contract; reject oversized chunks
    // rather than silently running an unexpected callback workload.
    if (frames > mMaxFrames) {
        std::fill(wetL, wetL + frames, 0.0f);
        std::fill(wetR, wetR + frames, 0.0f);
        return;
    }
    if (mResetRequested.exchange(false, std::memory_order_acq_rel)) {
        std::fill(mLeft.begin(), mLeft.end(), 0.0f);
        std::fill(mRight.begin(), mRight.end(), 0.0f);
        mWrite = 0;
        mTimeFadeRemaining = 0;
        mTimeFadeProgress = 0.0f;
        mCurrentDelayFrames = mNewDelayFrames;
    }

    beginTimeTransition(desiredDelayMs(bpm) * static_cast<float>(mSampleRate / 1000.0));
    const bool enabled = mEnabled.load(std::memory_order_relaxed);
    const float targetFeedback = mFeedback.load(std::memory_order_relaxed);
    const float targetWet = mWet.load(std::memory_order_relaxed);
    const float enableTarget = enabled ? 1.0f : 0.0f;

    for (int i = 0; i < frames; ++i) {
        auto slew = [](float current, float target) noexcept {
            const float delta = target - current;
            return current + std::max(-kSlewPerSample, std::min(kSlewPerSample, delta));
        };
        mWetState = slew(mWetState, targetWet);
        mFeedbackState = slew(mFeedbackState, targetFeedback);
        mInputGain = slew(mInputGain, enableTarget);
        const float rawL = sendL && std::isfinite(sendL[i]) ? sendL[i] : 0.0f;
        const float rawR = sendR && std::isfinite(sendR[i]) ? sendR[i] : 0.0f;
        const float inL = finiteClamp(rawL * mInputGain, 0.0f, -kMaxInternalSample, kMaxInternalSample);
        const float inR = finiteClamp(rawR * mInputGain, 0.0f, -kMaxInternalSample, kMaxInternalSample);
        auto read = [this](const std::vector<float>& ring, float delay) noexcept {
            float position = static_cast<float>(mWrite) - delay;
            while (position < 0.0f) position += static_cast<float>(mCapacity);
            const auto i0 = static_cast<std::size_t>(position) % mCapacity;
            const auto i1 = (i0 + 1) % mCapacity;
            const float frac = position - std::floor(position);
            return ring[i0] + (ring[i1] - ring[i0]) * frac;
        };
        float oldL = read(mLeft, mOldDelayFrames), oldR = read(mRight, mOldDelayFrames);
        float delayedL = oldL, delayedR = oldR;
        if (mTimeFadeRemaining > 0) {
            const float newL = read(mLeft, mNewDelayFrames), newR = read(mRight, mNewDelayFrames);
            const float blend = mTimeFadeProgress;
            delayedL = oldL + (newL - oldL) * blend;
            delayedR = oldR + (newR - oldR) * blend;
            mTimeFadeProgress = std::min(1.0f, mTimeFadeProgress + kTimeFadeProgressPerSample);
            mTimeFadeRemaining = static_cast<int>((1.0f - mTimeFadeProgress) / kTimeFadeProgressPerSample);
            if (mTimeFadeProgress >= 1.0f) {
                mTimeFadeRemaining = 0;
                mCurrentDelayFrames = mOldDelayFrames = mNewDelayFrames;
            }
        } else {
            mOldDelayFrames = mNewDelayFrames;
            mCurrentDelayFrames = mNewDelayFrames;
        }
        const float fb = finiteClamp(mFeedbackState, 0.0f, 0.0f, kMaxFeedback);
        mLeft[mWrite] = finiteClamp(inL + delayedL * fb, 0.0f, -kMaxInternalSample, kMaxInternalSample);
        mRight[mWrite] = finiteClamp(inR + delayedR * fb, 0.0f, -kMaxInternalSample, kMaxInternalSample);
        wetL[i] = finiteClamp(delayedL * finiteClamp(mWetState, 0.0f, 0.0f, kMaxWet),
                              0.0f, -kMaxInternalSample, kMaxInternalSample);
        wetR[i] = finiteClamp(delayedR * finiteClamp(mWetState, 0.0f, 0.0f, kMaxWet),
                              0.0f, -kMaxInternalSample, kMaxInternalSample);
        if (++mWrite == mCapacity) mWrite = 0;
    }
}
