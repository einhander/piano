#pragma once
#include <atomic>
#include <cstdint>
#include <type_traits>

enum class TransportCmdType : uint8_t { SetTempo, SetSampleRate };
struct TransportCmd { TransportCmdType type; uint32_t bpmMicros; int32_t sampleRate; };

class TransportCmdQueue {
public:
    static constexpr uint32_t Capacity = 32;
    TransportCmdQueue() { for (uint32_t i = 0; i < Capacity; ++i) mSequence[i].store(i); }
    bool push(const TransportCmd& cmd);
    bool pop(TransportCmd& cmd);
    int32_t size() const;
    uint32_t droppedCount() const { return mDropped.load(std::memory_order_relaxed); }
private:
    TransportCmd mData[Capacity]{};
    std::atomic<uint32_t> mSequence[Capacity]{};
    std::atomic<uint32_t> mWrite{0}, mRead{0};
    std::atomic<uint32_t> mDropped{0};
};
static_assert(std::atomic<uint32_t>::is_always_lock_free);
static_assert(std::atomic<int32_t>::is_always_lock_free);
static_assert(std::atomic<int64_t>::is_always_lock_free);
