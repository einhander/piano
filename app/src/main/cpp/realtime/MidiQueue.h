#pragma once

#include <atomic>
#include <cstdint>
#include <cstring>

// Lock-free SPSC MIDI message queue
// Single producer (MIDI input thread), single consumer (audio callback)
struct MidiMessage {
    uint8_t status;
    uint8_t data1;
    uint8_t data2;
    // Convention (load-bearing): timestamp == 0 → live keyboard event;
    // timestamp > 0 → file/player tick (MidiFilePlayer). Consumed by the
    // held-note bitmap in FluidSynthEngine::processLiveMidi and the recorder
    // in NativeEngine::midiThreadFunc (records only timestamp == 0).
    int64_t timestamp;
};

class MidiQueue {
public:
    explicit MidiQueue(int32_t capacity);
    ~MidiQueue();

    // Non-copyable, non-movable
    MidiQueue(const MidiQueue&) = delete;
    MidiQueue& operator=(const MidiQueue&) = delete;
    MidiQueue(MidiQueue&&) = delete;
    MidiQueue& operator=(MidiQueue&&) = delete;

    // Push message (returns true if successful, false if queue full)
    // MPSC-safe: uses CAS to handle concurrent producers
    bool push(const MidiMessage& msg);

    // Pop message (returns true if successful, false if queue empty)
    bool pop(MidiMessage& msg);

    // Get number of available messages
    int32_t size() const;

    // Get capacity
    int32_t capacity() const { return mCapacity; }

    // Get dropped count (queue overflow)
    int32_t droppedCount() const { return mDroppedCount.load(); }

    // Reset queue
    void reset();

private:
    MidiMessage* mData = nullptr;
    int32_t mCapacity = 0;
    std::atomic<uint32_t> mWritePos{0};
    std::atomic<uint32_t> mReadPos{0};
    std::atomic<int32_t> mDroppedCount{0};
};