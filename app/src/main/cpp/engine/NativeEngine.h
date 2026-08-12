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
#include "engine/Mixer.h"
#include "engine/MasterBus.h"
#include "engine/LaunchQuantizer.h"
#include "engine/MidiRecorder.h"

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

    // Launch quantization
    void setQuantizationGrid(int32_t grid);
    int32_t getQuantizationGrid() const;
    bool isLaunchPending() const;
    void acknowledgeLaunch();
    int64_t scheduleLaunch(int32_t sceneId, int32_t grid, int64_t currentFrame);

    // Scene navigation
    void registerScene(int32_t sceneId, const char* name);
    int32_t nextScene() const;
    int32_t previousScene() const;
    int32_t getSceneCount() const;

    // Launch queue
    bool queueSceneLaunch(int32_t sceneId, int64_t targetFrame);
    int32_t getLaunchQueueDepth() const;

    // Clip transport sync
    void setClipTransportSync(int32_t clipId, bool enabled);
    void setClipStartTick(int32_t clipId, int64_t startTick);
    void setClipEndTick(int32_t clipId, int64_t endTick);
    void setClipLoop(int32_t clipId, bool loop);

    // Mixer controls (atomic, safe for UI thread)
    void setTrackVolume(int trackId, float volume);
    void setTrackPan(int trackId, float pan);
    void setTrackMute(int trackId, bool mute);
    void setTrackSolo(int trackId, bool solo);
    float getTrackPeakMeter(int trackId) const;

    // Master bus controls
    void setMasterVolume(float volume);
    float getMasterPeakMeter() const;

    // Project loading (called from worker thread, NOT audio callback)
    void loadProject(const char* json);

    // Clip scheduling
    void addClip(int32_t clipId, int32_t trackId, int64_t startTick, int64_t lengthTicks,
                 const uint8_t* events, int32_t eventCount);
    void removeClip(int32_t clipId);

    // Count-in metronome: play clicks before recording starts
    // beats: number of count-in beats (default 4)
    // Returns the frame when recording should start
    int64_t startCountIn(int beats = 4);
    bool isCountingIn() const { return mCountingIn.load(); }
    int64_t getCountInEndFrame() const { return mCountInEndFrame.load(); }

    // Recording control
    void startRecording();
    void stopRecording();
    void setRecordArm(int trackId, bool armed);
    void setOverdub(bool overdub);
    bool isRecording() const;
    const std::vector<RecordedMidiEvent>& getRecordedEvents();

    // MIDI export
    bool writeMidiFile(const char* filePath,
                       const std::vector<RecordedMidiEvent>& events,
                       int ppq, uint32_t tempo);

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

    // Count-in metronome helpers
    void playCountInClick(int64_t frame);
    bool shouldPlayCountInClick(int64_t frame) const;

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
    LaunchQuantizer mLaunchQuantizer;

    // Mixer + MasterBus (audio mixing pipeline)
    Mixer mMixer;
    MasterBus mMasterBus;

    // Clip storage (owned by NativeEngine, safe for audio thread access)
    static constexpr int32_t kMaxClips = 64;
    ClipScheduler::ClipData mClips[kMaxClips];
    std::atomic<int32_t> mClipCount{0};

    // Pre-allocated synth render buffer (avoids stack allocation in audio callback)
    static constexpr int32_t kMaxSynthFrames = 2048;
    float mSynthBuffer[kMaxSynthFrames * 2];  // stereo float

    // Count-in metronome state
    std::atomic<bool> mCountingIn{false};
    int64_t mCountInEndFrame{0};
    int mCountInBeats{4};
    int64_t mCountInStartFrame{0};
    int mCountInClickIndex{0};

    // Recording state
    MidiRecorder mRecorder;
    std::atomic<bool> mRecordArmed[16];  // per-track record arm (channels 0-15)
};