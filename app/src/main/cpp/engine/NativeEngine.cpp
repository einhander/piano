#include "NativeEngine.h"
#include "audio/OboeOutput.h"
#include "synth/FluidSynthEngine.h"
#include "realtime/MidiQueue.h"
#include "model/TransportState.h"
#include "engine/Sequencer.h"
#include "engine/SceneManager.h"
#include "engine/ClipScheduler.h"
#include "engine/LaunchQuantizer.h"
#include "engine/MidiRecorder.h"
#include "engine/MidiFilePlayer.h"
#include "midi/MidiFileParser.h"
#include "midi/MidiFileWriter.h"

#include <atomic>
#include <cmath>

std::atomic<NativeEngine*> NativeEngine::sInstance{nullptr};

NativeEngine::NativeEngine()
    : mSynth(new FluidSynthEngine()),
      mSequencer(),
      mSceneManager(),
      mClipScheduler() {
    sInstance.store(this, std::memory_order_release);
    startMidiThread();
}

NativeEngine::~NativeEngine() {
    stopMidiThread();
    shutdown();
    if (sInstance.load(std::memory_order_acquire) == this) {
        sInstance.store(nullptr, std::memory_order_release);
    }
    delete mSynth;
    mSynth = nullptr;
}

bool NativeEngine::init(int sampleRate, int bufferSize) {
    if (mInitialized.load(std::memory_order_acquire)) {
        return true;
    }

    mSampleRate = sampleRate;
    mPublishedSampleRate.store(sampleRate, std::memory_order_release);
    mRequestedSampleRate.store(sampleRate, std::memory_order_release);
    mBufferSize = bufferSize;

    bool ok = mSynth->init(sampleRate, bufferSize);
    if (!ok) {
        return false;
    }

    // Wire up transport + sequencer
    mTransport.initializeAudio(sampleRate);
    mAppliedBpmMicros.store(mTransport.bpmMicros, std::memory_order_release);
    mAppliedTpfFixed.store(static_cast<uint32_t>(mTransport.tpf * 1000000000.0 + 0.5),
                           std::memory_order_release);
    mAppliedTickMicros.store(0, std::memory_order_release);
    mPublishedSampleRate.store(sampleRate, std::memory_order_release);
    mSequencer.init(&mTransport);
    mClipScheduler.init(&mTransport);
    mSnapshotFrame.store(0, std::memory_order_relaxed);
    mTransportSnapshotSeq.store(2, std::memory_order_release);

    // Initialize Mixer and MasterBus
    // M2: size to the larger of kMaxSynthFrames and the ACTUAL Oboe buffer
    // capacity. The LatencyTuner grows the buffer toward the AAudio capacity
    // (≈4×burst); on weak phones with 10–20ms bursts that capacity exceeds
    // kMaxSynthFrames (2048) — e.g. a 20ms burst → 3840. Sizing to 2048 would
    // clamp the render to 2048 frames + a silence tail per callback (the
    // choppiness we are fixing). The capacity is valid here: openAudio()
    // (OboeOutput::open) runs before initEngine() (see MainActivity), so the
    // stream is already open. If it is not yet available (returns 0), fall back
    // to kMaxSynthFrames. mMaxSynthFrames also drives the safeFrames clamp in
    // onAudioFrame, so the full buffer is rendered (not just 2048).
    mMaxSynthFrames = kMaxSynthFrames;
    OboeOutput* oboe = OboeOutput::getInstance();
    if (oboe) {
        int cap = oboe->getBufferCapacityInFrames();
        if (cap > mMaxSynthFrames) {
            mMaxSynthFrames = cap;
        }
    }
    mMixer.init(16, mMaxSynthFrames);
    mMasterBus.init(mMaxSynthFrames);

    // Prepare the master effect chain scratch buffers at the same max as
    // Mixer/MasterBus (plan Phase 7). The LSP bundle is NOT loaded here — it
    // is loaded lazily by loadMasterEffectBundle() (called from the
    // PlaybackService worker thread after the engine is up, once the prebuilt
    // .so path is known). With the bundle absent, process() is a passthrough.
    mMasterEffects.prepare(sampleRate, mMaxSynthFrames);

    // Route FluidSynth output through Mixer track 0
    mMixer.setVolume(0, 1.0f);
    mMixer.setPan(0, 0.0f);
    mMixer.setMute(0, false);
    mMixer.setSolo(0, false);

    // Set master volume
    mMasterBus.setVolume(1.0f);

    // Register audio frame callback with OboeOutput
    OboeOutput::setAudioFrameCallback(&NativeEngine::onAudioFrameStatic);

    // M5: register the "on open" callback so a mid-session reopen at a
    // different rate updates the transport + re-prepares the synth. (Set in
    // init() so it is live before any subsequent reopen; the initial open
    // happens before init(), when the engine is not yet initialized, so the
    // callback no-ops there.)
    OboeOutput::setOnOpenCallback(&NativeEngine::onOpenStatic);

    mInitialized.store(true, std::memory_order_release);
    return true;
}

void NativeEngine::shutdown() {
    if (!mInitialized.load(std::memory_order_acquire)) {
        return;
    }

    OboeOutput::setAudioFrameCallback(nullptr);
    unloadSoundFonts();
    mInitialized.store(false, std::memory_order_release);
}

oboe::Result NativeEngine::startAudio() {
    OboeOutput* inst = OboeOutput::getInstance();
    if (!inst) return oboe::Result::ErrorNull;
    return inst->start();
}

oboe::Result NativeEngine::stopAudio() {
    OboeOutput* inst = OboeOutput::getInstance();
    if (!inst) return oboe::Result::ErrorNull;
    return inst->stop();
}

bool NativeEngine::isAudioPlaying() const {
    OboeOutput* inst = OboeOutput::getInstance();
    if (!inst) return false;
    // Only "Started" counts as playing. open() leaves the stream in the Open
    // state (not yet started); treating Open as playing made the first Play
    // tap take the stop branch — the stream was never started and no source
    // produced sound (synth renders in the audio callback only).
    return inst->getState() == oboe::StreamState::Started;
}

