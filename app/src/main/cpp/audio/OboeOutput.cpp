#include "OboeOutput.h"
#include <cmath>

// Out-of-line definitions for inline static data members
std::atomic<OboeOutput*> OboeOutput::sInstance{nullptr};
std::atomic<OboeOutput::AudioFrameCallback> OboeOutput::sAudioFrameCallback{nullptr};

void OboeOutput::setAudioFrameCallback(AudioFrameCallback cb) {
    sAudioFrameCallback.store(cb, std::memory_order_release);
}

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

OboeOutput::OboeOutput() {
    sInstance.store(this, std::memory_order_release);
}

OboeOutput::~OboeOutput() {
    if (sInstance.load(std::memory_order_acquire) == this) {
        sInstance.store(nullptr, std::memory_order_release);
    }
}

oboe::Result OboeOutput::open() {
    oboe::AudioStreamBuilder builder;

    builder.setPerformanceMode(oboe::PerformanceMode::LowLatency);
    builder.setSharingMode(oboe::SharingMode::Exclusive);
    builder.setFormat(oboe::AudioFormat::Float);
    builder.setChannelCount(oboe::ChannelCount::Stereo);
    builder.setCallback(this);
    builder.setFramesPerCallback(64);
    builder.setErrorCallback(this);

    oboe::Result result = builder.openStream(&mStream);
    if (result != oboe::Result::OK) {
        return result;
    }

    mSampleRate = mStream->getSampleRate();
    mFramesPerBurst = mStream->getFramesPerBurst();
    mPhaseIncrement = kTestFrequency / mSampleRate;
    mState.store(oboe::StreamState::Open);

    return oboe::Result::OK;
}

void OboeOutput::close() {
    if (mStream != nullptr) {
        mStream->close();
        mStream = nullptr;
        mState.store(oboe::StreamState::Uninitialized);
    }
}

oboe::Result OboeOutput::start() {
    if (mStream == nullptr) {
        return oboe::Result::ErrorNull;
    }
    return mStream->start();
}

oboe::Result OboeOutput::stop() {
    if (mStream == nullptr) {
        return oboe::Result::ErrorNull;
    }
    return mStream->stop();
}

oboe::DataCallbackResult OboeOutput::onAudioReady(oboe::AudioStream* stream, void* data, int32_t numFrames) {
    float* floatData = static_cast<float*>(data);
    AudioFrameCallback cb = sAudioFrameCallback.load(std::memory_order_acquire);
    if (cb) {
        cb(floatData, numFrames);
    } else {
        generateSineWave(floatData, numFrames);
    }

    auto xrunResult = stream->getXRunCount();
    int32_t current = xrunResult.value();
    int32_t previous = mUnderrunCount.load();
    while (current > previous) {
        if (mUnderrunCount.compare_exchange_weak(previous, current)) {
            break;
        }
    }

    return oboe::DataCallbackResult::Continue;
}

void OboeOutput::onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) {
    mState.store(oboe::StreamState::Closed);
}

void OboeOutput::generateSineWave(float* buffer, int32_t numFrames) {
    for (int32_t i = 0; i < numFrames; i++) {
        float sample = kTestAmplitude * std::sin(2.0 * M_PI * mPhase);
        buffer[i * 2] = sample;
        buffer[i * 2 + 1] = sample;
        mPhase += mPhaseIncrement;
        if (mPhase >= 1.0) {
            mPhase -= 1.0;
        }
    }
}