#pragma once

#include <oboe/Oboe.h>
#include <atomic>
#include <cstdint>

class OboeOutput {
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

private:
    oboe::CallbackResult onAudioData(oboe::AudioStream* stream, void* data, int32_t numFrames);
    oboe::CallbackResult onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error);
    void generateSineWave(float* buffer, int32_t numFrames);

    // Audio frame callback — set by NativeEngine to replace sine wave generator
    using AudioFrameCallback = void(*)(float* output, int32_t numFrames);
    static void setAudioFrameCallback(AudioFrameCallback cb);

    static std::atomic<OboeOutput*> sInstance;
    static std::atomic<AudioFrameCallback> sAudioFrameCallback;
    oboe::AudioStream* mStream = nullptr;
    std::atomic<oboe::StreamState> mState{oboe::StreamState::Uninitialized};
    int32_t mSampleRate = 0;
    int32_t mFramesPerBurst = 0;
    std::atomic<int32_t> mUnderrunCount{0};

    double mPhase = 0.0;
    double mPhaseIncrement = 0.0;
    static constexpr double kTestFrequency = 440.0;
    static constexpr float kTestAmplitude = 0.3f;
};