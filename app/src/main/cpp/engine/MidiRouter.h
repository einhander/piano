#pragma once

#include "realtime/MidiQueue.h"
#include <cstdint>
#include <array>

// Routes MIDI messages to tracks
// Supports: channel filter, transpose, velocity scaling, channel remap
struct MidiRoute {
    int trackId;
    int inputChannel;  // -1 = all channels
    int outputChannel; // MIDI channel (0-15)
    int transpose;     // -24 to +24
    int minNote;       // 0-127
    int maxNote;       // 0-127
    float velocityScale; // 0.0 to 2.0
    bool enabled;
};

class MidiRouter {
public:
    MidiRouter();
    ~MidiRouter();

    // Add a route (each track gets one route)
    void addRoute(int trackId, const MidiRoute& route);

    // Remove a route
    void removeRoute(int trackId);

    // Process incoming MIDI messages
    // Writes results into caller-provided output buffer (max 16 routes)
    // Safe for audio thread — no heap allocation
    struct MidiOutput {
        int channel;
        int note;
        int velocity;
        int controller;
        int value;
        bool isNoteOn;
        bool isNoteOff;
        bool isControlChange;
        bool isProgramChange;
        bool isPitchBend;
    };

    // Process a single message; writes up to 16 outputs into caller buffer.
    // Returns number of outputs written.
    int process(const MidiMessage& msg, MidiOutput* outputs, int maxOutputs);

    // Panic: send all notes off on all channels
    void panic();

private:
    std::array<MidiRoute, 16> mRoutes; // One route per MIDI channel
    int mDrumChannel = 9; // Channel 10 = index 9
};