#include "FluidSynthEngine.h"
#include <cmath>
#include <cstring>

FluidSynthEngine::FluidSynthEngine() = default;
FluidSynthEngine::~FluidSynthEngine() {
    if (mSynth) {
        delete_fluid_synth(mSynth);
    }
    if (mSettings) {
        delete_fluid_settings(mSettings);
    }
}

bool FluidSynthEngine::init(int sampleRate, int bufferSize) {
    mSettings = new_fluid_settings();
    if (!mSettings) {
        return false;
    }

    fluid_settings_setint(mSettings, SYNTH_SAMPLE_RATE, sampleRate);
    fluid_settings_setint(mSettings, SYNTH_AUDIO_RATE, sampleRate);
    fluid_settings_setint(mSettings, SYNTH_THREAD_POOL_SIZE, 1);
    fluid_settings_setint(mSettings, SYNTH_AUDIO_BUFFERS, 2);
    fluid_settings_setint(mSettings, SYNTH_PAD_VOICES, 0);

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
    if (!mSynth || !mInitialized.load()) {
        return -1;
    }

    // Load SF2 — returns synthID, -1 on error
    // Note: This is NOT safe for audio thread, must be called from worker thread
    int sfId = fluid_synth_sfload(mSynth, filePath, 1);
    return sfId;
}

void FluidSynthEngine::unloadSoundFonts() {
    if (!mSynth) return;

    // Unload all soundfonts
    // This is NOT safe for audio thread
    while (fluid_synth_sfcount(mSynth) > 0) {
        int id = fluid_synth_sfid_list(mSynth, nullptr, 0);
        if (id < 0) break;
        fluid_synth_sfunload(mSynth, id, 1);
    }
}

void FluidSynthEngine::render(float* output, int numFrames) {
    if (!mSynth || !mInitialized.load()) {
        std::memset(output, 0, numFrames * 2 * sizeof(float));
        return;
    }

    // Render stereo float buffer directly
    // fluid_synth_write_float renders to float buffer in-place
    fluid_synth_write_float(mSynth, numFrames, output, 0, 2, output, 1, 2);
}

void FluidSynthEngine::noteOn(int channel, int note, int velocity) {
    if (!mSynth || !mInitialized.load()) return;
    fluid_synth_noteon(mSynth, channel, note, velocity);
}

void FluidSynthEngine::noteOff(int channel, int note) {
    if (!mSynth || !mInitialized.load()) return;
    fluid_synth_noteoff(mSynth, channel, note);
}

void FluidSynthEngine::controlChange(int channel, int controller, int value) {
    if (!mSynth || !mInitialized.load()) return;
    fluid_synth_cc(mSynth, channel, controller, value);
}

void FluidSynthEngine::programChange(int channel, int program) {
    if (!mSynth || !mInitialized.load()) return;
    fluid_synth_program_change(mSynth, channel, program);
}

void FluidSynthEngine::pitchBend(int channel, int value) {
    if (!mSynth || !mInitialized.load()) return;
    fluid_synth_pitch_bend(mSynth, channel, value);
}

void FluidSynthEngine::channelPressure(int channel, int value) {
    if (!mSynth || !mInitialized.load()) return;
    fluid_synth_channel_pressure(mSynth, channel, value);
}

void FluidSynthEngine::panic() {
    if (!mSynth || !mInitialized.load()) return;

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
    if (!mSynth || !mInitialized.load()) return;
    fluid_synth_set_polyphony(mSynth, polyphony);
}

void FluidSynthEngine::setMasterGain(float gain) {
    if (!mSynth || !mInitialized.load()) return;
    fluid_synth_set_gain(mSynth, gain);
}

// Called from dedicated MIDI thread — NOT from audio callback, so FluidSynth
// C API calls are real-time safe (no audio thread pressure).
void FluidSynthEngine::processLiveMidi(MidiQueue* queue) {
    if (!mSynth || !mInitialized.load() || !queue) return;

    MidiMessage msg;
    while (queue->pop(msg)) {
        uint8_t status = msg.status;
        uint8_t type = status & 0xF0;
        uint8_t channel = status & 0x0F;

        switch (type) {
            case 0x90: // Note On / Note Off
                if (msg.data2 > 0) {
                    fluid_synth_noteon(mSynth, channel, msg.data1, msg.data2);
                } else {
                    fluid_synth_noteoff(mSynth, channel, msg.data1);
                }
                break;
            case 0x80: // Note Off
                fluid_synth_noteoff(mSynth, channel, msg.data1);
                break;
            case 0xA0: // Polyphonic Aftertouch
                fluid_synth_polyphonic_aftertouch(mSynth, channel, msg.data1, msg.data2);
                break;
            case 0xB0: // Control Change
                fluid_synth_cc(mSynth, channel, msg.data1, msg.data2);
                break;
            case 0xC0: // Program Change
                fluid_synth_program_change(mSynth, channel, msg.data1);
                break;
            case 0xD0: // Channel Aftertouch
                fluid_synth_channel_pressure(mSynth, channel, msg.data1);
                break;
            case 0xE0: // Pitch Bend
                {
                    int16_t value = static_cast<int16_t>(
                        (msg.data2 << 7) | msg.data1
                    );
                    fluid_synth_pitch_bend(mSynth, channel, value);
                }
                break;
        }
    }
}