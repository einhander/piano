#include "SynthCmdQueue.h"

SynthCmdQueue::SynthCmdQueue(int32_t capacity) {
    int32_t p = 1; while (p < capacity) p <<= 1;
    mCapacity = p; mData = new SynthCmd[p];
    mSequence = new std::atomic<uint32_t>[p];
    for (int32_t i = 0; i < p; ++i) mSequence[i].store(static_cast<uint32_t>(i));
    std::memset(mData, 0, p * sizeof(SynthCmd));
}
SynthCmdQueue::~SynthCmdQueue() { delete[] mData; delete[] mSequence; }
bool SynthCmdQueue::push(const SynthCmd& cmd) {
    uint32_t pos = mWritePos.load(std::memory_order_relaxed);
    for (;;) {
        auto& seq = mSequence[pos & (mCapacity - 1)];
        int32_t dif = static_cast<int32_t>(seq.load(std::memory_order_acquire) - pos);
        if (dif == 0 && mWritePos.compare_exchange_weak(pos, pos + 1, std::memory_order_relaxed)) {
            mData[pos & (mCapacity - 1)] = cmd; seq.store(pos + 1, std::memory_order_release); return true;
        }
        if (dif < 0) { mDroppedCount.fetch_add(1, std::memory_order_relaxed); return false; }
        pos = mWritePos.load(std::memory_order_relaxed);
    }
}
bool SynthCmdQueue::pop(SynthCmd& cmd) {
    uint32_t pos = mReadPos.load(std::memory_order_relaxed); auto& seq = mSequence[pos & (mCapacity - 1)];
    if (static_cast<int32_t>(seq.load(std::memory_order_acquire) - (pos + 1)) != 0) return false;
    cmd = mData[pos & (mCapacity - 1)]; mReadPos.store(pos + 1, std::memory_order_relaxed);
    seq.store(pos + static_cast<uint32_t>(mCapacity), std::memory_order_release); return true;
}
int32_t SynthCmdQueue::size() const { uint32_t n=mWritePos.load(std::memory_order_acquire)-mReadPos.load(std::memory_order_acquire); return static_cast<int32_t>(n > static_cast<uint32_t>(mCapacity) ? mCapacity : n); }
