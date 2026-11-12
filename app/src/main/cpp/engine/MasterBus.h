#pragma once

#include <atomic>
#include <cstdint>

// Master output bus with volume control, soft clipper, and peak meter
// All buffers pre-allocated in init() — safe for audio callback
class MasterBus {
public:
    MasterBus();
    ~MasterBus();

    // Non-copyable
    MasterBus(const MasterBus&) = delete;
    MasterBus& operator=(const MasterBus&) = delete;

    // Initialize with max frames per buffer
    void init(int maxFramesPerBuffer);

    // Set master volume (atomic, safe for UI thread)
    void setVolume(float volume);

    // Process stereo float buffer
    // Applies volume + soft clipping (tanh-based limiter)
    // output: stereo float buffer (interleaved L/R), numFrames * 2 elements
    void process(float* output, int numFrames);

    // Max sample peak and max per-block stereo RMS, held until explicit reset.
    float getPeakMeter() const;
    float getBlockRms() const;
    uint32_t getPreLimiterClipSamples() const;
    uint32_t getNonFiniteSamples() const;

    // Reset peak meter
    void resetMeter();

private:
    std::atomic<float> mVolume{1.0f};
    std::atomic<float> mSamplePeak{0.0f};
    std::atomic<float> mBlockRms{0.0f};
    // Saturating lifetime counts since reset; UINT32_MAX means saturated.
    std::atomic<uint32_t> mPreLimiterClipSamples{0};
    std::atomic<uint32_t> mNonFiniteSamples{0};
    float* mBuffer = nullptr;
    int mMaxFrames = 0;
};
