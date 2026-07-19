#pragma once

#include <fluidsynth.h>
#include "realtime/MidiQueue.h"
#include <atomic>
#include <cstdint>
#include <unordered_map>

// FluidSynth adapter — renders PCM from MIDI events
// NOT using FluidSynth audio driver, only library mode
// Each track maps to one MIDI channel
class FluidSynthEngine {
public:
    FluidSynthEngine();
    ~FluidSynthEngine();

    // Non-copyable
    FluidSynthEngine(const FluidSynthEngine&) = delete;
    FluidSynthEngine& operator=(const FluidSynthEngine&) = delete;

    // Initialize FluidSynth (must be called before use)
    bool init(int sampleRate, int bufferSize);

    // Load SoundFont 2 file
    // Returns synthID on success, -1 on failure
    int loadSoundFont(const char* filePath);

    // Unload all SoundFonts
    void unloadSoundFonts();

    // Render PCM float buffer (stereo)
    // Called from audio callback — NO allocations
    void render(float* output, int numFrames);

    // MIDI events — all safe for audio thread
    void noteOn(int channel, int note, int velocity);
    void noteOff(int channel, int note);
    void controlChange(int channel, int controller, int value);
    void programChange(int channel, int program);
    void pitchBend(int channel, int value);  // 0-16383
    void channelPressure(int channel, int value);

    // Panic: all notes off, all controllers reset
    void panic();

    // Process live MIDI from queue — called from MIDI thread (NOT audio callback)
    void processLiveMidi(MidiQueue* queue);

    // Set polyphony
    void setPolyphony(int polyphony);

    // Set master gain
    void setMasterGain(float gain);

    // Get sample rate
    int getSampleRate() const { return mSampleRate; }

    // Check if initialized
    bool isInitialized() const { return mInitialized; }

private:
    fluid_synth_t* mSynth = nullptr;
    fluid_settings_t* mSettings = nullptr;
    int mSampleRate = 48000;
    int mBufferSize = 0;
    std::atomic<bool> mInitialized{false};
};