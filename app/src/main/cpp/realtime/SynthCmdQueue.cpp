#include "SynthCmdQueue.h"

SynthCmdQueue::SynthCmdQueue(int32_t capacity) {
    // Power-of-2 capacity
    int32_t powerOf2 = 1;
    while (powerOf2 < capacity) {
        powerOf2 <<= 1;
    }
    mCapacity = powerOf2;
    mData = new SynthCmd[mCapacity];
    std::memset(mData, 0, mCapacity * sizeof(SynthCmd));
}

// uint32_t arithmetic: wrap-around on overflow is well-defined (mod 2^32),
// and the mask (mCapacity-1) keeps indices in range regardless of overflow.

SynthCmdQueue::~SynthCmdQueue() {
    delete[] mData;
}

bool SynthCmdQueue::push(const SynthCmd& cmd) {
    // MPSC-safe: use CAS loop to atomically claim write slot
    // Monotonic counters: w >= r always holds, so (w - r) is the correct occupied count.
    while (true) {
        uint32_t writePos = mWritePos.load(std::memory_order_relaxed);
        uint32_t readPos = mReadPos.load(std::memory_order_acquire);
        if (writePos - readPos >= static_cast<uint32_t>(mCapacity)) {
            mDroppedCount.fetch_add(1);
            return false;  // Queue full
        }
        // m2: write the data BEFORE the release store (the CAS) so the data
        // write is happens-before the release store. On weakly-ordered CPUs
        // (ARM), the consumer (acquire load of mWritePos) might otherwise see
        // the updated writePos but read stale data (the data write not yet
        // visible). If the CAS fails (another producer claimed the slot first),
        // that producer overwrites our write — our push failed, so that's fine.
        mData[writePos & (mCapacity - 1)] = cmd;
        uint32_t expected = writePos;
        if (!mWritePos.compare_exchange_weak(expected, writePos + 1,
                std::memory_order_release, std::memory_order_relaxed)) {
            continue;
        }
        return true;
    }
}

bool SynthCmdQueue::pop(SynthCmd& cmd) {
    uint32_t writePos = mWritePos.load(std::memory_order_acquire);
    uint32_t readPos = mReadPos.load(std::memory_order_relaxed);

    if (readPos >= writePos) {
        return false;  // Queue empty
    }

    cmd = mData[readPos & (mCapacity - 1)];
    mReadPos.store(readPos + 1, std::memory_order_release);

    return true;
}

int32_t SynthCmdQueue::size() const {
    uint32_t writePos = mWritePos.load(std::memory_order_acquire);
    uint32_t readPos = mReadPos.load(std::memory_order_acquire);
    uint32_t used = writePos - readPos;
    return static_cast<int32_t>(used > 0 ? used : 0);
}