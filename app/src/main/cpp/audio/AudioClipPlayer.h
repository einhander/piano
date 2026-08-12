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

    // Transport sync
    void setTransportSync(bool enabled);
    void setStartTick(int64_t startTick);
    void setEndTick(int64_t endTick);
    void setLoopEnabled(bool loop);

    // Called from audio callback with current transport tick
    // Returns number of frames written to output (stereo float)
    int32_t renderSynced(float* output, int32_t maxFrames, double currentTick);

    bool isSynced() const { return mTransportSync.load(); }

private:
    std::atomic<bool> mPlaying{false};
    int32_t mLength = 0;
    std::atomic<float*> mPcmData{nullptr};  // Atomic for safe concurrent read/write

    // Transport sync state
    std::atomic<bool> mTransportSync{false};
    std::atomic<bool> mLoop{false};
    int64_t mStartTick = 0;
    int64_t mEndTick = 0;
};