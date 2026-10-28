#include "OboeOutput.h"
#include <cmath>
#include <sstream>

// Out-of-line definitions for inline static data members
std::atomic<OboeOutput*> OboeOutput::sInstance{nullptr};
std::atomic<OboeOutput::AudioFrameCallback> OboeOutput::sAudioFrameCallback{nullptr};
std::atomic<OboeOutput::OnOpenCallback> OboeOutput::sOnOpenCallback{nullptr};

void OboeOutput::setAudioFrameCallback(AudioFrameCallback cb) {
    sAudioFrameCallback.store(cb, std::memory_order_release);
}

void OboeOutput::setOnOpenCallback(OnOpenCallback cb) {
    sOnOpenCallback.store(cb, std::memory_order_release);
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

bool OboeOutput::isOpen() const {
    std::lock_guard<std::mutex> streamLock(mStreamMutex);
    return mStream != nullptr;
}

int32_t OboeOutput::getSampleRate() const {
    std::lock_guard<std::mutex> streamLock(mStreamMutex);
    return mSampleRate;
}

int32_t OboeOutput::getFramesPerBurst() const {
    std::lock_guard<std::mutex> streamLock(mStreamMutex);
    return mFramesPerBurst;
}

oboe::Result OboeOutput::open() {
    std::lock_guard<std::mutex> streamLock(mStreamMutex);
    // Idempotent: the engine is a process-level singleton that survives activity
    // recreation (AGENTS.md) — on re-bind, reuse the live stream instead of
    // leaking it. A stopped stream can be restarted. A dead stream (error/closed)
    // is closed and reopened.
    if (mStream != nullptr) {
        oboe::StreamState st = mState.load(std::memory_order_acquire);
        // Dead states: Closed / Disconnected (stream errors surface through the
        // error callback, which lands in Closed — there is no Error state).
        if (st != oboe::StreamState::Closed &&
            st != oboe::StreamState::Disconnected) {
            return oboe::Result::OK;
        }
        mStream->close();
        mStream = nullptr;
    }

    // Controlled diagnostic A/B: request Shared only, without attempting
    // Exclusive. All other stream requests and rate fallback remain unchanged.
    oboe::SharingMode modes[] = {oboe::SharingMode::Shared};

    // Sample rate: request 48000 first (the engine's design rate); if the
    // device cannot do it, fall back to the device's native rate (no rate
    // set). The ACTUAL rate the stream ends up at is authoritative — it is
    // passed to the engine + FluidSynth (Fix #3: fixes the ~8.7% pitch/tempo
    // error on 44.1k devices).
    int32_t sampleRates[] = { kRequestedSampleRate, 0 };  // 0 = device default

    for (int32_t rate : sampleRates) {
        for (auto mode : modes) {
            oboe::AudioStreamBuilder builder;
            builder.setPerformanceMode(oboe::PerformanceMode::LowLatency);
            builder.setSharingMode(mode);
            builder.setFormat(oboe::AudioFormat::Float);
            builder.setChannelCount(oboe::ChannelCount::Stereo);
            builder.setUsage(oboe::Usage::Media);
            builder.setContentType(oboe::ContentType::Music);
            builder.setAllowedCapturePolicy(oboe::AllowedCapturePolicy::All);
            builder.setCallback(this);
            builder.setFramesPerCallback(64);
            builder.setErrorCallback(this);
            if (rate > 0) {
                builder.setSampleRate(rate);
            }

            oboe::Result result = builder.openStream(&mStream);
            if (result == oboe::Result::OK) {
                // The device may have negotiated a different rate than
                // requested — the actual rate is authoritative.
                mSampleRate = mStream->getSampleRate();
                mFramesPerBurst = mStream->getFramesPerBurst();
                mPhaseIncrement = kTestFrequency / mSampleRate;
                mState.store(oboe::StreamState::Open);

                // NOTE: ADPF (setPerformanceHintEnabled / reportWorkload) is
                // intentionally NOT used — Oboe 1.10.2 wraps every user data
                // callback with begin/endPerformanceHintInCallback, whose first
                // call does AdpfWrapper::open (mutex + dlopen("libandroid.so") +
                // binder + LOGW/LOGD) and every call does reportActualDuration
                // (mutex + HAL call). That puts a mutex + dlopen + binder + log
                // INTO the audio callback, violating docs/realtime-rules.md.
                // The LatencyTuner below is the supported low-latency lever
                // (tune() is mutex-free).

                // LatencyTuner (Fix #4): auto-tunes the buffer size between
                // 2×burst and 8×burst based on underruns. tune() is called at
                // the end of each data callback (no allocation there).
                createLatencyTuner();

                // M5: notify the engine of the ACTUAL negotiated rate (worker
                // thread — open() is not the audio callback). The engine
                // no-ops if it is not yet initialized (initial open) or the
                // rate is unchanged; on a mid-session reopen at a different
                // rate it updates the transport + re-prepares the inactive
                // synth slot at the new rate.
                OnOpenCallback onOpen = sOnOpenCallback.load(std::memory_order_acquire);
                if (onOpen) {
                    onOpen(mSampleRate);
                }

                return oboe::Result::OK;
            }
        }
    }

    return oboe::Result::ErrorInternal;
}

void OboeOutput::createLatencyTuner() {
    if (mStream == nullptr) {
        return;
    }
    // Max 8×burst, min 2×burst (the default minimum). The constructor resets
    // the buffer to the minimum; tune() raises it on underruns.
    mLatencyTuner = std::make_unique<oboe::LatencyTuner>(
        *mStream, 8 * mFramesPerBurst);
    mLatencyTuner->setMinimumBufferSize(2 * mFramesPerBurst);
}

void OboeOutput::close() {
    std::lock_guard<std::mutex> streamLock(mStreamMutex);
    if (mStream != nullptr) {
        mStream->close();
        mStream = nullptr;
        mState.store(oboe::StreamState::Uninitialized);
    }
    mLatencyTuner.reset();
}

oboe::Result OboeOutput::start() {
    std::lock_guard<std::mutex> streamLock(mStreamMutex);
    if (mStream == nullptr) {
        return oboe::Result::ErrorNull;
    }
    // Rebind path: the engine is a process-level singleton and the stream
    // survives service rebinds (e.g. MIDI device reconnect). AAudio reports a
    // running stream as Started and returns INVALID_STATE (-895) for start()
    // on it — treat "already running" as success instead of a false error.
    if (mState.load(std::memory_order_acquire) == oboe::StreamState::Started) {
        if (mStream->getState() == oboe::StreamState::Started) {
            return oboe::Result::OK;
        }
    }
    oboe::Result result = mStream->start();
    if (result == oboe::Result::OK) {
        // Track the started state — isAudioPlaying() must not treat an
        // open-but-not-started stream as playing (that made the first Play
        // tap take the stop branch, so the stream never started → no sound).
        mState.store(oboe::StreamState::Started);
    }
    return result;
}

oboe::Result OboeOutput::stop() {
    std::lock_guard<std::mutex> streamLock(mStreamMutex);
    if (mStream == nullptr) {
        return oboe::Result::ErrorNull;
    }
    oboe::Result result = mStream->stop();
    if (result == oboe::Result::OK) {
        mState.store(oboe::StreamState::Stopped);
    }
    return result;
}

oboe::DataCallbackResult OboeOutput::onAudioReady(oboe::AudioStream* stream, void* data, int32_t numFrames) {
    // NOTE: no ADPF reportWorkload here — Oboe wraps every data callback with
    // begin/endPerformanceHintInCallback (mutex + dlopen + binder + log on the
    // first call, mutex + HAL call on every call). See the comment in open().
    // Diagnostics (atomics only — no allocation/lock/IO in the callback).
    mCallbackCount.fetch_add(1, std::memory_order_relaxed);
    mProcessedFrames.fetch_add(numFrames, std::memory_order_relaxed);

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

    // LatencyTuner (Fix #4): auto-tune the buffer size based on underruns.
    // Only when auto-tune is on (a user-set fixed buffer size is respected).
    // Designed to be called right before returning from the data callback.
    if (mLatencyTunerResetRequested.exchange(false, std::memory_order_acq_rel) && mLatencyTuner) {
        mLatencyTuner->requestReset();
    }
    if (mAutoTune.load(std::memory_order_relaxed) && mLatencyTuner) {
        mLatencyTuner->tune();
    }

    return oboe::DataCallbackResult::Continue;
}

void OboeOutput::onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) {
    mLastError.store(static_cast<int32_t>(error), std::memory_order_relaxed);
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

// ── Diagnostics (worker-thread reads) ────────────────────────────────────────

int32_t OboeOutput::getBufferSizeInFrames() {
    std::lock_guard<std::mutex> streamLock(mStreamMutex);
    if (mStream == nullptr) return 0;
    return mStream->getBufferSizeInFrames();
}

int32_t OboeOutput::getBufferCapacityInFrames() const {
    std::lock_guard<std::mutex> streamLock(mStreamMutex);
    if (mStream == nullptr) return 0;
    return mStream->getBufferCapacityInFrames();
}

int32_t OboeOutput::getLatencyMillis() const {
    std::lock_guard<std::mutex> streamLock(mStreamMutex);
    if (mStream == nullptr) return -1;
    auto result = mStream->calculateLatencyMillis();
    if (result != oboe::Result::OK) {
        return -1;  // stream not started / unsupported
    }
    return static_cast<int32_t>(result.value());
}

oboe::SharingMode OboeOutput::getSharingMode() const {
    std::lock_guard<std::mutex> streamLock(mStreamMutex);
    if (mStream == nullptr) return oboe::SharingMode::Shared;
    return mStream->getSharingMode();
}

oboe::PerformanceMode OboeOutput::getPerformanceMode() const {
    std::lock_guard<std::mutex> streamLock(mStreamMutex);
    if (mStream == nullptr) return oboe::PerformanceMode::LowLatency;
    return mStream->getPerformanceMode();
}

std::string OboeOutput::getStreamDiagnostics() const {
    std::lock_guard<std::mutex> streamLock(mStreamMutex);
    if (mStream == nullptr) return "closed";
    std::ostringstream out;
    out << "api=" << oboe::convertToText(mStream->getAudioApi())
        << ",format=" << oboe::convertToText(mStream->getFormat())
        << ",channels=" << mStream->getChannelCount()
        << ",rate=" << mStream->getSampleRate()
        << ",sharing=" << oboe::convertToText(mStream->getSharingMode())
        << ",performance=" << oboe::convertToText(mStream->getPerformanceMode())
        << ",state=" << oboe::convertToText(mStream->getState())
        << ",device=" << mStream->getDeviceId();
    return out.str();
}

// ── Buffer size control (worker thread) ──────────────────────────────────────

void OboeOutput::setAutoTune(bool autoTune) {
    std::lock_guard<std::mutex> streamLock(mStreamMutex);
    mAutoTune.store(autoTune, std::memory_order_relaxed);
    if (autoTune) mLatencyTunerResetRequested.store(true, std::memory_order_release);
}

int32_t OboeOutput::setBufferSizeInFrames(int32_t frames) {
    std::lock_guard<std::mutex> streamLock(mStreamMutex);
    if (mStream == nullptr) return 0;
    if (frames < 1) frames = 1;
    auto result = mStream->setBufferSizeInFrames(frames);
    if (result != oboe::Result::OK) {
        return mStream->getBufferSizeInFrames();
    }
    return result.value();
}