bool NativeEngine::isEngineInitialized() const {
    return mInitialized.load(std::memory_order_acquire);
}

int NativeEngine::loadSoundFont(const char* filePath) {
    if (!mSynth || !mInitialized.load(std::memory_order_acquire)) {
        return -1;
    }
    return mSynth->loadSoundFont(filePath);
}

bool NativeEngine::unloadSoundFont(int sfId) {
    if (!mSynth || !mInitialized.load(std::memory_order_acquire)) {
        return false;
    }
    return mSynth->unloadSoundFont(sfId);
}

void NativeEngine::unloadSoundFonts() {
    if (mSynth) {
        mSynth->unloadSoundFonts();
    }
}

void NativeEngine::noteOn(int channel, int note, int velocity) {
    if (mSynth) {
        mSynth->noteOn(channel, note, velocity);
    }
}

void NativeEngine::noteOff(int channel, int note) {
    if (mSynth) {
        mSynth->noteOff(channel, note);
    }
}

void NativeEngine::controlChange(int channel, int controller, int value) {
    if (mSynth) {
        mSynth->controlChange(channel, controller, value);
    }
}

void NativeEngine::programChange(int channel, int program) {
    if (mSynth) {
        mSynth->programChange(channel, program);
    }
}

void NativeEngine::pitchBend(int channel, int value) {
    if (mSynth) {
        mSynth->pitchBend(channel, value);
    }
}

void NativeEngine::channelPressure(int channel, int value) {
    if (mSynth) {
        mSynth->channelPressure(channel, value);
    }
}

void NativeEngine::panic() {
    if (mSynth) {
        mSynth->panic();
    }
}

void NativeEngine::setMasterGain(float gain) {
    if (mSynth) {
        mSynth->setMasterGain(gain);
    }
}

void NativeEngine::setPolyphony(int polyphony) {
    if (mSynth) {
        mSynth->setPolyphony(polyphony);
    }
}

void NativeEngine::setReverb(bool on) {
    if (mSynth) {
        mSynth->setReverb(on);
    }
}

void NativeEngine::setChorus(bool on) {
    if (mSynth) {
        mSynth->setChorus(on);
    }
}

void NativeEngine::setInterps(int method) {
    if (mSynth) {
        mSynth->setInterps(method);
    }
}

int NativeEngine::getPolyphony() const {
    return mSynth ? mSynth->getPolyphony() : 0;
}

float NativeEngine::getMasterGain() const {
    return mSynth ? mSynth->getMasterGain() : 0.0f;
}

int NativeEngine::getReverb() const {
    return mSynth ? mSynth->getReverb() : 0;
}

int NativeEngine::getChorus() const {
    return mSynth ? mSynth->getChorus() : 0;
}

int NativeEngine::getInterps() const {
    return mSynth ? mSynth->getInterps() : 4;
}

int NativeEngine::getActiveVoices() const {
    return mSynth ? mSynth->getActiveVoices() : 0;
}

int NativeEngine::getSoundFontCount() const {
    return mSynth ? mSynth->getSoundFontCount() : 0;
}

std::string NativeEngine::getSoundFontPath() const {
    return mSynth ? mSynth->getSoundFontPath() : std::string();
}

std::vector<FluidSynthEngine::LoadedSf2> NativeEngine::getLoadedSoundFonts() const {
    return mSynth ? mSynth->getLoadedSoundFonts() : std::vector<FluidSynthEngine::LoadedSf2>();
}

std::vector<InstrumentInfo> NativeEngine::getInstruments() const {
    return mSynth ? mSynth->getInstruments() : std::vector<InstrumentInfo>();
}

bool NativeEngine::setChannelProgram(int channel, int bank, int program) {
    return mSynth ? mSynth->setChannelProgram(channel, bank, program) : false;
}

bool NativeEngine::getChannelProgram(int channel, int& bank, int& program) const {
    return mSynth ? mSynth->getChannelProgram(channel, bank, program) : false;
}

int NativeEngine::getSampleRate() const {
    return mPublishedSampleRate.load(std::memory_order_acquire);
}

int NativeEngine::getUnderrunCount() const {
    OboeOutput* inst = OboeOutput::getInstance();
    if (!inst) return 0;
    return static_cast<int>(inst->getUnderrunCount());
}

// ── Diagnostics (worker-thread reads of atomics / benign ints) ──

int64_t NativeEngine::getProcessedFrames() const {
    OboeOutput* inst = OboeOutput::getInstance();
    return inst ? inst->getProcessedFrames() : 0;
}

int64_t NativeEngine::getCallbackCount() const {
    OboeOutput* inst = OboeOutput::getInstance();
    return inst ? inst->getCallbackCount() : 0;
}

int NativeEngine::getMidiQueueDrops() const {
    return static_cast<int>(mMidiQueue.droppedCount());
}

int NativeEngine::getSynthCmdQueueDrops() const {
    return mSynth ? static_cast<int>(mSynth->getCmdQueueDrops()) : 0;
}

int NativeEngine::getMidiQueueDepth() const {
    return static_cast<int>(mMidiQueue.size());
}

int NativeEngine::getLiveMidiQueueDepth() const {
    return static_cast<int>(mLiveMidiQueue.size());
}

// [perf]: number of clips currently in the clip scheduler (1 Hz line).
int NativeEngine::getActiveClipCount() const {
    return static_cast<int>(mClipScheduler.getActiveClipCount());
}

// [perf]: duration (ms) of the most recent SF2 load (one-time dump).
int64_t NativeEngine::getSf2LoadMs() const {
    return mSynth ? mSynth->getSf2LoadMs() : 0;
}

// ── Oboe stream diagnostics (worker-thread reads) ──

int NativeEngine::getBufferSizeInFrames() {
    OboeOutput* inst = OboeOutput::getInstance();
    return inst ? inst->getBufferSizeInFrames() : 0;
}

int NativeEngine::getBufferCapacityInFrames() const {
    OboeOutput* inst = OboeOutput::getInstance();
    return inst ? inst->getBufferCapacityInFrames() : 0;
}

