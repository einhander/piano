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

    // Get peak meter (RMS) — atomic read, safe for UI thread
    float getPeakMeter() const;

    // Reset peak meter
    void resetMeter();

private:
    std::atomic<float> mVolume{1.0f};
    std::atomic<float> mPeakMeter{0.0f};
    float* mBuffer = nullptr;
    int mMaxFrames = 0;
};