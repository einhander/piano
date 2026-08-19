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
    mBufferSize = bufferSize;

    bool ok = mSynth->init(sampleRate, bufferSize);
    if (!ok) {
        return false;
    }

    // Wire up transport + sequencer
    mTransport.sampleRate = sampleRate;
    mTransport.updateTicksPerFrame();
    mSequencer.init(&mTransport);
    mSequencer.setMidiQueue(&mMidiQueue);
    mClipScheduler.init(&mTransport, &mMidiQueue);
    mLaunchQuantizer.init(&mTransport);

    // Initialize Mixer and MasterBus
    // Size to kMaxSynthFrames (the safeFrames clamp in onAudioFrame), not the
    // hardcoded init bufferSize — the Oboe burst can exceed it (e.g. 960 in
    // shared mode), which would overflow the track/master buffers in the RT
    // callback.
    mMixer.init(16, kMaxSynthFrames);
    mMasterBus.init(kMaxSynthFrames);

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
    return mSampleRate;
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
void NativeEngine::updateSampleRate(int sampleRate) {
    if (sampleRate <= 0) return;
    mSampleRate = sampleRate;
    mTransport.sampleRate = sampleRate;
    mTransport.updateTicksPerFrame();
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
    if (newRate == mSampleRate) return;  // no change
    updateSampleRate(newRate);
    if (mSynth) {
        std::string sfPath = mSynth->getSoundFontPath();
        mSynth->reprepareAtNewRate(newRate, sfPath.empty() ? nullptr : sfPath.c_str());
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
    int safeFrames = (numFrames > kMaxSynthFrames) ? kMaxSynthFrames : numFrames;
    int64_t framePos = mTransport.framePosition.load(std::memory_order_acquire);

    // Process MIDI file player (real-time safe: pre-allocated slots, lock-free
    // queues). File events go to mMidiQueue (drained below, fed to the synth).
    mMidiFilePlayer.process(numFrames, mSampleRate, framePos, &mMidiQueue);

    // Process sequencer/clip scheduler events (→ mMidiQueue)
    mSequencer.processFrame();
    mClipScheduler.process();

    // Process queued scene launches
    mSceneManager.processLaunchQueue(framePos);

    // Advance frame position
    mTransport.framePosition.fetch_add(numFrames, std::memory_order_release);

    // Advance recording tick (for MIDI file tick timestamps on recorded events)
    // M5: double accumulator on audio thread + atomic int64_t for MIDI thread to read
    if (mRecorder.isRecording()) {
        double ticksPerFrame = (mTransport.bpm * mTransport.ppq) / (60.0 * mSampleRate);
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

        // Render the synth DIRECTLY into the mixer's track-0 buffer (no extra
        // full-buffer memcpy — Fix #9). The mixer reads track-0 in mix().
        float* track0 = mMixer.getTrackBuffer(0);
        mSynth->render(track0, safeFrames);

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

    // Process through MasterBus (volume + soft clipper)
    mMasterBus.process(output, safeFrames);

    // M4: when the Oboe buffer is larger than the synth/mixer buffers
    // (kMaxSynthFrames=2048), the tail of the output buffer is NOT written by
    // render/mix/process — it keeps the PREVIOUS callback's data (stale/ghost
    // audio). This is now reachable: the LatencyTuner grows the buffer up to
    // 8×burst (e.g. 20ms burst @48k = 960 → 8× = 7680 > 2048) precisely when
    // the device is struggling. Zero the tail so it plays silence.
    // (Output is interleaved stereo = 2 channels.)
    if (numFrames > safeFrames) {
        std::memset(output + safeFrames * 2, 0,
                    (numFrames - safeFrames) * 2 * sizeof(float));
    }
}

void NativeEngine::setBPM(double bpm) {
    mTransport.bpm = bpm;
    mTransport.updateTicksPerFrame();
}

void NativeEngine::setTransportState(int state) {
    mTransport.state.store(static_cast<TransportState::State>(state), std::memory_order_release);
}

double NativeEngine::getCurrentTick() const {
    return mTransport.currentTick();
}

int64_t NativeEngine::getFramePosition() const {
    return mTransport.framePosition.load(std::memory_order_acquire);
}

double NativeEngine::getBPM() const {
    return mTransport.bpm;
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
    (void)sceneId; // sceneId is used by queueSceneLaunch, not quantizer
    return mLaunchQuantizer.scheduleLaunch(static_cast<QuantizationGrid>(grid), currentFrame);
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

void NativeEngine::addClip(int32_t clipId, int32_t trackId, int64_t startTick, int64_t lengthTicks,
                           const uint8_t* events, int32_t eventCount) {
    if (!events || eventCount <= 0 || eventCount > ClipData::kMaxEvents) return;

    // Find slot (replace existing or find empty)
    int32_t replaceSlot = -1;  // Slot with matching clipId
    int32_t emptySlot = -1;    // First empty slot (clipId == 0)
    for (int32_t i = 0; i < kMaxClips; i++) {
        if (mClips[i].clipId == clipId) { replaceSlot = i; break; }
        if (mClips[i].clipId == 0 && emptySlot < 0) { emptySlot = i; }
    }
    int32_t slot = replaceSlot >= 0 ? replaceSlot : emptySlot;
    if (slot < 0) return;

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
    mClipScheduler.addClip(&mClips[slot]);

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
        if (mClips[i].clipId == clipId) {
            mClips[i].clipId = 0;
            mClips[i].eventCount = 0;
            mClipScheduler.removeClip(clipId);
            mClipCount.fetch_sub(1, std::memory_order_release);
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
    double framesPerBeat = (60.0 / mTransport.bpm) * mTransport.sampleRate;
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
    int clickFrames = static_cast<int>(0.05 * mTransport.sampleRate);
    if (clickFrames > kMaxSynthFrames) clickFrames = kMaxSynthFrames;

    for (int i = 0; i < clickFrames; i++) {
        double t = static_cast<double>(i) / mTransport.sampleRate;
        float sample = static_cast<float>(sin(2.0 * M_PI * 800.0 * t) * exp(-t * 40.0));
        mSynthBuffer[i * 2] = sample;
        mSynthBuffer[i * 2 + 1] = sample;
    }
}

bool NativeEngine::shouldPlayCountInClick(int64_t frame) const {
    if (!mCountingIn.load(std::memory_order_acquire)) return false;
    if (frame < mCountInStartFrame) return false;

    int64_t elapsed = frame - mCountInStartFrame;
    double framesPerBeat = (60.0 / mTransport.bpm) * mTransport.sampleRate;

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
int NativeEngine::loadMidiFileSlot(int slot, const char* filePath, float bpm, bool loop, int channel, bool startAfterLoad) {
    return mMidiFilePlayer.load(slot, filePath, bpm, loop, channel, startAfterLoad);
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