int NativeEngine::getLatencyMillis() const {
    OboeOutput* inst = OboeOutput::getInstance();
    return inst ? inst->getLatencyMillis() : 0;
}

int NativeEngine::getSharingMode() const {
    OboeOutput* inst = OboeOutput::getInstance();
    return inst ? static_cast<int>(inst->getSharingMode()) : 1;
}

int NativeEngine::getPerformanceMode() const {
    OboeOutput* inst = OboeOutput::getInstance();
    return inst ? static_cast<int>(inst->getPerformanceMode()) : 0;
}

// [perf]: frames per Oboe burst (one-time dump; buffer = N×burst).
int NativeEngine::getFramesPerBurst() const {
    OboeOutput* inst = OboeOutput::getInstance();
    return inst ? inst->getFramesPerBurst() : 0;
}

// ── Oboe buffer size control (worker thread — NOT the audio callback) ──

void NativeEngine::setAutoTune(bool autoTune) {
    OboeOutput* inst = OboeOutput::getInstance();
    if (inst) {
        inst->setAutoTune(autoTune);
    }
}

bool NativeEngine::isAutoTune() const {
    OboeOutput* inst = OboeOutput::getInstance();
    return inst ? inst->isAutoTune() : true;
}

int NativeEngine::setBufferSizeInFrames(int frames) {
    OboeOutput* inst = OboeOutput::getInstance();
    return inst ? inst->setBufferSizeInFrames(frames) : -1;
}

// ── Sample-rate coordination (Fix #3) ──
// Called (worker thread) after the Oboe stream is opened, with the ACTUAL
// device rate. Updates the transport so ticksPerFrame is correct. The
// FluidSynth sample rate is fixed at init (it cannot be changed after
// creation in this FluidSynth version), so this must be called before the
// first render; the engine is a process-level singleton initialized once with
// the actual rate (see MainActivity).
bool NativeEngine::updateSampleRate(int sampleRate) {
    if (sampleRate <= 0) return false;
    mRequestedSampleRate.store(sampleRate, std::memory_order_release);
    if (!mTransportCmdQueue.push({TransportCmdType::SetSampleRate, 0, sampleRate})) return false;
    mPublishedSampleRate.store(sampleRate, std::memory_order_release);
    return true;
}

// M5: handle a mid-session sample-rate change (worker thread). Called after
// the Oboe stream is (re)opened at a different rate than the engine was
// initialized with (e.g. BT device switch 44.1k→48k). The init() path is
// idempotent (mInitialized guard), so a reopen at a new rate would otherwise
// leave the transport tempo + FluidSynth pitch off by the rate ratio.
//
// Steps: (1) update the transport (ticksPerFrame) to the new rate; (2)
// re-prepare the INACTIVE synth slot at the new rate (create a new synth at
// the new rate, reload the current SF2, apply the desired state, flip). The
// audio thread picks up the new synth on the next callback. No-op if the
// engine is not initialized (the init path handles the first rate) or the
// rate is unchanged.
void NativeEngine::handleSampleRateChange(int newRate) {
    if (newRate <= 0) return;
    if (!mInitialized.load()) return;   // init path handles the first rate
    if (newRate == mPublishedSampleRate.load(std::memory_order_acquire)) return;  // no change
    mPendingSynthReprepareRate.store(newRate, std::memory_order_release);
    (void)updateSampleRate(newRate);
    if (mSynth) {
        // Reload ALL currently-loaded SF2s into the new synth (multi-SF2).
        std::vector<std::string> sfPaths;
        for (const auto& sf : mSynth->getLoadedSoundFonts()) {
            sfPaths.push_back(sf.path);
        }
        const int32_t requested = mPendingSynthReprepareRate.exchange(0, std::memory_order_acq_rel);
        if (requested > 0) mSynth->reprepareAtNewRate(requested, sfPaths);
    }
}

void NativeEngine::enqueueMidiMessage(uint8_t status, uint8_t data1, uint8_t data2, int64_t timestamp) {
    MidiMessage msg;
    msg.status = status;
    msg.data1 = data1;
    msg.data2 = data2;
    msg.timestamp = timestamp;
    mMidiQueue.push(msg);
}

// NOTE: the old processMidiQueue() (which drained mMidiQueue → mLiveMidiQueue
// and called mMidiCV.notify_one()) is removed. The audio callback now drains
// mMidiQueue directly and feeds the synth (see onAudioFrame). The notify_one
// is gone (Fix #2: the audio callback never touches a mutex/condvar). The MIDI
// thread polls mLiveMidiQueue for recording only.

