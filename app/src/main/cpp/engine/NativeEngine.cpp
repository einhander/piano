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

int NativeEngine::getPolyphony() const {
    return mSynth ? mSynth->getPolyphony() : 0;
}

float NativeEngine::getMasterGain() const {
    return mSynth ? mSynth->getMasterGain() : 0.0f;
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

    // Process MIDI file player (real-time safe: pre-allocated slots, lock-free queues)
    mMidiFilePlayer.process(numFrames, mSampleRate, mTransport.framePosition.load(std::memory_order_acquire), &mLiveMidiQueue);

    // Process sequencer/clip scheduler events
    mSequencer.processFrame();
    mClipScheduler.process();

    // Process queued scene launches
    mSceneManager.processLaunchQueue(mTransport.framePosition.load(std::memory_order_acquire));

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

    // Render via FluidSynth into pre-allocated buffer (avoids stack allocation)
    // FluidSynth renders stereo PCM float
    int safeFrames = (numFrames > kMaxSynthFrames) ? kMaxSynthFrames : numFrames;
    if (mSynth) {
        mSynth->render(mSynthBuffer, safeFrames);
    } else {
        // Zero output if synth not initialized
        for (int i = 0; i < safeFrames * 2; i++) {
            mSynthBuffer[i] = 0.0f;
        }
    }

    // Route synth output through Mixer track 0 (stereo copy)
    float* track0 = mMixer.getTrackBuffer(0);
    std::memcpy(track0, mSynthBuffer, safeFrames * 2 * sizeof(float));

    // Reset meters at start of playback
    mMixer.resetMeters();
    mMasterBus.resetMeter();

    // Mix all tracks through Mixer into stereo output
    mMixer.mix(output, safeFrames);

    // Process through MasterBus (volume + soft clipper)
    mMasterBus.process(output, safeFrames);
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

// MIDI thread — runs outside audio callback, processes live MIDI into FluidSynth
void NativeEngine::midiThreadFunc() {
    while (mMidiThreadRunning.load(std::memory_order_acquire)) {
        // Drain all pending live MIDI messages from the queue into a temp buffer
        // (so we can feed both FluidSynth and the recorder without double-popping)
        std::vector<MidiMessage> tempMsgs;
        MidiMessage msg;
        while (mLiveMidiQueue.pop(msg)) {
            tempMsgs.push_back(msg);
        }

        // m5/m10: Record live MIDI events with tick timestamps (MIDI thread, vector push_back OK)
        // Convention: live keyboard events carry msg.timestamp == 0;
        // player events carry the tick (> 0). Record only timestamp == 0.
        if (mRecorder.isRecording() && !tempMsgs.empty()) {
            int64_t recTick = mRecordTick.load(std::memory_order_relaxed);
            for (const auto& tm : tempMsgs) {
                if (tm.timestamp != 0) continue; // skip player-originated events
                RecordedMidiEvent recEvt;
                recEvt.tick = recTick;
                recEvt.status = tm.status;
                recEvt.data1 = tm.data1;
                recEvt.data2 = tm.data2;
                recEvt.trackId = 0;
                mRecorder.record(recEvt);
            }
        }

        // m7: Feed FluidSynth with the drained batch directly (no re-push, no reorder)
        if (!tempMsgs.empty()) {
            if (mSynth) {
                mSynth->processLiveMidi(tempMsgs);
            }
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