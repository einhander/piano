#include "MasterBus.h"
#include <algorithm>
#include <cmath>
#include <limits>

static_assert(std::atomic<float>::is_always_lock_free, "audio meters require lock-free float atomics");
static_assert(std::atomic<uint32_t>::is_always_lock_free, "audio counters require lock-free 32-bit atomics");

MasterBus::MasterBus() = default;
MasterBus::~MasterBus() { delete[] mBuffer; }

void MasterBus::init(int maxFramesPerBuffer) {
    delete[] mBuffer;
    mMaxFrames = std::max(maxFramesPerBuffer, 1);
    mBuffer = new float[static_cast<size_t>(mMaxFrames) * 2]{};
}

void MasterBus::setVolume(float volume) {
    if (!std::isfinite(volume)) volume = 0.0f;
    mVolume.store(std::clamp(volume, 0.0f, 1.0f));
}

static inline float fastTanh(float x) {
    // Bound before squaring: avoids overflow for huge finite inputs. Non-finite
    // inputs are handled by process() and never reach this approximation.
    x = std::clamp(x, -4.0f, 4.0f);
    const float x2 = x * x;
    const float r = x * (1.0f + 0.0582f * x2) / (1.0f + 0.3895f * x2);
    return std::clamp(r, -1.0f, 1.0f);
}

static void publishMax(std::atomic<float>& target, float value) {
    float old = target.load(std::memory_order_relaxed);
    while (value > old && !target.compare_exchange_weak(old, value, std::memory_order_relaxed)) {}
}
static void addSaturating(std::atomic<uint32_t>& target, uint32_t amount) {
    uint32_t old = target.load(std::memory_order_relaxed);
    while (old != UINT32_MAX) {
        const uint32_t next = amount > UINT32_MAX - old ? UINT32_MAX : old + amount;
        if (target.compare_exchange_weak(old, next, std::memory_order_relaxed)) break;
    }
}

void MasterBus::process(float* output, int numFrames) {
    if (!output || numFrames <= 0) return;
    const float vol = mVolume.load(std::memory_order_relaxed);
    float peak = 0.0f;
    double sumSquares = 0.0;
    uint32_t clips = 0, nonFinite = 0;
    for (int i = 0; i < numFrames * 2; ++i) {
        float sample = output[i];
        if (!std::isfinite(sample)) {
            ++nonFinite;
            output[i] = 0.0f;
            continue;
        }
        sample *= vol;
        if (!std::isfinite(sample)) { ++nonFinite; output[i] = 0.0f; continue; }
        if (std::fabs(sample) > 1.0f) ++clips;
        const float softened = fastTanh(sample * 1.5f);
        sample = std::isfinite(softened) ? std::clamp(softened, -1.0f, 1.0f) : 0.0f;
        output[i] = sample;
        peak = std::max(peak, std::fabs(sample));
        sumSquares += static_cast<double>(sample) * sample;
    }
    const float rms = static_cast<float>(std::sqrt(sumSquares / (numFrames * 2.0)));
    publishMax(mSamplePeak, peak);
    publishMax(mBlockRms, rms);
    addSaturating(mPreLimiterClipSamples, clips);
    addSaturating(mNonFiniteSamples, nonFinite);
}

float MasterBus::getPeakMeter() const { return mSamplePeak.load(std::memory_order_relaxed); }
float MasterBus::getBlockRms() const { return mBlockRms.load(std::memory_order_relaxed); }
uint32_t MasterBus::getPreLimiterClipSamples() const { return mPreLimiterClipSamples.load(std::memory_order_relaxed); }
uint32_t MasterBus::getNonFiniteSamples() const { return mNonFiniteSamples.load(std::memory_order_relaxed); }
void MasterBus::resetMeter() {
    mSamplePeak.store(0.0f, std::memory_order_relaxed);
    mBlockRms.store(0.0f, std::memory_order_relaxed);
    mPreLimiterClipSamples.store(0, std::memory_order_relaxed);
    mNonFiniteSamples.store(0, std::memory_order_relaxed);
}
