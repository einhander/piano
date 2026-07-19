#pragma once

#include <atomic>
#include <cstdint>
#include <thread>
#include <mutex>
#include <condition_variable>
#include <chrono>

#include "realtime/MidiQueue.h"
#include "model/TransportState.h"
#include "engine/Sequencer.h"
#include "engine/SceneManager.h"
#include "engine/ClipScheduler.h"

class OboeOutput;
class FluidSynthEngine;

class NativeEngine {
public:
    NativeEngine();
    ~NativeEngine();
    NativeEngine(const NativeEngine&) = delete;
    NativeEngine& operator=(const NativeEngine&) = delete;

    // Lifecycle
    bool init(int sampleRate, int bufferSize);
    void shutdown();

    // Audio output control
    oboe::Result startAudio();
    oboe::Result stopAudio();
    bool isAudioPlaying() const;

    // SoundFont
    int loadSoundFont(const char* filePath);
    void unloadSoundFonts();

    // MIDI events — from JNI or MIDI router
    void noteOn(int channel, int note, int velocity);
    void noteOff(int channel, int note);
    void controlChange(int channel, int controller, int value);
    void programChange(int channel, int program);
    void pitchBend(int channel, int value);
    void channelPressure(int channel, int value);

    // Panic
    void panic();

    // Master controls
    void setMasterGain(float gain);
    void setPolyphony(int polyphony);

    // Transport control
    void setBPM(double bpm);
    void setTransportState(int state);  // 0=Stopped, 1=Playing, 2=Paused
    double getCurrentTick() const;
    int64_t getFramePosition() const;

    // Scene management
    void switchScene(int32_t sceneId);
    int32_t currentSceneId() const;
    bool hasSceneChanged() const;
    void acknowledgeSceneChange();

    // Project loading (called from worker thread, NOT audio callback)
    void loadProject(const char* json);

    // Clip scheduling
    void addClip(int32_t clipId, int32_t trackId, int64_t startTick, int64_t lengthTicks,
                 const uint8_t* events, int32_t eventCount);
    void removeClip(int32_t clipId);

    // Getters
    int getSampleRate() const;
    int getUnderrunCount() const;

    // Callback hook — called from OboeOutput audio callback
    // MUST be real-time safe: no allocations, no locks, no syscalls
    void onAudioFrame(float* output, int numFrames);

    // MIDI queue — called from audio callback (real-time safe)
    void enqueueMidiMessage(uint8_t status, uint8_t data1, uint8_t data2, int64_t timestamp);
    void processMidiQueue();

    // Static wrapper for OboeOutput callback registration
    static void onAudioFrameStatic(float* output, int32_t numFrames);

    static NativeEngine* getInstance();

private:
    // MIDI thread — decouples FluidSynth calls from audio callback
    void midiThreadFunc();
    void startMidiThread();
    void stopMidiThread();

    static std::atomic<NativeEngine*> sInstance;
    FluidSynthEngine* mSynth = nullptr;
    MidiQueue mMidiQueue{4096};
    MidiQueue mLiveMidiQueue{4096};  // Live MIDI → async FluidSynth processing
    std::thread mMidiThread;
    std::atomic<bool> mMidiThreadRunning{false};
    std::atomic<int32_t> mDroppedCount{0};
    std::mutex mMidiMutex;
    std::condition_variable mMidiCV;
    int mSampleRate = 48000;
    int mBufferSize = 512;
    std::atomic<bool> mInitialized{false};

    // Transport + Sequencer (Phase 5)
    TransportState mTransport;
    Sequencer mSequencer;
    SceneManager mSceneManager;
    ClipScheduler mClipScheduler;

    // Clip storage (owned by NativeEngine, safe for audio thread access)
    static constexpr int32_t kMaxClips = 64;
    ClipScheduler::ClipData mClips[kMaxClips];
    std::atomic<int32_t> mClipCount{0};
};