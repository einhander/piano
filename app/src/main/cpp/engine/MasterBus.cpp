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
    const float normalize = 1.0f / std::tanh(clipFactor);

    for (int i = 0; i < numFrames; i++) {
        // Process left channel
        float left = output[i * 2] * vol;
        left = std::tanh(left * clipFactor) * normalize;

        // Process right channel
        float right = output[i * 2 + 1] * vol;
        right = std::tanh(right * clipFactor) * normalize;

        // RMS peak calculation
        peakRms += left * left + right * right;

        output[i * 2] = left;
        output[i * 2 + 1] = right;
    }

    // Store peak RMS
    peakRms = std::sqrt(peakRms / (numFrames * 2));
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