#pragma once

#include <cstdint>
#include <atomic>

// Audio clip player — reads PCM data from linear buffer
// MVP: supports WAV PCM only
class AudioClipPlayer {
public:
    AudioClipPlayer();
    ~AudioClipPlayer();

    bool load(const float* pcmData, int32_t numFrames);
    // Read from a caller-supplied startFrame (global time source).
    // AudioClipPlayer is a passive buffer — no internal position counter.
    int32_t read(float* output, int32_t maxFrames, int32_t startFrame);
    bool isPlaying() const { return mPlaying.load(); }
    void play();
    void stop();
    int32_t length() const { return mLength; }

private:
    std::atomic<bool> mPlaying{false};
    int32_t mLength = 0;
    std::atomic<float*> mPcmData{nullptr};  // Atomic for safe concurrent read/write
};