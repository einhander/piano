#pragma once

#include <atomic>
#include <cstdint>
#include <cstring>

// Lock-free SPSC ring buffer for PCM data
// Single producer, single consumer
// Pre-allocated, no dynamic memory after construction
class RingBuffer {
public:
    explicit RingBuffer(int32_t capacity);
    ~RingBuffer();

    RingBuffer(const RingBuffer&) = delete;
    RingBuffer& operator=(const RingBuffer&) = delete;
    RingBuffer(RingBuffer&&) = delete;
    RingBuffer& operator=(RingBuffer&&) = delete;

    int32_t write(const float* data, int32_t numFrames);
    int32_t read(float* data, int32_t numFrames);
    int32_t available() const;
    int32_t capacity() const { return mCapacity; }
    void reset();

private:
    float* mData = nullptr;
    int32_t mCapacity = 0;
    std::atomic<int32_t> mWritePos{0};
    std::atomic<int32_t> mReadPos{0};
};