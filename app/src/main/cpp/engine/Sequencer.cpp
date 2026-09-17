#include "Sequencer.h"
#include <cstring>

Sequencer::Sequencer() {
    // Initialize all events with negative framePosition to mark as empty
    for (int32_t i = 0; i < kMaxScheduledEvents; i++) {
        mEvents[i].framePosition.store(-1, std::memory_order_relaxed);
        mEvents[i].status = 0;
        mEvents[i].data1 = 0;
        mEvents[i].data2 = 0;
        mOrder[i] = 0;
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
    if (framePosition < 0) return;
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

        // Claim slot before touching payload. -2 means producer owns slot but has
        // not published payload yet; consumer ignores all negative positions.
        int64_t expected = -1;
        if (!mEvents[slot].framePosition.compare_exchange_strong(
                expected, -2, std::memory_order_acquire, std::memory_order_relaxed)) {
            continue;
        }

        mEvents[slot].status = status;
        mEvents[slot].data1 = data1;
        mEvents[slot].data2 = data2;
        mOrder[slot] = mNextOrder.fetch_add(1, std::memory_order_relaxed);
        mEvents[slot].framePosition.store(framePosition, std::memory_order_release);
        mEventCount.fetch_add(1, std::memory_order_release);
        return;
    }
}

int32_t Sequencer::collectDueEvents(int64_t beginFrame, int64_t endFrame,
                                    TimedMidiEvent* output, int32_t capacity) {
    if (!mRunning.load(std::memory_order_acquire)) return 0;
    if (!output || capacity <= 0 || endFrame <= beginFrame) return 0;
    int32_t count = 0;
    for (int32_t i = 0; i < kMaxScheduledEvents && count < capacity; ++i) {
        int64_t frame = mEvents[i].framePosition.load(std::memory_order_acquire);
        if (frame < 0 || frame >= endFrame) continue;
        int64_t expected = frame;
        if (!mEvents[i].framePosition.compare_exchange_strong(
                expected, -2, std::memory_order_acq_rel, std::memory_order_relaxed)) continue;
        output[count].targetFrame = frame < beginFrame ? beginFrame : frame;
        output[count].order = mOrder[i];
        output[count].sourceSlot = 0;
        output[count].phase = TimedMidiEvent::Scheduled;
        output[count].message.status = mEvents[i].status;
        output[count].message.data1 = mEvents[i].data1;
        output[count].message.data2 = mEvents[i].data2;
        output[count].message.timestamp = frame > 0 ? frame : 1;
        ++count;
        mEvents[i].status = mEvents[i].data1 = mEvents[i].data2 = 0;
        mEvents[i].framePosition.store(-1, std::memory_order_release);
        mEventCount.fetch_sub(1, std::memory_order_release);
    }
    // Stable bounded insertion sort by target frame, then producer order.
    for (int32_t i = 1; i < count; ++i) {
        TimedMidiEvent item = output[i]; int32_t j = i;
        while (j > 0 && (output[j-1].targetFrame > item.targetFrame ||
               (output[j-1].targetFrame == item.targetFrame && output[j-1].order > item.order))) {
            output[j] = output[j-1]; --j;
        }
        output[j] = item;
    }
    return count;
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
        int64_t scheduledFrame = mEvents[i].framePosition.load(std::memory_order_acquire);
        if (scheduledFrame < 0) continue;  // Empty or producer-owned slot
        if (scheduledFrame > currentFrame) continue;  // Not yet

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
        mEvents[i].framePosition.store(-1, std::memory_order_release);
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
