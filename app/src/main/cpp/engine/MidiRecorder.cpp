#include "MidiRecorder.h"
#include <algorithm>

MidiRecorder::MidiRecorder() = default;
MidiRecorder::~MidiRecorder() = default;

void MidiRecorder::start(int64_t startTick) {
    mStartTick = startTick;
    mRecording.store(true);
    clear();
}

void MidiRecorder::stop() {
    mRecording.store(false);
}

// NOTE: record() is called from MIDI input thread, NOT audio callback.
// std::vector is acceptable here. If moved to audio thread, replace with ring buffer.
void MidiRecorder::record(const RecordedMidiEvent& event) {
    if (!mRecording.load()) return;

    RecordedMidiEvent recorded = event;
    recorded.tick -= mStartTick; // Relative to start
    mEvents.push_back(recorded);
}

void MidiRecorder::clear() {
    mEvents.clear();
}

void MidiRecorder::quantize(int64_t quantizationTicks) {
    for (auto& event : mEvents) {
        event.tick = (event.tick / quantizationTicks) * quantizationTicks;
    }
    // Sort by tick
    std::sort(mEvents.begin(), mEvents.end(),
        [](const RecordedMidiEvent& a, const RecordedMidiEvent& b) {
            return a.tick < b.tick;
        });
}