void NativeEngine::onAudioFrame(float* output, int numFrames) {
    // M2: clamp to mMaxSynthFrames (max(kMaxSynthFrames, Oboe capacity), set in
    // init()), NOT the fixed kMaxSynthFrames — this renders the full Oboe buffer
    // (e.g. 3840) instead of clamping to 2048 + a silence tail (the choppiness).
    // The tail-zero below is a safety net for a mid-session reopen at a LARGER
    // capacity (numFrames > mMaxSynthFrames → silence tail, acceptable).
    int safeFrames = (numFrames > mMaxSynthFrames) ? mMaxSynthFrames : numFrames;
    int64_t framePos = mTransport.framePosition.load(std::memory_order_acquire);
    const int32_t requestedRate = mRequestedSampleRate.load(std::memory_order_acquire);
    if (requestedRate > 0 && requestedRate != mTransport.sampleRate)
        (void)mTransportCmdQueue.push({TransportCmdType::SetSampleRate, 0, requestedRate});
    TransportCmd transportCmd;
    while (mTransportCmdQueue.pop(transportCmd)) {
        if (transportCmd.type == TransportCmdType::SetTempo)
            mTransport.applyTempo(transportCmd.bpmMicros, framePos);
        else if (transportCmd.sampleRate > 0)
        {
            mTransport.applyRate(transportCmd.sampleRate, framePos);
            mSampleRate = transportCmd.sampleRate;
        }
    }
    // A producer may have published a newer rate while queue was being drained.
    // Keep retrying latest request; transition cannot be lost to overflow.
    const int32_t latestRate = mRequestedSampleRate.load(std::memory_order_acquire);
    if (latestRate > 0 && latestRate != mTransport.sampleRate)
        (void)mTransportCmdQueue.push({TransportCmdType::SetSampleRate, 0, latestRate});
    mTransportSnapshotSeq.fetch_add(1, std::memory_order_release);
    mSnapshotFrame.store(framePos, std::memory_order_relaxed);
    mAppliedBpmMicros.store(mTransport.bpmMicros, std::memory_order_release);
    constexpr double kTpfScale = 1000000000.0;
    mAppliedTpfFixed.store(static_cast<uint32_t>(mTransport.tpf * kTpfScale + 0.5), std::memory_order_release);
    mPublishedSampleRate.store(mTransport.sampleRate, std::memory_order_release);
    mAppliedTickMicros.store(static_cast<int64_t>(mTransport.frameToTickAudio(framePos) * 1000000.0), std::memory_order_release);
    mTransportSnapshotSeq.fetch_add(1, std::memory_order_release);

    // Process MIDI file player (real-time safe: pre-allocated slots, lock-free
    // queues). File events go to mMidiQueue (drained below, fed to the synth).
    mMidiFilePlayer.process(numFrames, mSampleRate, framePos, &mMidiQueue);

    // Clip removal is control-thread intent; scheduler detachment and retired
    // acknowledgement happen only at callback boundary.
    for (int32_t i = 0; i < kMaxClips; ++i) {
        uint32_t published = static_cast<uint32_t>(ClipSlotState::Published);
        if (mClipStates[i].state.compare_exchange_strong(published,
                static_cast<uint32_t>(ClipSlotState::Active), std::memory_order_acq_rel)) {
            mClipScheduler.activateSlot(i, &mClips[i]);
        }
        if (mClipStates[i].state.load(std::memory_order_acquire) ==
                static_cast<uint32_t>(ClipSlotState::RetireRequested)) {
            mClipScheduler.deactivateSlot(i);
            uint32_t expected = static_cast<uint32_t>(ClipSlotState::RetireRequested);
            mClipStates[i].state.compare_exchange_strong(expected,
                static_cast<uint32_t>(ClipSlotState::Retired), std::memory_order_release,
                std::memory_order_relaxed);
        }
    }
    int32_t sequencerEventCount = mSequencer.collectDueEvents(
        framePos, framePos + safeFrames, mSequencerStaging, kSequencerStagingCapacity);
    sequencerEventCount += mClipScheduler.collectDueEvents(
        framePos, framePos + safeFrames, mSequencerStaging + sequencerEventCount,
        kSequencerStagingCapacity - sequencerEventCount);
    for (int32_t i = 1; i < sequencerEventCount; ++i) {
        TimedMidiEvent item = mSequencerStaging[i]; int32_t j = i;
        while (j > 0 && timedMidiEventLess(item, mSequencerStaging[j - 1])) { mSequencerStaging[j] = mSequencerStaging[j - 1]; --j; }
        mSequencerStaging[j] = item;
    }

    // Process queued scene launches
    mSceneManager.processLaunchQueue(framePos);

    // Advance frame position
    mTransport.framePosition.fetch_add(numFrames, std::memory_order_release);

    // Advance recording tick (for MIDI file tick timestamps on recorded events)
    // M5: double accumulator on audio thread + atomic int64_t for MIDI thread to read
    if (mRecorder.isRecording()) {
        double ticksPerFrame = mTransport.tpf;
        mRecordTickAccumulator += ticksPerFrame * numFrames;
        mRecordTick.store(static_cast<int64_t>(mRecordTickAccumulator),
                          std::memory_order_relaxed);
    }

    // ── Synth access region (sequence lock) — ALL fluid_synth_* calls here ──
    // The audio thread is the ONLY thread that touches the synths, so no lock
    // is needed (Fix #1: the old mSynthMutex is removed). The sequence lock
    // (mSynthSeq, in beginSynthAccess/endSynthAccess) only synchronizes the
    // worker-thread getInstruments/getSoundFontCount reads of the active synth.
    if (mSynth) {
        mSynth->beginSynthAccess();

        // Drain the control command queue (settings/panic/direct notes) and
        // apply to both synth slots (kept in sync).
        mSynth->processCommands();

        // Drain the live MIDI queue → feed the active synth (with held-note
        // tracking + re-arm). Live keyboard events (timestamp == 0) are also
        // pushed to mLiveMidiQueue for the MIDI thread's recording.
        MidiMessage msg;
        while (mMidiQueue.pop(msg)) {
            mSynth->processOneMidi(msg);
            if (msg.timestamp == 0) {
                mLiveMidiQueue.push(msg);  // for recording (MIDI thread)
            }
        }

        // Immediate queue first; sequencer events render at their frame offsets.
        float* track0 = mMixer.getTrackBuffer(0);
        int offset = 0;
        for (int32_t i = 0; i < sequencerEventCount; ++i) {
            int eventOffset = static_cast<int>(mSequencerStaging[i].targetFrame - framePos);
            if (eventOffset < offset) eventOffset = offset;
            if (eventOffset > safeFrames) eventOffset = safeFrames;
            if (eventOffset > offset) {
                mSynth->render(track0 + offset * 2, eventOffset - offset);
            }
            mSynth->processOneMidi(mSequencerStaging[i].message);
            offset = eventOffset;
        }
        if (offset < safeFrames) {
            mSynth->render(track0 + offset * 2, safeFrames - offset);
        }

        mSynth->endSynthAccess();
    } else {
        // Zero the track-0 buffer if the synth is not initialized.
        float* track0 = mMixer.getTrackBuffer(0);
        for (int i = 0; i < safeFrames * 2; i++) {
            track0[i] = 0.0f;
        }
    }

    // Reset meters at start of playback
    mMixer.resetMeters();
    mMasterBus.resetMeter();

    // Mix all tracks through Mixer into stereo output
    mMixer.mix(output, safeFrames);

    // ── LSP master effect chain (EQ → Compressor → Limiter) ──
    // Inserted AFTER the Mixer and BEFORE the MasterBus, per plan Phase 6/17:
    //   Mixer → EQ → Compressor → Limiter → MasterBus → Oboe
    // This runs after endSynthAccess() (the FluidSynth sequence-lock region
    // ended above), processes exactly safeFrames, and is a no-op passthrough
    // when the bundle is not loaded or every effect is bypassed — so the
    // baseline audio path is unchanged. Realtime-safe: deinterleave once into
    // pre-allocated planar scratch, run fixed effect array, interleave once.
    mMasterEffects.process(output, safeFrames);

    // Process through MasterBus (volume + soft clipper)
    mMasterBus.process(output, safeFrames);

    // M4/M2: safety net. Normally safeFrames == numFrames (mMaxSynthFrames is sized
    // to the Oboe capacity in init()), so this branch is not taken. It only
    // fires if the stream is REOPENED mid-session at a LARGER capacity than the
    // buffers were sized for (numFrames > mMaxSynthFrames) — then the tail is
    // not written by render/mix/process and would keep the PREVIOUS callback's
    // data (stale/ghost audio). Zero it so it plays silence. (The buffers are
    // not grown at runtime — a silence tail in this edge case is acceptable.)
    // (Output is interleaved stereo = 2 channels.)
    if (numFrames > safeFrames) {
        std::memset(output + safeFrames * 2, 0,
                    (numFrames - safeFrames) * 2 * sizeof(float));
    }
}

