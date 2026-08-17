#pragma once

#include <cstdint>
#include <vector>
#include <atomic>
#include <mutex>

// Records live MIDI input
struct RecordedMidiEvent {
    int64_t tick;      // Transport tick (not raw timestamp)
    uint8_t status;    // MIDI status byte (0x80-0xEF)
    uint8_t data1;
    uint8_t data2;
    uint8_t trackId;   // Source track/channel (0-15)
};

class MidiRecorder {
public:
    MidiRecorder();
    ~MidiRecorder();

    // Start recording
    void start(int64_t startTick);

    // Stop recording
    void stop();

    // Record a MIDI event (safe for MIDI thread; uses mutex for worker-thread safety)
    void record(const RecordedMidiEvent& event);

    // Check if recording
    bool isRecording() const { return mRecording.load(); }

    // Enable overdub mode — new events are appended to existing events
    void setOverdub(bool overdub);
    bool isOverdub() const { return mOverdub.load(); }

    // Get a copy of the recorded events (thread-safe: takes the internal lock)
    std::vector<RecordedMidiEvent> getEvents() const;

    // Number of recorded events (thread-safe: takes the internal lock)
    size_t eventCount() const;

    // Get combined events (existing + new) — used in overdub mode
    std::vector<RecordedMidiEvent> getCombinedEvents() const;

    // Clear recorded events
    void clear();

    // Quantize recorded events
    void quantize(int64_t quantizationTicks); // e.g., 960 for 1/4 note

private:
    std::atomic<bool> mRecording{false};
    std::atomic<bool> mOverdub{false};
    int64_t mStartTick = 0;
    std::vector<RecordedMidiEvent> mEvents;
    mutable std::mutex mMutex; // m8: protects mEvents from MIDI/worker thread races
};