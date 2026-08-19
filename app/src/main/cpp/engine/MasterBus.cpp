#include "MasterBus.h"
#include <cmath>
#include <cstring>

MasterBus::MasterBus() {}
MasterBus::~MasterBus() {
    if (mBuffer) {
        delete[] mBuffer;
        mBuffer = nullptr;
    }
}

void MasterBus::init(int maxFramesPerBuffer) {
    if (maxFramesPerBuffer < 1) {
        maxFramesPerBuffer = 512;
    }
    mMaxFrames = maxFramesPerBuffer;
    mBuffer = new float[maxFramesPerBuffer * 2];
    std::memset(mBuffer, 0, maxFramesPerBuffer * 2 * sizeof(float));
}

void MasterBus::setVolume(float volume) {
    if (volume < 0.0f) volume = 0.0f;
    if (volume > 1.0f) volume = 1.0f;
    mVolume.store(volume);
}

// Fast tanh approximation for the soft clipper (Fix #6). Replaces the two
// std::tanh calls per frame (each uses exp internally) with a cheap rational
// approximation: tanh(x) ≈ x*(1 + b*x^2)/(1 + c*x^2), fit so that the value
// and slope match at x=0 and the value matches at x=1 and x=2. Measured max
// error ~0.006 at x=2.5 (≤0.001 for |x|≤1.5, the actual input range after the
// ×1.5 clip factor). The result is clamped to [-1, 1] BEFORE the ×normalize
// gain (1.10479 = 1/tanh(1.5)), so the final output can reach ~1.1048 (0.87
// dB); the HAL hard-clips at ±1.0.
static inline float fastTanh(float x) {
    float x2 = x * x;
    float r = x * (1.0f + 0.0582f * x2) / (1.0f + 0.3895f * x2);
    if (r > 1.0f) return 1.0f;
    if (r < -1.0f) return -1.0f;
    return r;
}

void MasterBus::process(float* output, int numFrames) {
    if (numFrames > mMaxFrames) {
        numFrames = mMaxFrames;
    }
    if (numFrames <= 0) return;

    float vol = mVolume.load();
    float peakRms = 0.0f;

    // Soft clipper constant: tanh(1.5) normalized
    // output = tanh(input * 1.5) / tanh(1.5)
    constexpr float clipFactor = 1.5f;
    // 1.0f / tanh(1.5f) — precomputed (tanh(1.5) = 0.905148211).
    constexpr float normalize = 1.10479f;

    for (int i = 0; i < numFrames; i++) {
        // Process left channel
        float left = output[i * 2] * vol;
        left = fastTanh(left * clipFactor) * normalize;

        // Process right channel
        float right = output[i * 2 + 1] * vol;
        right = fastTanh(right * clipFactor) * normalize;

        // RMS peak calculation. Fix #7: decimate every 4th frame (metering
        // only — does not affect the audio output).
        if ((i & 3) == 0) {
            peakRms += left * left + right * right;
        }

        output[i * 2] = left;
        output[i * 2 + 1] = right;
    }

    // Store peak RMS. Decimated: only ~numFrames/4 frames were summed, so
    // scale by the decimation factor (4) to estimate the full sum.
    peakRms = std::sqrt((4.0f * peakRms) / (numFrames * 2));
    float currentPeak = mPeakMeter.load();
    if (peakRms > currentPeak) {
        mPeakMeter.store(peakRms);
    }
}

float MasterBus::getPeakMeter() const {
    return mPeakMeter.load();
}

void MasterBus::resetMeter() {
    mPeakMeter.store(0.0f);
}