void NativeEngine::setBPM(double bpm) {
    if (std::isfinite(bpm) && bpm > 0.0 && bpm <= 1000.0)
        (void)mTransportCmdQueue.push({TransportCmdType::SetTempo,
            static_cast<uint32_t>(bpm * 1000000.0 + 0.5), 0});
}

void NativeEngine::setTransportState(int state) {
    mTransport.state.store(static_cast<TransportState::State>(state), std::memory_order_release);
}

double NativeEngine::getCurrentTick() const {
    return mAppliedTickMicros.load(std::memory_order_acquire) / 1000000.0;
}

int64_t NativeEngine::getFramePosition() const {
    return mTransport.framePosition.load(std::memory_order_acquire);
}

double NativeEngine::getBPM() const {
    return mAppliedBpmMicros.load(std::memory_order_acquire) / 1000000.0;
}

int32_t NativeEngine::getPpq() const {
    return mTransport.ppq;
}

void NativeEngine::switchScene(int32_t sceneId) {
    mSceneManager.switchScene(sceneId);
}

int32_t NativeEngine::currentSceneId() const {
    return mSceneManager.currentSceneId();
}

bool NativeEngine::hasSceneChanged() const {
    return mSceneManager.hasSceneChanged();
}

void NativeEngine::acknowledgeSceneChange() {
    mSceneManager.acknowledgeSceneChange();
}

// Launch quantization
void NativeEngine::setQuantizationGrid(int32_t grid) {
    mLaunchQuantizer.setGrid(static_cast<QuantizationGrid>(grid));
}

int32_t NativeEngine::getQuantizationGrid() const {
    return static_cast<int32_t>(mLaunchQuantizer.getGrid());
}

bool NativeEngine::isLaunchPending() const {
    return mLaunchQuantizer.isLaunchPending();
}

void NativeEngine::acknowledgeLaunch() {
    mLaunchQuantizer.acknowledgeLaunch();
}

int64_t NativeEngine::scheduleLaunch(int32_t sceneId, int32_t grid, int64_t currentFrame) {
    uint32_t seq;
    TransportSnapshot snapshot;
    do {
        seq = mTransportSnapshotSeq.load(std::memory_order_acquire);
        snapshot = {mAppliedTickMicros.load(std::memory_order_relaxed) / 1000000.0,
            mAppliedTpfFixed.load(std::memory_order_relaxed) / 1000000000.0,
            mSnapshotFrame.load(std::memory_order_relaxed), 960, 4};
    } while ((seq & 1u) || seq != mTransportSnapshotSeq.load(std::memory_order_acquire));
    (void)sceneId; // sceneId is used by queueSceneLaunch, not quantizer
    return mLaunchQuantizer.scheduleLaunch(static_cast<QuantizationGrid>(grid), currentFrame, snapshot);
}

// Scene navigation
void NativeEngine::registerScene(int32_t sceneId, const char* name) {
    mSceneManager.registerScene(sceneId, name);
}

int32_t NativeEngine::nextScene() const {
    return mSceneManager.nextScene();
}

int32_t NativeEngine::previousScene() const {
    return mSceneManager.previousScene();
}

int32_t NativeEngine::getSceneCount() const {
    return mSceneManager.getSceneCount();
}

// Launch queue
bool NativeEngine::queueSceneLaunch(int32_t sceneId, int64_t targetFrame) {
    return mSceneManager.queueSceneLaunch(sceneId, targetFrame);
}

int32_t NativeEngine::getLaunchQueueDepth() const {
    return mSceneManager.getQueueDepth();
}

// Clip transport sync
void NativeEngine::setClipTransportSync(int32_t clipId, bool enabled) {
    (void)clipId; (void)enabled;
    // MVP: store clip sync settings for later use
    // Full implementation would maintain a per-clip sync state table
}

void NativeEngine::setClipStartTick(int32_t clipId, int64_t startTick) {
    (void)clipId; (void)startTick;
    // MVP: placeholder
}

void NativeEngine::setClipEndTick(int32_t clipId, int64_t endTick) {
    (void)clipId; (void)endTick;
    // MVP: placeholder
}

void NativeEngine::setClipLoop(int32_t clipId, bool loop) {
    (void)clipId; (void)loop;
    // MVP: placeholder
}

void NativeEngine::setTrackVolume(int trackId, float volume) {
    mMixer.setVolume(trackId, volume);
}

void NativeEngine::setTrackPan(int trackId, float pan) {
    mMixer.setPan(trackId, pan);
}

void NativeEngine::setTrackMute(int trackId, bool mute) {
    mMixer.setMute(trackId, mute);
}

void NativeEngine::setTrackSolo(int trackId, bool solo) {
    mMixer.setSolo(trackId, solo);
}

float NativeEngine::getTrackPeakMeter(int trackId) const {
    return mMixer.getPeakMeter(trackId);
}

