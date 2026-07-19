#pragma once

#include "model/TransportState.h"
#include "realtime/MidiQueue.h"
#include <cstdint>
#include <atomic>

// Represents a scheduled MIDI event from a clip
struct ScheduledEvent {
    std::atomic<int64_t> framePosition{-1};  // -1 = empty, >=0 = scheduled frame
    uint8_t status = 0;
    uint8_t data1 = 0;
    uint8_t data2 = 0;
};

class Sequencer {
public:
    Sequencer();
    ~Sequencer();

    // Initialize with transport reference
    void init(TransportState* transport);

    // Set MIDI queue for event dispatch
    void setMidiQueue(MidiQueue* queue);

    // Add a scheduled event (from clip data, called from non-audio thread)
    void scheduleEvent(int64_t framePosition, uint8_t status, uint8_t data1, uint8_t data2);

    // Process scheduled events — called from audio callback
    // Returns true if any events were processed
    bool processFrame();

    // Start/stop scheduling
    void start();
    void stop();
    bool isRunning() const { return mRunning.load(); }

    // Get transport reference
    TransportState* transport() const { return mTransport; }

private:
    TransportState* mTransport = nullptr;
    MidiQueue* mMidiQueue = nullptr;
    std::atomic<bool> mRunning{false};

    // Pre-allocated event buffer — no dynamic allocation in audio thread
    static constexpr int32_t kMaxScheduledEvents = 256;
    ScheduledEvent mEvents[kMaxScheduledEvents];
    std::atomic<int32_t> mEventCount{0};
};