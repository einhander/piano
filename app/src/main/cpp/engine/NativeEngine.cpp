#include "NativeEngine.h"
#include "audio/OboeOutput.h"
#include "synth/FluidSynthEngine.h"
#include "realtime/MidiQueue.h"
#include "model/TransportState.h"
#include "engine/Sequencer.h"
#include "engine/SceneManager.h"
#include "engine/ClipScheduler.h"

#include <atomic>

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

    // Register audio frame callback with OboeOutput
    OboeOutput::setAudioFrameCallback(&NativeEngine::onAudioFrameStatic);

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
    if (!inst) return oboe::Result::ErrorNullPointer;
    return inst->start();
}

oboe::Result NativeEngine::stopAudio() {
    OboeOutput* inst = OboeOutput::getInstance();
    if (!inst) return oboe::Result::ErrorNullPointer;
    return inst->stop();
}

bool NativeEngine::isAudioPlaying() const {
    OboeOutput* inst = OboeOutput::getInstance();
    if (!inst) return false;
    oboe::StreamState state = inst->getState();
    return state == oboe::StreamState::Open
        || state == oboe::StreamState::Starting
        || state == oboe::StreamState::Started
        || state == oboe::StreamState::Running;
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

int NativeEngine::getSampleRate() const {
    return mSampleRate;
}

int NativeEngine::getUnderrunCount() const {
    OboeOutput* inst = OboeOutput::getInstance();
    if (!inst) return 0;
    return static_cast<int>(inst->getUnderrunCount());
}

void NativeEngine::enqueueMidiMessage(uint8_t status, uint8_t data1, uint8_t data2, int64_t timestamp) {
    MidiMessage msg;
    msg.status = status;
    msg.data1 = data1;
    msg.data2 = data2;
    msg.timestamp = timestamp;
    mMidiQueue.push(msg);
}

void NativeEngine::processMidiQueue() {
    // Drain live MIDI from mMidiQueue into mLiveMidiQueue for async processing.
    // This keeps the audio callback free of FluidSynth C API calls which are
    // NOT real-time safe (voice allocation may lock internal mutexes).
    MidiMessage msg;
    while (mMidiQueue.pop(msg)) {
        if (!mLiveMidiQueue.push(msg)) {
            mDroppedCount.fetch_add(1, std::memory_order_relaxed);
        }
    }
    // Wake MIDI thread if it is idle
    mMidiCV.notify_one();
}

void NativeEngine::onAudioFrame(float* output, int numFrames) {
    // Process pending MIDI messages from external input
    processMidiQueue();

    // Process sequencer/clip scheduler events
    mSequencer.processFrame();
    mClipScheduler.process();

    // Advance frame position
    mTransport.framePosition.fetch_add(numFrames, std::memory_order_release);

    // Render via FluidSynth
    if (mSynth) {
        mSynth->render(output, numFrames);
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

NativeEngine* NativeEngine::getInstance() {
    return sInstance.load(std::memory_order_acquire);
}

// MIDI thread — runs outside audio callback, processes live MIDI into FluidSynth
void NativeEngine::midiThreadFunc() {
    while (mMidiThreadRunning.load(std::memory_order_acquire)) {
        // Drain all pending live MIDI messages into FluidSynth
        if (mSynth) {
            mSynth->processLiveMidi(&mLiveMidiQueue);
        }

        // Wait for new messages or shutdown signal
        std::unique_lock<std::mutex> lock(mMidiMutex);
        mMidiCV.wait_for(lock, std::chrono::milliseconds(2), [this] {
            return !mMidiThreadRunning.load(std::memory_order_acquire)
                || mLiveMidiQueue.size() > 0;
        });
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
    mMidiCV.notify_one();
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