void NativeEngine::setMasterVolume(float volume) {
    mMasterBus.setVolume(volume);
}

float NativeEngine::getMasterPeakMeter() const {
    return mMasterBus.getPeakMeter();
}

// ── Master effect chain (LSP) ──
int NativeEngine::loadMasterEffectBundle(const char* soPath) {
    // Worker thread. dlopen + instantiate happen here; the audio thread only
    // ever calls process() (no alloc/lock). Re-prepares at the current rate/max.
    return mMasterEffects.loadBundle(soPath, mSampleRate, mMaxSynthFrames);
}

const char* NativeEngine::getMasterEffectLoadError() const {
    return mMasterEffects.loadError();
}

bool NativeEngine::isMasterEffectChainAvailable() const {
    return mMasterEffects.isAvailable();
}

int NativeEngine::getMasterEffectCount() const {
    return mMasterEffects.effectCount();
}

void NativeEngine::setMasterEffectEnabled(int slot, bool enabled) {
    if (auto* e = mMasterEffects.effect(slot)) {
        // "Enabled" = not bypassed. Default is bypassed (safe).
        e->setBypassed(!enabled);
    }
}

bool NativeEngine::isMasterEffectEnabled(int slot) const {
    if (auto* e = mMasterEffects.effect(slot)) {
        return !e->isBypassed();
    }
    return false;
}

void NativeEngine::setMasterEffectParameter(int slot, int parameterId, float value) {
    if (auto* e = mMasterEffects.effect(slot)) {
        e->setParameter(static_cast<uint32_t>(parameterId), value);
    }
}

float NativeEngine::getMasterEffectParameter(int slot, int parameterId) const {
    if (auto* e = mMasterEffects.effect(slot)) {
        return e->getParameter(static_cast<uint32_t>(parameterId));
    }
    return 0.0f;
}

const char* NativeEngine::getMasterEffectStableId(int slot) const {
    if (auto* e = mMasterEffects.effect(slot)) {
        return e->stableId();
    }
    return "";
}

int NativeEngine::getMasterEffectParamCount(int slot) const {
    int count = 0;
    piano::lsp::paramDescriptors(slot, count);
    return count;
}

bool NativeEngine::getMasterEffectParamInfo(int slot, int index,
                                           uint32_t& paramId,
                                           float& minValue,
                                           float& maxValue,
                                           float& defaultValue,
                                           bool& logarithmic,
                                           bool& integer,
                                           bool& toggled) const {
    int count = 0;
    const auto* descriptors = piano::lsp::paramDescriptors(slot, count);
    if (descriptors == nullptr || index < 0 || index >= count) {
        return false;
    }
    const auto& d = descriptors[index];
    paramId = d.id;
    minValue = d.minValue;
    maxValue = d.maxValue;
    defaultValue = d.defaultValue;
    logarithmic = d.logarithmic;
    integer = d.integer;
    toggled = d.toggled;
    return true;
}

const char* NativeEngine::getMasterEffectParamName(int slot, int index) const {
    int count = 0;
    const auto* descriptors = piano::lsp::paramDescriptors(slot, count);
    if (descriptors == nullptr || index < 0 || index >= count) {
        return "";
    }
    return descriptors[index].displayName ? descriptors[index].displayName : "";
}

void NativeEngine::addClip(int32_t clipId, int32_t trackId, int64_t startTick, int64_t lengthTicks,
                           const uint8_t* events, int32_t eventCount) {
    if (!events || eventCount <= 0 || eventCount > ClipData::kMaxEvents) return;

    // Find slot (replace existing or find reusable state)
    int32_t replaceSlot = -1;  // Slot with matching clipId
    int32_t emptySlot = -1;    // First empty slot (clipId == 0)
    for (int32_t i = 0; i < kMaxClips; i++) {
        uint32_t state = mClipStates[i].state.load(std::memory_order_acquire);
        if (state == static_cast<uint32_t>(ClipSlotState::Published) ||
            state == static_cast<uint32_t>(ClipSlotState::Active)) {
            if (mClips[i].clipId == clipId) { replaceSlot = i; break; }
        }
        if ((state == static_cast<uint32_t>(ClipSlotState::Free) || state == static_cast<uint32_t>(ClipSlotState::Retired)) && emptySlot < 0) emptySlot = i;
    }
    int32_t slot = replaceSlot >= 0 ? replaceSlot : emptySlot;
    if (slot < 0) return;
    uint32_t expectedState = static_cast<uint32_t>(ClipSlotState::Free);
    if (!mClipStates[slot].state.compare_exchange_strong(expectedState,
            static_cast<uint32_t>(ClipSlotState::Building), std::memory_order_acq_rel)) {
        expectedState = static_cast<uint32_t>(ClipSlotState::Retired);
        if (!mClipStates[slot].state.compare_exchange_strong(expectedState,
                static_cast<uint32_t>(ClipSlotState::Building), std::memory_order_acq_rel)) return;
    }

    mClips[slot].clipId = clipId;
    mClips[slot].trackId = trackId;
    mClips[slot].startTick = startTick;
    mClips[slot].lengthTicks = lengthTicks;
    mClips[slot].eventCount = eventCount;

    // Copy events: each event is 12 bytes (int64_t tick + 3 uint8_t)
    const uint8_t* src = events;
    for (int32_t i = 0; i < eventCount; i++) {
        int64_t tick = 0;
        for (int b = 0; b < 8; b++) {
            tick |= static_cast<int64_t>(src[b]) << (b * 8);
        }
        src += 8;
        mClips[slot].events[i].tick = tick;
        mClips[slot].events[i].status = src[0];
        mClips[slot].events[i].data1 = src[1];
        mClips[slot].events[i].data2 = src[2];
        src += 3;
    }

    // mClips is already ClipScheduler::ClipData[] — no cast needed
    mClipStates[slot].state.store(static_cast<uint32_t>(ClipSlotState::Published), std::memory_order_release);

    int32_t count = mClipCount.load(std::memory_order_acquire);
    if (slot >= count) mClipCount.store(slot + 1, std::memory_order_release);
}

