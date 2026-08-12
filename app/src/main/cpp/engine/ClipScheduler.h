#pragma once

#include "model/TransportState.h"
#include "realtime/MidiQueue.h"
#include <cstdint>
#include <atomic>

// Represents a clip's MIDI data
struct ClipData {
    int32_t clipId;
    int32_t trackId;
    int64_t startTick;  // Where this clip starts in tick space
    int64_t lengthTicks;  // Clip length in ticks
    // MIDI events: pre-serialized as (tick, status, data1, data2)
    // MVP: fixed-size array
    static constexpr int32_t kMaxEvents = 1024;
    struct Event {
        int64_t tick;
        uint8_t status;
        uint8_t data1;
        uint8_t data2;
    } events[kMaxEvents];
    int32_t eventCount = 0;

    // Track active notes for loop boundary cleanup
    uint8_t mActiveNotes[128];
    int32_t mActiveNoteCount = 0;
};

class ClipScheduler {
public:
    ClipScheduler();
    ~ClipScheduler();

    void init(TransportState* transport, MidiQueue* midiQueue);

    // Add a clip (called from non-audio thread)
    void addClip(ClipData* clip);

    // Remove a clip
    void removeClip(int32_t clipId);

    // Process — called from audio callback
    // Scans clips for events that should fire at current tick position
    void process();

    // Start/stop scheduling
    void start();
    void stop();
    bool isRunning() const { return mRunning.load(); }

private:
    TransportState* mTransport = nullptr;
    MidiQueue* mMidiQueue = nullptr;
    std::atomic<bool> mRunning{false};

    // Pre-allocated clip storage — atomic pointers prevent data race between
    // UI thread (addClip/removeClip) and audio thread (process).
    static constexpr int32_t kMaxClips = 64;
    struct ClipSlot {
        std::atomic<ClipData*> clip{nullptr};
    };
    ClipSlot mClips[kMaxClips];
    std::atomic<int32_t> mClipCount{0};

    // Per-clip last-fired event index — prevents event re-firing within same callback window
    int32_t mLastFiredEventIndex[kMaxClips];
};