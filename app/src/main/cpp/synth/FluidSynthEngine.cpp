#include "FluidSynthEngine.h"
#include <fluidsynth/synth.h>
#include <cmath>
#include <cstring>
#include <vector>

FluidSynthEngine::FluidSynthEngine() = default;
FluidSynthEngine::~FluidSynthEngine() {
    std::lock_guard<std::mutex> lock(mSynthMutex);
    if (mSynth) {
        delete_fluid_synth(mSynth);
        mSynth = nullptr;
    }
    if (mSettings) {
        delete_fluid_settings(mSettings);
        mSettings = nullptr;
    }
}

bool FluidSynthEngine::init(int sampleRate, int bufferSize) {
    mSettings = new_fluid_settings();
    if (!mSettings) {
        return false;
    }

    fluid_settings_setnum(mSettings, "synth.sample-rate", sampleRate);
    fluid_settings_setint(mSettings, "synth.cpu-cores", 1);
    fluid_settings_setint(mSettings, "audio.periods", 2);

    mSampleRate = sampleRate;
    mBufferSize = bufferSize;

    mSynth = new_fluid_synth(mSettings);
    if (!mSynth) {
        return false;
    }

    // Disable FluidSynth audio driver — we render PCM ourselves via write_float
    fluid_settings_setstr(mSettings, "audio.driver", "none");

    mInitialized.store(true);
    return true;
}

int FluidSynthEngine::loadSoundFont(const char* filePath) {
    if (!mInitialized.load()) {
        return -1;
    }

    std::lock_guard<std::mutex> lock(mSynthMutex);
    if (!mSynth) {
        return -1;
    }

    // Load SF2 — returns synthID, -1 on error
    // Note: This is NOT safe for audio thread, must be called from worker thread
    int sfId = fluid_synth_sfload(mSynth, filePath, 1);
    if (sfId >= 0) {
        { std::lock_guard<std::mutex> lock2(mSfPathMutex); mLoadedSfPath = filePath; }
    }
    return sfId;
}

void FluidSynthEngine::unloadSoundFonts() {
    std::lock_guard<std::mutex> lock(mSynthMutex);
    if (!mSynth) return;

    { std::lock_guard<std::mutex> lock2(mSfPathMutex); mLoadedSfPath.clear(); }

    // Unload all soundfonts
    // This is NOT safe for audio thread
    while (fluid_synth_sfcount(mSynth) > 0) {
        fluid_sfont_t *sfont = fluid_synth_get_sfont(mSynth, 0);
        if (!sfont) break;
        int id = fluid_sfont_get_id(sfont);
        fluid_synth_sfunload(mSynth, id, 1);
    }
}

void FluidSynthEngine::render(float* output, int numFrames) {
    if (!mInitialized.load()) {
        std::memset(output, 0, numFrames * 2 * sizeof(float));
        return;
    }

    std::lock_guard<std::mutex> lock(mSynthMutex);
    if (!mSynth) {
        std::memset(output, 0, numFrames * 2 * sizeof(float));
        return;
    }

    // Render stereo float buffer directly
    // fluid_synth_write_float renders to float buffer in-place
    fluid_synth_write_float(mSynth, numFrames, output, 0, 2, output, 1, 2);
}

void FluidSynthEngine::noteOn(int channel, int note, int velocity) {
    if (!mInitialized.load()) return;
    std::lock_guard<std::mutex> lock(mSynthMutex);
    if (!mSynth) return;
    fluid_synth_noteon(mSynth, channel, note, velocity);
}

void FluidSynthEngine::noteOff(int channel, int note) {
    if (!mInitialized.load()) return;
    std::lock_guard<std::mutex> lock(mSynthMutex);
    if (!mSynth) return;
    fluid_synth_noteoff(mSynth, channel, note);
}

void FluidSynthEngine::controlChange(int channel, int controller, int value) {
    if (!mInitialized.load()) return;
    std::lock_guard<std::mutex> lock(mSynthMutex);
    if (!mSynth) return;
    fluid_synth_cc(mSynth, channel, controller, value);
}

void FluidSynthEngine::programChange(int channel, int program) {
    if (!mInitialized.load()) return;
    std::lock_guard<std::mutex> lock(mSynthMutex);
    if (!mSynth) return;
    fluid_synth_program_change(mSynth, channel, program);
}

