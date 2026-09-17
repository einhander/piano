#pragma once

#include "model/TransportState.h"
#include "TimedMidiEvent.h"
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

};

class ClipScheduler {
public:
    ClipScheduler();
    ~ClipScheduler();

    void init(TransportState* transport);
    int32_t collectDueEvents(int64_t beginFrame, int64_t endFrame, TimedMidiEvent* output, int32_t capacity);

    // Audio-thread-only lifecycle operations.
    void activateSlot(int32_t slot, ClipData* clip);
    void deactivateSlot(int32_t slot);

    // Process — called from audio callback
    // Scans clips for events that should fire at current tick position

    // Start/stop scheduling
    void start();
    void stop();
    bool isRunning() const { return mRunning.load(); }

    // [perf]: number of clips currently in the scheduler (benign atomic read,
    // safe from a worker thread). Surfaced in the 1 Hz [perf] line.
    int32_t getActiveClipCount() const { return mClipCount.load(); }

private:
    TransportState* mTransport = nullptr;
    std::atomic<bool> mRunning{false};

    // Pre-allocated clip storage — atomic pointers prevent data race between
    // UI thread (addClip/removeClip) and audio thread (process).
    static constexpr int32_t kMaxClips = 64;
    struct ClipSlot {
        std::atomic<ClipData*> clip{nullptr};
    };
    struct ClipRuntime {
        int32_t nextEventIndex = 0;
        int32_t loopIndex = 0;
        struct ActiveNote { uint8_t channel = 0; uint8_t note = 0; } activeNotes[128];
        int32_t activeNoteCount = 0;
        uint32_t nextOrder = 0;
    };
    ClipSlot mClips[kMaxClips];
    std::atomic<int32_t> mClipCount{0};

    // Per-clip last-fired event index — prevents event re-firing within same callback window
    int32_t mLastFiredEventIndex[kMaxClips];
    ClipRuntime mRuntime[kMaxClips];
};
