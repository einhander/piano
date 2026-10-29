#include "OboeOutput.h"
#include "StreamRecoveryPolicy.h"
#include <cmath>
#include <sstream>
#ifndef PIANO_CALLBACK_DIAGNOSTICS
#define PIANO_CALLBACK_DIAGNOSTICS 0
#endif
#include <time.h>

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

oboe::StreamState OboeOutput::getActualState() {
    std::lock_guard<std::mutex> streamLock(mStreamMutex);
    if (mStream == nullptr) return oboe::StreamState::Closed;
    const oboe::StreamState actual = mStream->getState();
    mState.store(actual, std::memory_order_release);
    return actual;
}

bool OboeOutput::configureDiagnosticProfile(piano::audio::ApiChoice api, piano::audio::RateChoice rate) {
    std::lock_guard<std::mutex> streamLock(mStreamMutex);
    if (mStream != nullptr || mProfileConfigured) return false;
    mRequestedApi = api;
    mRequestedRate = rate;
    mProfileConfigured = true;
    return true;
}

void OboeOutput::closeForDiagnostics() { close(); }

oboe::Result OboeOutput::open() {
    std::lock_guard<std::mutex> streamLock(mStreamMutex);
    // Idempotent: the engine is a process-level singleton that survives activity
    // recreation (AGENTS.md) — on re-bind, reuse the live stream instead of
    // leaking it. A stopped stream can be restarted. A dead stream (error/closed)
    // is closed and reopened.
    if (mStream != nullptr) {
        // Query actual backend state on this serialized worker path. Cached
        // Started can outlive an AAudio disconnect and must not keep a dead
        // stream reusable.
        oboe::StreamState st = mStream->getState();
        // Dead states: Closed / Disconnected (stream errors surface through the
        // error callback, which lands in Closed — there is no Error state).
        if (!piano::audio::requiresFreshStream(st)) {
            return oboe::Result::OK;
        }
        mState.store(st, std::memory_order_release);
        const uint32_t oldGeneration = mStreamGeneration.load(std::memory_order_relaxed);
        const oboe::Result closeResult = mStream->close();
        mLastClosedGeneration.store(oldGeneration, std::memory_order_relaxed);
        mLastCloseResult.store(static_cast<int32_t>(closeResult), std::memory_order_relaxed);
        mLastCloseReason.store(1, std::memory_order_relaxed); // closed while reopening dead stream
        mActiveStreamIdentity.store(0, std::memory_order_release);
        mStream = nullptr;
    }

    // Diagnostics profile is fixed for process lifetime, set before first open.
    oboe::SharingMode modes[] = {oboe::SharingMode::Shared};

    // Sample rate: request 48000 first (the engine's design rate); if the
    // device cannot do it, fall back to the device's native rate (no rate
    // set). The ACTUAL rate the stream ends up at is authoritative — it is
    // passed to the engine + FluidSynth (Fix #3: fixes the ~8.7% pitch/tempo
    // error on 44.1k devices).
    const int32_t requested = piano::audio::requestedRate(mRequestedRate);
    int32_t sampleRates[] = { requested, 0 }; // explicit rates retain existing device-rate fallback; SYSTEM starts at 0

    const int rateCount = piano::audio::rateAttemptCount(mRequestedRate);
    for (int rateIndex = 0; rateIndex < rateCount; ++rateIndex) {
        const int32_t rate = sampleRates[rateIndex];
        for (auto mode : modes) {
            oboe::AudioStreamBuilder builder;
            builder.setPerformanceMode(oboe::PerformanceMode::LowLatency);
            builder.setSharingMode(mode);
            if (mRequestedApi != piano::audio::ApiChoice::OboeDefault)
                builder.setAudioApi(piano::audio::toOboeApi(mRequestedApi));
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
                mActualApi.store(static_cast<int32_t>(mStream->getAudioApi()), std::memory_order_relaxed);
                mActualFormat.store(static_cast<int32_t>(mStream->getFormat()), std::memory_order_relaxed);
                mActualCallbackFrames.store(mStream->getFramesPerDataCallback(), std::memory_order_relaxed);
                mStreamGeneration.fetch_add(1, std::memory_order_acq_rel);
                mActiveStreamIdentity.store(reinterpret_cast<uintptr_t>(mStream), std::memory_order_release);
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
        // Oboe close joins callback processing. Callback never takes
        // mStreamMutex; LatencyTuner state is destroyed only after close.
        const uint32_t generation = mStreamGeneration.load(std::memory_order_relaxed);
        const oboe::Result closeResult = mStream->close();
        mLastClosedGeneration.store(generation, std::memory_order_relaxed);
        mLastCloseResult.store(static_cast<int32_t>(closeResult), std::memory_order_relaxed);
        mLastCloseReason.store(2, std::memory_order_relaxed); // explicit OboeOutput::close
        mActiveStreamIdentity.store(0, std::memory_order_release);
        mStream = nullptr;
        mState.store(oboe::StreamState::Uninitialized);
        mLatencyTuner.reset();
    }
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
    const oboe::StreamState actualState = mStream->getState();
    if (piano::audio::requiresFreshStream(actualState)) {
        mState.store(actualState, std::memory_order_release);
        return actualState == oboe::StreamState::Disconnected
            ? oboe::Result::ErrorDisconnected : oboe::Result::ErrorClosed;
    }
    if (actualState == oboe::StreamState::Started) {
        mState.store(oboe::StreamState::Started, std::memory_order_release);
        return oboe::Result::OK;
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
#if PIANO_CALLBACK_DIAGNOSTICS
    mCallbackTiming.callbackEnterTotal.fetch_add(1, std::memory_order_relaxed);
    mCallbackTiming.publishStage(1); // ENTER: callback admitted; before engine call
    timespec diagnosticNow{};
    clock_gettime(CLOCK_MONOTONIC, &diagnosticNow);
    const uint64_t entryNs = static_cast<uint64_t>(diagnosticNow.tv_sec) * 1000000000ULL + diagnosticNow.tv_nsec;
    mCallbackTiming.enter(entryNs);
#endif
    // NOTE: no ADPF reportWorkload here — Oboe wraps every data callback with
    // begin/endPerformanceHintInCallback (mutex + dlopen + binder + log on the
    // first call, mutex + HAL call on every call). See the comment in open().
    // Diagnostics (atomics only — no allocation/lock/IO in the callback).
    mCallbackCount.fetch_add(1, std::memory_order_relaxed);
    mProcessedFrames.fetch_add(numFrames, std::memory_order_relaxed);

    float* floatData = static_cast<float*>(data);
    AudioFrameCallback cb = sAudioFrameCallback.load(std::memory_order_acquire);
#if PIANO_CALLBACK_DIAGNOSTICS
    mCallbackTiming.publishStage(2); // ENGINE: frame callback running
#endif
    if (cb) {
        cb(floatData, numFrames);
    } else {
        generateSineWave(floatData, numFrames);
    }

#if PIANO_CALLBACK_DIAGNOSTICS
    mCallbackTiming.publishStage(3); // XRUN: querying stream underrun counter
#endif
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
#if PIANO_CALLBACK_DIAGNOSTICS
        mCallbackTiming.publishStage(4); // TUNER: reset/tune work
#endif
        mLatencyTuner->requestReset();
    }
    if (mAutoTune.load(std::memory_order_relaxed) && mLatencyTuner) {
#if PIANO_CALLBACK_DIAGNOSTICS
        mCallbackTiming.publishStage(4);
#endif
        mLatencyTuner->tune();
    }

#if PIANO_CALLBACK_DIAGNOSTICS
    mCallbackTiming.publishStage(5); // EXIT: callback work complete
    mCallbackTiming.callbackExitTotal.fetch_add(1, std::memory_order_relaxed);
    clock_gettime(CLOCK_MONOTONIC, &diagnosticNow);
    const uint64_t exitNs = static_cast<uint64_t>(diagnosticNow.tv_sec) * 1000000000ULL + diagnosticNow.tv_nsec;
    mCallbackTiming.finish(CallbackTiming::micros(exitNs - entryNs),
                           static_cast<uint32_t>(numFrames), static_cast<uint32_t>(mSampleRate));
    mCallbackTiming.publishStage(0); // IDLE; no callback currently executing
#endif

    return oboe::DataCallbackResult::Continue;
}

void OboeOutput::takeCallbackTiming(uint32_t* out) noexcept {
#if PIANO_CALLBACK_DIAGNOSTICS
    out[5] = 1;
    out[0] = mCallbackTiming.take(mCallbackTiming.count);
    out[1] = mCallbackTiming.take(mCallbackTiming.frames);
    out[2] = mCallbackTiming.take(mCallbackTiming.maxWallUs);
    out[3] = mCallbackTiming.take(mCallbackTiming.maxGapUs);
    out[4] = mCallbackTiming.take(mCallbackTiming.overBudget);
    const uint32_t minFrames = mCallbackTiming.take(mCallbackTiming.minFrames);
    out[21] = minFrames == ~uint32_t{0} ? 0 : minFrames;
    out[22] = mCallbackTiming.take(mCallbackTiming.maxFrames);
    // Append sampled engine timing to preserve established indices 0..5.
    out[6] = mCallbackTiming.take(mCallbackTiming.stageCount);
    out[7] = mCallbackTiming.take(mCallbackTiming.synthWallUs);
    out[8] = mCallbackTiming.take(mCallbackTiming.engineCpuUs);
    out[9] = mCallbackTiming.callbackEnterTotal.load(std::memory_order_relaxed);
    out[10] = mCallbackTiming.callbackExitTotal.load(std::memory_order_relaxed);
    out[11] = mCallbackTiming.callbackStage.load(std::memory_order_relaxed);
    // Bounded seqlock read: last complete sampled callback's paired stage CPUs.
    out[12] = out[13] = out[14] = out[15] = out[16] = out[17] = out[18] = out[19] = out[20] = 0;
    for (int attempt = 0; attempt < 3; ++attempt) {
        const uint32_t before = mCallbackTiming.detailSequence.load(std::memory_order_acquire);
        if (before & 1u) continue;
        const uint32_t values[] = {
            mCallbackTiming.detailEngineCpuUs.load(std::memory_order_relaxed),
            mCallbackTiming.detailSynthCpuUs.load(std::memory_order_relaxed),
            mCallbackTiming.detailInstFxCpuUs.load(std::memory_order_relaxed),
            mCallbackTiming.detailDelayCpuUs.load(std::memory_order_relaxed),
            mCallbackTiming.detailBusCpuUs.load(std::memory_order_relaxed),
            mCallbackTiming.detailMixerCpuUs.load(std::memory_order_relaxed),
            mCallbackTiming.detailMasterCpuUs.load(std::memory_order_relaxed),
            mCallbackTiming.detailOtherCpuUs.load(std::memory_order_relaxed),
            mCallbackTiming.detailSynthWallUs.load(std::memory_order_relaxed),
        };
        if (before == mCallbackTiming.detailSequence.load(std::memory_order_acquire)) {
            for (int i = 0; i < 9; ++i) out[12 + i] = values[i];
            break;
        }
    }
#else
    for (int i = 0; i < 5; ++i) out[i] = 0;
    out[5] = 0;
    for (int i = 6; i < 21; ++i) out[i] = 0;
    out[21] = out[22] = 0;
#endif
}

bool OboeOutput::diagnosticsEnabled() noexcept {
#if PIANO_CALLBACK_DIAGNOSTICS
    return true;
#else
    return false;
#endif
}

bool OboeOutput::takeEngineDiagnosticSample() noexcept {
#if PIANO_CALLBACK_DIAGNOSTICS
    static uint32_t callbackIndex = 0; // sole callback-thread writer
    return (callbackIndex++ & 63u) == 0;
#else
    return false;
#endif
}

void OboeOutput::recordEngineDiagnostic(uint32_t synthWallUs, uint32_t engineCpuUs,
                                        const std::array<uint32_t, 7>& stageCpuUs) noexcept {
#if PIANO_CALLBACK_DIAGNOSTICS
    if (OboeOutput* output = getInstance())
        output->mCallbackTiming.finishDetailedStage(synthWallUs, engineCpuUs, stageCpuUs);
#else
    (void)synthWallUs;
    (void)engineCpuUs;
    (void)stageCpuUs;
#endif
}

void OboeOutput::publishCallbackStage(uint32_t stage) noexcept {
#if PIANO_CALLBACK_DIAGNOSTICS
    if (OboeOutput* output = getInstance()) output->mCallbackTiming.publishStage(stage);
#else
    (void)stage;
#endif
}

void OboeOutput::onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) {
    mLastError.store(static_cast<int32_t>(error), std::memory_order_relaxed);
    mLastErrorGeneration.store(mStreamGeneration.load(std::memory_order_acquire), std::memory_order_relaxed);
    mLastErrorDeviceId.store(stream ? stream->getDeviceId() : -1, std::memory_order_relaxed);
    const bool matches = stream && reinterpret_cast<uintptr_t>(stream) ==
        mActiveStreamIdentity.load(std::memory_order_acquire);
    mLastErrorMatchesCurrent.store(matches, std::memory_order_relaxed);
    // A delayed error callback for a superseded stream must not mark its newer
    // replacement closed. Identity matching does not dereference the stream.
    if (matches) mState.store(oboe::StreamState::Closed, std::memory_order_release);
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
    std::ostringstream out;
    out << "callback_policy=fixed64,callback_requested=64"
        << ",generation=" << mStreamGeneration.load(std::memory_order_relaxed)
        << ",requested_api=" << static_cast<int>(mRequestedApi)
        << ",requested_rate=" << piano::audio::requestedRate(mRequestedRate)
        << ",actual_api=" << mActualApi.load(std::memory_order_relaxed)
        << ",actual_format=" << mActualFormat.load(std::memory_order_relaxed)
        << ",actual_callback_frames=" << mActualCallbackFrames.load(std::memory_order_relaxed)
        << ",last_closed_generation=" << mLastClosedGeneration.load(std::memory_order_relaxed)
        << ",last_close_result=" << mLastCloseResult.load(std::memory_order_relaxed)
        << ",last_close_reason=" << mLastCloseReason.load(std::memory_order_relaxed)
        << ",last_error_generation=" << mLastErrorGeneration.load(std::memory_order_relaxed)
        << ",last_error_device=" << mLastErrorDeviceId.load(std::memory_order_relaxed)
        << ",last_error_matches_current=" << mLastErrorMatchesCurrent.load(std::memory_order_relaxed);
    if (mStream == nullptr) {
        out << ",stream=closed,cached_state=" << oboe::convertToText(mState.load(std::memory_order_relaxed));
        return out.str();
    }
    out << ",api=" << oboe::convertToText(mStream->getAudioApi())
        << ",format=" << oboe::convertToText(mStream->getFormat())
        << ",channels=" << mStream->getChannelCount()
        << ",rate=" << mStream->getSampleRate()
        << ",sharing=" << oboe::convertToText(mStream->getSharingMode())
        << ",performance=" << oboe::convertToText(mStream->getPerformanceMode())
        << ",state=" << oboe::convertToText(mStream->getState())
        << ",cached_state=" << oboe::convertToText(mState.load(std::memory_order_relaxed))
        << ",device=" << mStream->getDeviceId()
        << ",callback_negotiated=" << mStream->getFramesPerDataCallback()
        << ",frames_written=" << mStream->getFramesWritten()
        << ",frames_read=" << mStream->getFramesRead();
    const auto timestamp = mStream->getTimestamp(CLOCK_MONOTONIC);
    if (timestamp) {
        const auto& value = timestamp.value();
        out << ",timestamp_status=ok,timestamp_position=" << value.position
            << ",timestamp_ns=" << value.timestamp;
    } else {
        out << ",timestamp_status=" << oboe::convertToText(timestamp.error());
    }
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