void FluidSynthEngine::pitchBend(int channel, int value) {
    if (!mInitialized.load()) return;
    std::lock_guard<std::mutex> lock(mSynthMutex);
    if (!mSynth) return;
    fluid_synth_pitch_bend(mSynth, channel, value);
}

void FluidSynthEngine::channelPressure(int channel, int value) {
    if (!mInitialized.load()) return;
    std::lock_guard<std::mutex> lock(mSynthMutex);
    if (!mSynth) return;
    fluid_synth_channel_pressure(mSynth, channel, value);
}

void FluidSynthEngine::panic() {
    if (!mInitialized.load()) return;
    std::lock_guard<std::mutex> lock(mSynthMutex);
    if (!mSynth) return;

    // Send panic commands to FluidSynth
    for (int ch = 0; ch < 16; ch++) {
        fluid_synth_all_notes_off(mSynth, ch);
        fluid_synth_all_sounds_off(mSynth, ch);
        fluid_synth_cc(mSynth, ch, 123, 0);  // MIDI reset
        fluid_synth_cc(mSynth, ch, 120, 0);  // All notes off
        fluid_synth_cc(mSynth, ch, 64, 0);   // Sustain off
    }
}

void FluidSynthEngine::setPolyphony(int polyphony) {
    if (!mInitialized.load()) return;
    std::lock_guard<std::mutex> lock(mSynthMutex);
    if (!mSynth) return;
    fluid_synth_set_polyphony(mSynth, polyphony);
}

void FluidSynthEngine::setMasterGain(float gain) {
    if (!mInitialized.load()) return;
    std::lock_guard<std::mutex> lock(mSynthMutex);
    if (!mSynth) return;
    fluid_synth_set_gain(mSynth, gain);
}

int FluidSynthEngine::getPolyphony() const {
    std::lock_guard<std::mutex> lock(mSynthMutex);
    if (!mSynth) return 0;
    return fluid_synth_get_polyphony(mSynth);
}

float FluidSynthEngine::getMasterGain() const {
    std::lock_guard<std::mutex> lock(mSynthMutex);
    if (!mSynth) return 0.0f;
    return fluid_synth_get_gain(mSynth);
}

int FluidSynthEngine::getSoundFontCount() const {
    std::lock_guard<std::mutex> lock(mSynthMutex);
    if (!mSynth) return 0;
    return fluid_synth_sfcount(mSynth);
}

std::string FluidSynthEngine::getSoundFontPath() const {
    std::lock_guard<std::mutex> lock(mSfPathMutex);
    return mLoadedSfPath;
}

// Called from dedicated MIDI thread — NOT from audio callback.
// Locks mSynthMutex to serialize with audio callback render() and settings changes.
void FluidSynthEngine::processLiveMidi(MidiQueue* queue) {
    if (!mInitialized.load() || !queue) return;

    // Pop all messages from queue first (lock-free queue, no lock needed)
    // Then lock mSynthMutex once to batch all FluidSynth calls
    std::vector<MidiMessage> batch;
    MidiMessage msg;
    while (queue->pop(msg)) {
        batch.push_back(msg);
    }
    if (batch.empty()) return;

    std::lock_guard<std::mutex> lock(mSynthMutex);
    if (!mSynth) return;

    for (const auto& m : batch) {
        uint8_t status = m.status;
        uint8_t type = status & 0xF0;
        uint8_t channel = status & 0x0F;

        switch (type) {
            case 0x90: // Note On / Note Off
                if (m.data2 > 0) {
                    fluid_synth_noteon(mSynth, channel, m.data1, m.data2);
                } else {
                    fluid_synth_noteoff(mSynth, channel, m.data1);
                }
                break;
            case 0x80: // Note Off
                fluid_synth_noteoff(mSynth, channel, m.data1);
                break;
            case 0xA0: // Polyphonic Aftertouch
                fluid_synth_key_pressure(mSynth, channel, m.data1, m.data2);
                break;
            case 0xB0: // Control Change
                fluid_synth_cc(mSynth, channel, m.data1, m.data2);
                break;
            case 0xC0: // Program Change
                fluid_synth_program_change(mSynth, channel, m.data1);
                break;
            case 0xD0: // Channel Aftertouch
                fluid_synth_channel_pressure(mSynth, channel, m.data1);
                break;
            case 0xE0: // Pitch Bend
                {
                    int16_t value = static_cast<int16_t>(
                        (m.data2 << 7) | m.data1
                    );
                    fluid_synth_pitch_bend(mSynth, channel, value);
                }
                break;
        }
    }
}