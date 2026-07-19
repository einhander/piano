#pragma once

#include <cstdint>
#include <vector>
#include <atomic>

// Records live MIDI input
struct RecordedMidiEvent {
    int64_t tick;      // Transport tick (not raw timestamp)
    uint8_t status;
    uint8_t data1;
    uint8_t data2;
};

class MidiRecorder {
public:
    MidiRecorder();
    ~MidiRecorder();

    // Start recording
    void start(int64_t startTick);

    // Stop recording
    void stop();

    // Record a MIDI event (safe for audio thread)
    void record(const RecordedMidiEvent& event);

    // Check if recording
    bool isRecording() const { return mRecording.load(); }

    // Get recorded events
    const std::vector<RecordedMidiEvent>& getEvents() const { return mEvents; }

    // Clear recorded events
    void clear();

    // Quantize recorded events
    void quantize(int64_t quantizationTicks); // e.g., 960 for 1/4 note

private:
    std::atomic<bool> mRecording{false};
    int64_t mStartTick = 0;
    std::vector<RecordedMidiEvent> mEvents;
};