#include "Sequencer.h"
#include <cstring>

Sequencer::Sequencer() {
    // Initialize all events with negative framePosition to mark as empty
    for (int32_t i = 0; i < kMaxScheduledEvents; i++) {
        mEvents[i].framePosition.store(-1, std::memory_order_relaxed);
        mEvents[i].status = 0;
        mEvents[i].data1 = 0;
        mEvents[i].data2 = 0;
    }
}

Sequencer::~Sequencer() = default;

void Sequencer::init(TransportState* transport) {
    mTransport = transport;
}

void Sequencer::setMidiQueue(MidiQueue* queue) {
    mMidiQueue = queue;
}

void Sequencer::scheduleEvent(int64_t framePosition, uint8_t status, uint8_t data1, uint8_t data2) {
    // CAS loop to atomically find and claim an empty slot
    while (true) {
        int32_t count = mEventCount.load(std::memory_order_acquire);
        if (count >= kMaxScheduledEvents) return;  // Buffer full

        // Find first empty slot
        int32_t slot = -1;
        for (int32_t i = 0; i < kMaxScheduledEvents; i++) {
            if (mEvents[i].framePosition.load(std::memory_order_relaxed) < 0) {
                slot = i;
                break;
            }
        }
        if (slot < 0) return;  // No empty slot found

        // Write all fields BEFORE CAS — ensures they're all visible to the reader
        mEvents[slot].status = status;
        mEvents[slot].data1 = data1;
        mEvents[slot].data2 = data2;
        mEvents[slot].framePosition.store(framePosition, std::memory_order_relaxed);

        // CAS as the visibility trigger
        if (mEvents[slot].framePosition.compare_exchange_strong(
                -1, framePosition, std::memory_order_release, std::memory_order_relaxed)) {
            mEventCount.fetch_add(1, std::memory_order_release);
            return;
        }
        // CAS failed — another thread claimed this slot, retry
    }
}

bool Sequencer::processFrame() {
    if (!mRunning.load(std::memory_order_acquire)) {
        return false;
    }

    if (!mTransport) return false;

    int64_t currentFrame = mTransport->framePosition.load(std::memory_order_acquire);
    bool anyProcessed = false;

    // Scan all scheduled events for ones that should fire at or before current frame
    for (int32_t i = 0; i < kMaxScheduledEvents; i++) {
        if (mEvents[i].framePosition < 0) continue;  // Empty slot
        if (mEvents[i].framePosition > currentFrame) continue;  // Not yet

        // This event should fire — enqueue to MIDI queue
        if (mMidiQueue) {
            MidiMessage msg;
            msg.status = mEvents[i].status;
            msg.data1 = mEvents[i].data1;
            msg.data2 = mEvents[i].data2;
            msg.timestamp = currentFrame;
            mMidiQueue->push(msg);
        }

        // Mark as processed (framePosition = -1)
        mEvents[i].framePosition = -1;
        mEvents[i].status = 0;
        mEvents[i].data1 = 0;
        mEvents[i].data2 = 0;
        mEventCount.fetch_sub(1, std::memory_order_release);
        anyProcessed = true;
    }

    return anyProcessed;
}

void Sequencer::start() {
    mRunning.store(true, std::memory_order_release);
}

void Sequencer::stop() {
    mRunning.store(false, std::memory_order_release);
}