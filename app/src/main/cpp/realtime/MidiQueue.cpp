#include "MidiQueue.h"

MidiQueue::MidiQueue(int32_t capacity) {
    int32_t p = 1; while (p < capacity) p <<= 1;
    mCapacity = p; mData = new MidiMessage[p];
    mSequence = new std::atomic<uint32_t>[p];
    for (int32_t i = 0; i < p; ++i) mSequence[i].store(static_cast<uint32_t>(i));
    std::memset(mData, 0, p * sizeof(MidiMessage));
}
MidiQueue::~MidiQueue() { delete[] mData; delete[] mSequence; }

bool MidiQueue::push(const MidiMessage& msg) {
    uint32_t pos = mWritePos.load(std::memory_order_relaxed);
    for (;;) {
        auto& seq = mSequence[pos & (mCapacity - 1)];
        int32_t dif = static_cast<int32_t>(seq.load(std::memory_order_acquire) - pos);
        if (dif == 0) {
            if (mWritePos.compare_exchange_weak(pos, pos + 1, std::memory_order_relaxed)) {
                mData[pos & (mCapacity - 1)] = msg;
                seq.store(pos + 1, std::memory_order_release);
                return true;
            }
        } else if (dif < 0) {
            mDroppedCount.fetch_add(1, std::memory_order_relaxed); return false;
        } else {
            // Another producer advanced the claim position; retry from current position.
            pos = mWritePos.load(std::memory_order_relaxed);
        }
    }
}
bool MidiQueue::pop(MidiMessage& msg) {
    uint32_t pos = mReadPos.load(std::memory_order_relaxed);
    auto& seq = mSequence[pos & (mCapacity - 1)];
    if (static_cast<int32_t>(seq.load(std::memory_order_acquire) - (pos + 1)) != 0) return false;
    msg = mData[pos & (mCapacity - 1)];
    mReadPos.store(pos + 1, std::memory_order_relaxed);
    seq.store(pos + static_cast<uint32_t>(mCapacity), std::memory_order_release);
    return true;
}
int32_t MidiQueue::size() const {
    uint32_t n = mWritePos.load(std::memory_order_acquire) - mReadPos.load(std::memory_order_acquire);
    return static_cast<int32_t>(n > static_cast<uint32_t>(mCapacity) ? mCapacity : n);
}
void MidiQueue::reset() {
    mWritePos.store(0, std::memory_order_relaxed); mReadPos.store(0, std::memory_order_relaxed);
    for (int32_t i = 0; i < mCapacity; ++i) mSequence[i].store(static_cast<uint32_t>(i), std::memory_order_release);
    std::memset(mData, 0, mCapacity * sizeof(MidiMessage));
}
