#pragma once

#include <atomic>
#include <cstdint>

// Per-track mixer with volume, pan, mute, solo, and peak meter
// All buffers pre-allocated in init() — safe for audio callback
class Mixer {
public:
    Mixer();
    ~Mixer();

    // Non-copyable
    Mixer(const Mixer&) = delete;
    Mixer& operator=(const Mixer&) = delete;

    // Initialize with track count and max frames per buffer
    void init(int trackCount, int maxFramesPerBuffer);

    // Set track parameters (atomic, safe for UI thread)
    void setVolume(int trackId, float volume);
    void setPan(int trackId, float pan);
    void setMute(int trackId, bool mute);
    void setSolo(int trackId, bool solo);

    // Mix tracks into stereo output buffer
    // Each track's mono data is already pre-multiplied by volume/pan
    // output: stereo float buffer (interleaved L/R), numFrames * 2 elements
    void mix(float* output, int numFrames);

    // Get peak meter (RMS) for track — atomic read, safe for UI thread
    float getPeakMeter(int trackId) const;

    // Get pre-allocated mono buffer for a track (for writing audio data)
    // Safe for audio callback — pointer is stable after init()
    float* getTrackBuffer(int trackId) const;

    // Reset all peak meters (call at start of playback)
    void resetMeters();

    // Get track count
    int getTrackCount() const { return mTrackCount; }

private:
    struct TrackState {
        std::atomic<float> volume{1.0f};
        std::atomic<float> pan{0.0f};
        std::atomic<bool> mute{false};
        std::atomic<bool> solo{false};
        std::atomic<float> peakMeter{0.0f};
        float* buffer = nullptr;  // Pre-allocated mono buffer
    };

    static constexpr int kMaxTracks = 16;
    TrackState mTracks[kMaxTracks];
    int mTrackCount = 0;
    int mMaxFrames = 0;
    float* mOutputBuffer = nullptr;  // Pre-allocated stereo buffer
};