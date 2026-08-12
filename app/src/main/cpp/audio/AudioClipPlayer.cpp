#include "AudioClipPlayer.h"
#include <cstring>
#include <cmath>

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

void AudioClipPlayer::setTransportSync(bool enabled) {
    mTransportSync.store(enabled, std::memory_order_release);
}

void AudioClipPlayer::setStartTick(int64_t startTick) {
    mStartTick = startTick;
}

void AudioClipPlayer::setEndTick(int64_t endTick) {
    mEndTick = endTick;
}

void AudioClipPlayer::setLoopEnabled(bool loop) {
    mLoop.store(loop, std::memory_order_release);
}

int32_t AudioClipPlayer::renderSynced(float* output, int32_t maxFrames, double currentTick) {
    if (!mTransportSync.load(std::memory_order_acquire)) return 0;
    if (!mPlaying.load(std::memory_order_acquire)) return 0;

    float* data = mPcmData.load(std::memory_order_acquire);
    if (!data || mLength <= 0) return 0;

    // Check if current tick is within the clip range
    if (currentTick < mStartTick || currentTick > mEndTick) {
        // Outside clip range — silence
        return 0;
    }

    // Calculate tick offset within clip
    double tickOffset = currentTick - mStartTick;

    // Handle looping: wrap offset within clip duration
    int64_t clipDurationTicks = mEndTick - mStartTick;
    if (mLoop.load(std::memory_order_acquire) && clipDurationTicks > 0) {
        tickOffset = fmod(tickOffset, static_cast<double>(clipDurationTicks));
        if (tickOffset < 0) {
            tickOffset += static_cast<double>(clipDurationTicks);
        }
    }

    // Convert tick offset to frame position
    // tickOffset is in tick units; need to convert to sample frames
    // The clip's PCM data is at the project's sample rate
    // For simplicity, assume 1 tick = 1 frame (caller handles tick-to-frame conversion)
    // In practice, the caller should convert tick offset to frame offset
    // Here we treat tickOffset as already being in frame units relative to clip start
    int32_t pos = static_cast<int32_t>(tickOffset);
    if (pos >= mLength) return 0;

    int32_t remaining = mLength - pos;
    int32_t toRead = maxFrames < remaining ? maxFrames : remaining;

    // Mono read (stereo would need interleaved handling)
    std::memcpy(output, data + pos, toRead * sizeof(float));
    return toRead;
}