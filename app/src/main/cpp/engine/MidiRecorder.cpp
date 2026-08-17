#include "MidiRecorder.h"
#include <algorithm>

MidiRecorder::MidiRecorder() = default;
MidiRecorder::~MidiRecorder() = default;

void MidiRecorder::start(int64_t startTick) {
    std::lock_guard<std::mutex> lock(mMutex);
    mStartTick = startTick;
    mRecording.store(true);
    if (!mOverdub.load()) {
        clear();
    }
}

void MidiRecorder::stop() {
    mRecording.store(false);
}

void MidiRecorder::setOverdub(bool overdub) {
    mOverdub.store(overdub);
}

// NOTE: record() is called from MIDI input thread, NOT audio callback.
// std::vector is acceptable here. Protected by mutex for worker-thread safety (m8).
void MidiRecorder::record(const RecordedMidiEvent& event) {
    if (!mRecording.load()) return;

    std::lock_guard<std::mutex> lock(mMutex);
    RecordedMidiEvent recorded = event;
    // m4: startTick is 0, so no subtraction needed.
    // Ticks are already relative to recording start (set by mRecordTick in onAudioFrame).
    mEvents.push_back(recorded);
}

// clear() is internal: only called from start(), which already holds mMutex
// (std::mutex is non-recursive — clear() must not lock).
void MidiRecorder::clear() {
    mEvents.clear();
}

std::vector<RecordedMidiEvent> MidiRecorder::getEvents() const {
    std::lock_guard<std::mutex> lock(mMutex);
    return mEvents;
}

size_t MidiRecorder::eventCount() const {
    std::lock_guard<std::mutex> lock(mMutex);
    return mEvents.size();
}

std::vector<RecordedMidiEvent> MidiRecorder::getCombinedEvents() const {
    // In overdub mode, return all events (existing + new are already combined)
    // In non-overdub mode, just return a copy
    std::lock_guard<std::mutex> lock(mMutex);
    return mEvents;
}

void MidiRecorder::quantize(int64_t quantizationTicks) {
    std::lock_guard<std::mutex> lock(mMutex);
    for (auto& event : mEvents) {
        event.tick = (event.tick / quantizationTicks) * quantizationTicks;
    }
    // Sort by tick
    std::sort(mEvents.begin(), mEvents.end(),
        [](const RecordedMidiEvent& a, const RecordedMidiEvent& b) {
            return a.tick < b.tick;
        });
}