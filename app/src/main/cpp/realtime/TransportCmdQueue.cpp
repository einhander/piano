#include "TransportCmdQueue.h"

bool TransportCmdQueue::push(const TransportCmd& cmd) {
    uint32_t pos = mWrite.load(std::memory_order_relaxed);
    for (;;) {
        auto& seq = mSequence[pos & (Capacity - 1)];
        int32_t dif = static_cast<int32_t>(seq.load(std::memory_order_acquire) - pos);
        if (dif == 0 && mWrite.compare_exchange_weak(pos, pos + 1, std::memory_order_relaxed)) {
            mData[pos & (Capacity - 1)] = cmd;
            seq.store(pos + 1, std::memory_order_release);
            return true;
        }
        if (dif < 0) { mDropped.fetch_add(1, std::memory_order_relaxed); return false; }
        pos = mWrite.load(std::memory_order_relaxed);
    }
}
bool TransportCmdQueue::pop(TransportCmd& cmd) {
    uint32_t pos = mRead.load(std::memory_order_relaxed);
    auto& seq = mSequence[pos & (Capacity - 1)];
    if (static_cast<int32_t>(seq.load(std::memory_order_acquire) - (pos + 1)) != 0) return false;
    cmd = mData[pos & (Capacity - 1)];
    mRead.store(pos + 1, std::memory_order_relaxed);
    seq.store(pos + Capacity, std::memory_order_release);
    return true;
}
int32_t TransportCmdQueue::size() const {
    uint32_t n = mWrite.load(std::memory_order_acquire) - mRead.load(std::memory_order_acquire);
    return static_cast<int32_t>(n > Capacity ? Capacity : n);
}
