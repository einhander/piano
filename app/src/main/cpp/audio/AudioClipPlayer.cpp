#include "AudioClipPlayer.h"
#include <cstring>

AudioClipPlayer::AudioClipPlayer() = default;
AudioClipPlayer::~AudioClipPlayer() {
    delete[] mPcmData.load(std::memory_order_acquire);
}

bool AudioClipPlayer::load(const float* pcmData, int32_t numFrames) {
    int32_t newLength = numFrames;
    float* newBuffer = new float[newLength];
    std::memcpy(newBuffer, pcmData, newLength * sizeof(float));

    // Atomic swap — old buffer freed after all readers are done
    float* old = mPcmData.exchange(newBuffer, std::memory_order_acq_rel);
    mLength = newLength;
    delete[] old;
    return true;
}

int32_t AudioClipPlayer::read(float* output, int32_t maxFrames, int32_t startFrame) {
    if (!mPlaying.load(std::memory_order_acquire)) return 0;
    float* data = mPcmData.load(std::memory_order_acquire);
    if (!data || mLength <= 0) return 0;
    // Position is determined by caller (startFrame), not internal counter
    int32_t pos = startFrame;
    if (pos >= mLength) return 0;
    int32_t remaining = mLength - pos;
    int32_t toRead = maxFrames < remaining ? maxFrames : remaining;
    std::memcpy(output, data + pos, toRead * sizeof(float));
    return toRead;
}

void AudioClipPlayer::play() {
    mPlaying.store(true);
}

void AudioClipPlayer::stop() {
    mPlaying.store(false);
}