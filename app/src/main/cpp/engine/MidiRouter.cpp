#include "MidiRouter.h"
#include <algorithm>

MidiRouter::MidiRouter() {
    mRoutes.fill({});
    for (auto& route : mRoutes) {
        route.enabled = false;
        route.inputChannel = -1;
        route.outputChannel = 0;
        route.transpose = 0;
        route.minNote = 0;
        route.maxNote = 127;
        route.velocityScale = 1.0f;
    }
}

MidiRouter::~MidiRouter() = default;

void MidiRouter::addRoute(int trackId, const MidiRoute& route) {
    // Find a route for this track or create one
    for (auto& r : mRoutes) {
        if (r.trackId == trackId) {
            r = route;
            r.enabled = true;
            return;
        }
    }
    // Find first disabled route
    for (auto& r : mRoutes) {
        if (!r.enabled) {
            r = route;
            r.enabled = true;
            return;
        }
    }
}

void MidiRouter::removeRoute(int trackId) {
    for (auto& r : mRoutes) {
        if (r.trackId == trackId) {
            r.enabled = false;
            return;
        }
    }
}

int MidiRouter::process(const MidiMessage& msg, MidiOutput* outputs, int maxOutputs) {
    int outputCount = 0;

    // Extract MIDI status byte
    uint8_t status = msg.status & 0xF0;
    uint8_t channel = msg.status & 0x0F;

    for (const auto& route : mRoutes) {
        if (!route.enabled) continue;
        if (route.inputChannel != -1 && route.inputChannel != static_cast<int>(channel)) continue;

        MidiOutput out{};

        switch (status) {
            case 0x90: { // Note On
                out.isNoteOn = true;
                out.channel = route.outputChannel;
                out.note = route.transpose + static_cast<int>(msg.data1);
                out.velocity = static_cast<int>(msg.data2 * route.velocityScale);
                // Drop notes outside range, NOT clamp
                if (out.note < route.minNote || out.note > route.maxNote) continue;
                out.velocity = std::clamp(out.velocity, 0, 127);
                if (out.velocity == 0) {
                    out.isNoteOn = false;
                    out.isNoteOff = true;
                }
                if (outputCount < maxOutputs) outputs[outputCount++] = out;
                break;
            }
            case 0x80: { // Note Off
                out.isNoteOff = true;
                out.channel = route.outputChannel;
                out.note = route.transpose + static_cast<int>(msg.data1);
                // Drop notes outside range, NOT clamp
                if (out.note < route.minNote || out.note > route.maxNote) continue;
                if (outputCount < maxOutputs) outputs[outputCount++] = out;
                break;
            }
            case 0xB0: { // Control Change
                out.isControlChange = true;
                out.channel = route.outputChannel;
                out.controller = msg.data1;
                out.value = msg.data2;
                if (outputCount < maxOutputs) outputs[outputCount++] = out;
                break;
            }
            case 0xC0: { // Program Change
                out.isProgramChange = true;
                out.channel = route.outputChannel;
                out.value = msg.data1;
                if (outputCount < maxOutputs) outputs[outputCount++] = out;
                break;
            }
            case 0xE0: { // Pitch Bend
                out.isPitchBend = true;
                out.channel = route.outputChannel;
                out.value = static_cast<int>(msg.data1) | (static_cast<int>(msg.data2) << 7);
                if (outputCount < maxOutputs) outputs[outputCount++] = out;
                break;
            }
        }
    }

    return outputCount;
}

void MidiRouter::panic() {
    // Panic is handled at the synth level via NativeEngine::panic()
    // This method exists for API completeness
}