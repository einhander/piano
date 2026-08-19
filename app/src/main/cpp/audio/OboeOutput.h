#pragma once

#include <oboe/Oboe.h>
#include <atomic>
#include <cstdint>
#include <memory>

class OboeOutput : public oboe::AudioStreamCallback {
public:
    OboeOutput();
    ~OboeOutput();

    OboeOutput(const OboeOutput&) = delete;
    OboeOutput& operator=(const OboeOutput&) = delete;

    oboe::Result open();
    void close();
    oboe::Result start();
    oboe::Result stop();

    static OboeOutput* getInstance() { return sInstance.load(std::memory_order_acquire); }

    bool isOpen() const { return mStream != nullptr; }
    int32_t getSampleRate() const { return mSampleRate; }
    int32_t getFramesPerBurst() const { return mFramesPerBurst; }
    int32_t getUnderrunCount() const { return mUnderrunCount.load(); }
    oboe::StreamState getState() const { return mState.load(); }

    // ── Diagnostics (worker-thread reads; benign races are acceptable) ──
    int64_t getProcessedFrames() const { return mProcessedFrames.load(); }
    int64_t getCallbackCount() const { return mCallbackCount.load(); }
    // Effective buffer size in frames (may change if the LatencyTuner is active).
    // Non-const: Oboe's getBufferSizeInFrames() is a non-const virtual.
    int32_t getBufferSizeInFrames();
    int32_t getBufferCapacityInFrames() const;
    // Output latency in milliseconds (valid only while the stream is started).
    // Returns -1 if unavailable (stream not started / unsupported).
    int32_t getLatencyMillis() const;
    oboe::SharingMode getSharingMode() const;
    oboe::PerformanceMode getPerformanceMode() const;

    // ── Buffer size control (worker thread — NOT the audio callback) ──
    // autoTune=true (default): the LatencyTuner manages the buffer size
    // (2×burst .. 8×burst) based on underruns. autoTune=false: the buffer size
    // is fixed to the value passed to setBufferSize().
    void setAutoTune(bool autoTune);
    bool isAutoTune() const { return mAutoTune.load(); }
    // Set a fixed buffer size in frames (worker thread). Sets the size on the
    // stream; when autoTune is on the LatencyTuner may then GROW it (up to
    // 8×burst) in response to underruns. Returns the effective size.
    int32_t setBufferSizeInFrames(int32_t frames);

private:
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* data, int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;
    void generateSineWave(float* buffer, int32_t numFrames);

    // Create the LatencyTuner for the current stream (worker thread, after open).
    void createLatencyTuner();

    // Audio frame callback — set by NativeEngine to replace sine wave generator
    using AudioFrameCallback = void(*)(float* output, int32_t numFrames);
    static void setAudioFrameCallback(AudioFrameCallback cb);

    // M5: "on open" callback — set by NativeEngine. Called (worker thread)
    // after a SUCCESSFUL (re)open with the ACTUAL negotiated sample rate.
    // NativeEngine uses it to handle a mid-session rate change (update the
    // transport + re-prepare the inactive synth slot at the new rate). A
    // function pointer (not a NativeEngine method) avoids a circular include
    // (OboeOutput.h forward-declares NativeEngine; NativeEngine.cpp includes
    // OboeOutput.h).
    using OnOpenCallback = void(*)(int32_t newRate);
    static void setOnOpenCallback(OnOpenCallback cb);

    friend class NativeEngine;

    static std::atomic<OboeOutput*> sInstance;
    static std::atomic<AudioFrameCallback> sAudioFrameCallback;
    static std::atomic<OnOpenCallback> sOnOpenCallback;

    oboe::AudioStream* mStream = nullptr;
    std::atomic<oboe::StreamState> mState{oboe::StreamState::Uninitialized};
    int32_t mSampleRate = 0;
    int32_t mFramesPerBurst = 0;
    std::atomic<int32_t> mUnderrunCount{0};

    // Diagnostics (audio thread writes, worker thread reads).
    std::atomic<int64_t> mProcessedFrames{0};
    std::atomic<int64_t> mCallbackCount{0};

    // LatencyTuner (created on the worker thread after open; tune() is called
    // in the audio callback — no allocation there).
    std::unique_ptr<oboe::LatencyTuner> mLatencyTuner;
    std::atomic<bool> mAutoTune{true};

    double mPhase = 0.0;
    double mPhaseIncrement = 0.0;
    static constexpr double kTestFrequency = 440.0;
    static constexpr float kTestAmplitude = 0.3f;
    // Requested sample rate (tried first on open; the device may fall back to
    // its native rate, which is then authoritative).
    static constexpr int32_t kRequestedSampleRate = 48000;
};