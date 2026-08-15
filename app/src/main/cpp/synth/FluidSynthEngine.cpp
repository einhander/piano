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

std::vector<InstrumentInfo> FluidSynthEngine::getInstruments() const {
    if (!mInitialized.load()) return {};
    std::vector<InstrumentInfo> result;
    std::lock_guard<std::mutex> lock(mSynthMutex);
    if (!mSynth) return result;
    // One-shot UI operation; a typical GM SF2 (128 presets) fits the initial
    // capacity without reallocation. Holding mSynthMutex for the enumeration
    // briefly blocks the audio callback — acceptable for an infrequent call.
    result.reserve(256);
    int sfCount = fluid_synth_sfcount(mSynth);
    for (int i = 0; i < sfCount; i++) {
        fluid_sfont_t* sfont = fluid_synth_get_sfont(mSynth, i);
        if (!sfont) continue;
        fluid_sfont_iteration_start(sfont);
        fluid_preset_t* preset;
        while ((preset = fluid_sfont_iteration_next(sfont)) != nullptr) {
            const char* name = fluid_preset_get_name(preset);
            result.push_back({name ? name : "",
                              fluid_preset_get_banknum(preset),
                              fluid_preset_get_num(preset)});
        }
    }
    return result;
}

bool FluidSynthEngine::setChannelProgram(int channel, int bank, int program) {
    if (!mInitialized.load()) return false;
    std::lock_guard<std::mutex> lock(mSynthMutex);
    if (!mSynth) return false;
    if (channel < 0 || channel > 15) return false;
    if (bank < 0) bank = 0;
    if (bank > 16383) bank = 16383;
    if (program < 0 || program > 127) program = 0;
    // fluid_synth_bank_select handles bank style (GM/GS/XG) and the full
    // 0-16383 range; raw CC0 only covers 0-127 and is style-dependent.
    // Note: in the default GS bank style, channel_type (drum/melodic) is not
    // updated by either this path or the CC0 path; it only affects the
    // substitute chosen when the preset is missing (see return value below).
    if (fluid_synth_bank_select(mSynth, channel, bank) != FLUID_OK) {
        return false;
    }
    // FLUID_FAILED means the (bank, program) preset is not in the loaded SF2
    // (e.g. SF2 swapped after enumeration) — a substitute was applied instead.
    if (fluid_synth_program_change(mSynth, channel, program) != FLUID_OK) {
        return false;
    }
    return true;
}

// Returns the requested (or substituted) bank/program — what the user selected,
// not necessarily the sounding preset after fallback. Live MIDI program changes
// (0xC0) also move this without any UI notification; the UI must re-sync on
// resume or after SF2 changes.
bool FluidSynthEngine::getChannelProgram(int channel, int& bank, int& program) const {
    if (!mInitialized.load()) return false;
    std::lock_guard<std::mutex> lock(mSynthMutex);
    if (!mSynth) return false;
    if (channel < 0 || channel > 15) return false;
    int sfontId = -1;
    if (fluid_synth_get_program(mSynth, channel, &sfontId, &bank, &program) != FLUID_OK) {
        return false;
    }
    return true;
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