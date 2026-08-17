#include "MidiQueue.h"

MidiQueue::MidiQueue(int32_t capacity) {
    // Power-of-2 capacity
    int32_t powerOf2 = 1;
    while (powerOf2 < capacity) {
        powerOf2 <<= 1;
    }
    mCapacity = powerOf2;
    mData = new MidiMessage[mCapacity];
    std::memset(mData, 0, mCapacity * sizeof(MidiMessage));
}

// uint32_t arithmetic: wrap-around on overflow is well-defined (mod 2^32),
// and the mask (mCapacity-1) keeps indices in range regardless of overflow.

MidiQueue::~MidiQueue() {
    delete[] mData;
}

bool MidiQueue::push(const MidiMessage& msg) {
    // MPSC-safe: use CAS loop to atomically claim write slot
    // Monotonic counters: w >= r always holds, so (w - r) is the correct occupied count.
    while (true) {
        uint32_t writePos = mWritePos.load(std::memory_order_relaxed);
        uint32_t readPos = mReadPos.load(std::memory_order_acquire);
        // Monotonic subtraction — no false-drops from mask wrap-around
        if (writePos - readPos >= static_cast<uint32_t>(mCapacity)) {
            mDroppedCount.fetch_add(1);
            return false;  // Queue full
        }
        // Use CAS to atomically claim this slot
        uint32_t expected = writePos;
        if (!mWritePos.compare_exchange_weak(expected, writePos + 1, std::memory_order_release, std::memory_order_relaxed)) {
            // Another producer claimed this slot — retry
            continue;
        }
        mData[writePos & (mCapacity - 1)] = msg;
        return true;
    }
}

bool MidiQueue::pop(MidiMessage& msg) {
    uint32_t writePos = mWritePos.load(std::memory_order_acquire);
    uint32_t readPos = mReadPos.load(std::memory_order_relaxed);

    if (readPos >= writePos) {
        return false;  // Queue empty
    }

    msg = mData[readPos & (mCapacity - 1)];
    mReadPos.store(readPos + 1, std::memory_order_release);

    return true;
}

int32_t MidiQueue::size() const {
    uint32_t writePos = mWritePos.load(std::memory_order_acquire);
    uint32_t readPos = mReadPos.load(std::memory_order_acquire);
    uint32_t used = writePos - readPos;
    return static_cast<int32_t>(used > 0 ? used : 0);
}

void MidiQueue::reset() {
    mWritePos.store(0, std::memory_order_release);
    mReadPos.store(0, std::memory_order_release);
    std::memset(mData, 0, mCapacity * sizeof(MidiMessage));
}

// Note: uint32_t positions mean ~4.3 billion messages before wrap-around.
// The mask operation (pos & (capacity-1)) keeps array indexing correct
// across wrap-around boundaries since unsigned overflow is well-defined.