void NativeEngine::loadProject(const char* json) {
    if (!json || !mInitialized.load(std::memory_order_acquire)) return;

    // Parse JSON on worker thread — apply to engine state at callback boundary
    // MVP: set basic project parameters from JSON
    // Full implementation would parse tracks, clips, scenes via nlohmann/json or manual parser

    // For now, parse bpm if present (simple manual parse for testing)
    const char* bpmKey = "\"bpm\"";
    const char* pos = strstr(json, bpmKey);
    if (pos) {
        pos += strlen(bpmKey);
        // Skip colon and whitespace
        while (*pos == ':' || *pos == ' ') pos++;
        if (*pos >= '0' && *pos <= '9') {
            double parsedBpm = 0.0;
            const char* start = pos;
            while (*pos >= '0' && *pos <= '9' || *pos == '.') pos++;
            char buf[64] = {0};
            int len = (int)(pos - start);
            if (len > 0 && len < 64) {
                strncpy(buf, start, len);
                parsedBpm = atof(buf);
                if (parsedBpm > 20.0 && parsedBpm < 300.0) {
                    setBPM(parsedBpm);
                }
            }
        }
    }

    // Parse masterGain if present
    const char* gainKey = "\"masterGain\"";
    pos = strstr(json, gainKey);
    if (pos) {
        pos += strlen(gainKey);
        while (*pos == ':' || *pos == ' ') pos++;
        if (*pos >= '0' && *pos <= '9') {
            float parsedGain = static_cast<float>(atof(pos));
            if (parsedGain >= 0.0f && parsedGain <= 2.0f) {
                setMasterGain(parsedGain);
            }
        }
    }
}

void NativeEngine::removeClip(int32_t clipId) {
    for (int32_t i = 0; i < kMaxClips; i++) {
        uint32_t state = mClipStates[i].state.load(std::memory_order_acquire);
        if ((state == static_cast<uint32_t>(ClipSlotState::Published) ||
             state == static_cast<uint32_t>(ClipSlotState::Active)) &&
            mClips[i].clipId == clipId) {
            uint32_t expected = static_cast<uint32_t>(ClipSlotState::Published);
            if (!mClipStates[i].state.compare_exchange_strong(expected,
                static_cast<uint32_t>(ClipSlotState::RetireRequested), std::memory_order_acq_rel)) {
                expected = static_cast<uint32_t>(ClipSlotState::Active);
                mClipStates[i].state.compare_exchange_strong(expected,
                    static_cast<uint32_t>(ClipSlotState::RetireRequested), std::memory_order_acq_rel);
            }
            return;
        }
    }
}

// Count-in metronome
int64_t NativeEngine::startCountIn(int beats) {
    mCountingIn.store(true, std::memory_order_release);
    mCountInBeats = beats;
    mCountInStartFrame = mTransport.framePosition.load(std::memory_order_acquire);
    mCountInClickIndex = 0;

    // Each beat = 60/bpm seconds = 60/bpm * sampleRate frames
    const double rate = mPublishedSampleRate.load(std::memory_order_acquire);
    const double bpmMicros = mAppliedBpmMicros.load(std::memory_order_acquire);
    double framesPerBeat = (60000000.0 / bpmMicros) * rate;
    mCountInEndFrame = mCountInStartFrame + static_cast<int64_t>(framesPerBeat * beats);

    return mCountInEndFrame;
}

bool NativeEngine::isCountingIn() const {
    return mCountingIn.load(std::memory_order_acquire);
}

int64_t NativeEngine::getCountInEndFrame() const {
    return mCountInEndFrame.load(std::memory_order_acquire);
}

// Play a short sine wave click for count-in
// NOT real-time safe — called from UI thread only, writes to synth buffer
void NativeEngine::playCountInClick(int64_t frame) {
    // Generate a short click: ~50ms sine burst at 800Hz
    // Render into synth buffer at the appropriate position
    const double rate = mPublishedSampleRate.load(std::memory_order_acquire);
    int clickFrames = static_cast<int>(0.05 * rate);
    if (clickFrames > kMaxSynthFrames) clickFrames = kMaxSynthFrames;

    for (int i = 0; i < clickFrames; i++) {
        double t = static_cast<double>(i) / rate;
        float sample = static_cast<float>(sin(2.0 * M_PI * 800.0 * t) * exp(-t * 40.0));
        mSynthBuffer[i * 2] = sample;
        mSynthBuffer[i * 2 + 1] = sample;
    }
}

bool NativeEngine::shouldPlayCountInClick(int64_t frame) const {
    if (!mCountingIn.load(std::memory_order_acquire)) return false;
    if (frame < mCountInStartFrame) return false;

    int64_t elapsed = frame - mCountInStartFrame;
    const double rate = mPublishedSampleRate.load(std::memory_order_acquire);
    const double bpmMicros = mAppliedBpmMicros.load(std::memory_order_acquire);
    double framesPerBeat = (60000000.0 / bpmMicros) * rate;

    int currentBeat = static_cast<int>(elapsed / framesPerBeat);
    if (currentBeat >= mCountInBeats) return false;

    // Check if we're at the start of a beat (within 10 frames)
    int64_t beatStart = static_cast<int64_t>(currentBeat * framesPerBeat);
    return (elapsed - beatStart) < 10;
}

// Recording control
void NativeEngine::startRecording() {
    // M4: pass 0 — mRecordTick is already the tick source, record() won't subtract again
    mRecordTickAccumulator = 0.0; // clear cross-session stale offset before new recording
    mRecorder.start(0);
    mRecordTick.store(0, std::memory_order_release);
    // m9: removed transport state side effect — recording tick advances in onAudioFrame
    // regardless of transport state; the export tempo param must match the bpm used during recording.
}

void NativeEngine::stopRecording() {
    mRecorder.stop();
}

void NativeEngine::setRecordArm(int trackId, bool armed) {
    if (trackId >= 0 && trackId < 16) {
        mRecordArmed[trackId].store(armed, std::memory_order_release);
    }
}

void NativeEngine::setOverdub(bool overdub) {
    mRecorder.setOverdub(overdub);
}

bool NativeEngine::isRecording() const {
    return mRecorder.isRecording();
}

std::vector<RecordedMidiEvent> NativeEngine::getRecordedEvents() {
    return mRecorder.getEvents(); // thread-safe copy (N2/N7)
}

// MIDI file slot playback
int NativeEngine::loadMidiFileSlot(int slot, const char* filePath, float bpm, bool loop,
                                   const int32_t* selectedTracks, int32_t selectedCount,
                                   const int32_t* trackChannels, bool startAfterLoad) {
    return mMidiFilePlayer.load(slot, filePath, bpm, loop, selectedTracks, selectedCount, trackChannels, startAfterLoad);
}

std::vector<std::string> NativeEngine::getMidiFileTracks(const char* filePath) {
    if (!filePath) return {};
    return mMidiFilePlayer.getTrackNamesForFile(filePath);
}

float NativeEngine::getMidiFileTempo(const char* filePath) {
    if (!filePath) return -1.0f;
    return mMidiFilePlayer.getMidiFileTempo(filePath);
}

int NativeEngine::preloadMidiFile(const char* filePath) {
    return mMidiFilePlayer.preload(filePath);
}

void NativeEngine::startMidiFileSlot(int slot) {
    mMidiFilePlayer.start(slot);
}

void NativeEngine::stopMidiFileSlot(int slot) {
    mMidiFilePlayer.stop(slot);
}

bool NativeEngine::isMidiFileSlotPlaying(int slot) const {
    return mMidiFilePlayer.isSlotPlaying(slot);
}

void NativeEngine::setMidiFileSlotLoop(int slot, bool loop) {
    mMidiFilePlayer.setLoop(slot, loop);
}

void NativeEngine::setMidiFileSlotTempo(int slot, float bpm) {
    mMidiFilePlayer.setTempo(slot, bpm);
}

MidiFilePlayer::SlotInfo NativeEngine::getMidiFileSlotInfo(int slot) const {
    return mMidiFilePlayer.getSlotInfo(slot);
}

void NativeEngine::freeMidiFileSlot(int slot) {
    mMidiFilePlayer.freeSlot(slot);
}

int64_t NativeEngine::getMidiFileSlotLoadFrame(int slot) const {
    return mMidiFilePlayer.getLoadConsumeFrame(slot);
}

int64_t NativeEngine::getMidiFileSlotStartFrame(int slot) const {
    return mMidiFilePlayer.getStartConsumeFrame(slot);
}

// Recorded MIDI export
bool NativeEngine::writeRecordedMidiFile(const char* filePath, int ppq, uint32_t tempo) {
    const auto events = mRecorder.getEvents(); // thread-safe copy (N2)
    if (events.empty()) return false;
    MidiFileWriter writer;
    return writer.write(filePath, events, 0, ppq, tempo);
}

int NativeEngine::getRecordedEventCount() const {
    return static_cast<int>(mRecorder.eventCount()); // thread-safe (N2)
}

NativeEngine* NativeEngine::getInstance() {
    return sInstance.load(std::memory_order_acquire);
}

// MIDI thread — runs outside the audio callback. It now ONLY records live
// MIDI (the audio thread feeds the synth directly — Fix #1/#2). It polls the
// lock-free mLiveMidiQueue (no condition variable — the audio callback never
// touches a mutex/condvar). mLiveMidiQueue contains only live keyboard events
// (timestamp == 0), which the audio thread pushes here for recording.
void NativeEngine::midiThreadFunc() {
    while (mMidiThreadRunning.load(std::memory_order_acquire)) {
        // Drain all pending live MIDI messages from the queue into a temp
        // buffer (vector push_back is OK here — this is NOT the audio callback).
        std::vector<MidiMessage> tempMsgs;
        MidiMessage msg;
        while (mLiveMidiQueue.pop(msg)) {
            tempMsgs.push_back(msg);
        }

        // m5/m10: Record live MIDI events with tick timestamps (MIDI thread).
        // All events in mLiveMidiQueue are live keyboard events (timestamp == 0)
        // — the audio thread pushes only those here.
        if (mRecorder.isRecording() && !tempMsgs.empty()) {
            int64_t recTick = mRecordTick.load(std::memory_order_relaxed);
            for (const auto& tm : tempMsgs) {
                RecordedMidiEvent recEvt;
                recEvt.tick = recTick;
                recEvt.status = tm.status;
                recEvt.data1 = tm.data1;
                recEvt.data2 = tm.data2;
                recEvt.trackId = 0;
                mRecorder.record(recEvt);
            }
        }

        // Poll (no condition variable — Fix #2). A short sleep bounds the CPU
        // cost; recording is not real-time critical.
        std::this_thread::sleep_for(std::chrono::milliseconds(2));
    }
}

void NativeEngine::startMidiThread() {
    if (mMidiThreadRunning.load(std::memory_order_acquire)) return;
    mMidiThreadRunning.store(true, std::memory_order_release);
    mMidiThread = std::thread(&NativeEngine::midiThreadFunc, this);
}

void NativeEngine::stopMidiThread() {
    if (!mMidiThreadRunning.load(std::memory_order_acquire)) return;
    mMidiThreadRunning.store(false, std::memory_order_release);
    // No notify_one (Fix #2) — the MIDI thread polls with a 2ms sleep, so it
    // exits within ~2ms of the flag being cleared.
    if (mMidiThread.joinable()) {
        mMidiThread.join();
    }
}

// Static wrapper for OboeOutput callback
void NativeEngine::onAudioFrameStatic(float* output, int32_t numFrames) {
    NativeEngine* inst = getInstance();
    if (inst) {
        inst->onAudioFrame(output, numFrames);
    }
}

// M5: static wrapper for the OboeOutput "on open" callback (worker thread).
// Handles a mid-session sample-rate change (update the transport + re-prepare
// the inactive synth slot at the new rate). No-op if the engine is not
// initialized or the rate is unchanged.
void NativeEngine::onOpenStatic(int32_t newRate) {
    NativeEngine* inst = getInstance();
    if (inst) {
        inst->handleSampleRateChange(static_cast<int>(newRate));
    }
}
