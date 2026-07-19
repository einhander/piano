#include "RingBuffer.h"

RingBuffer::RingBuffer(int32_t capacity)
    : mCapacity(capacity) {
    int32_t powerOf2 = 1;
    while (powerOf2 < capacity) {
        powerOf2 <<= 1;
    }
    mCapacity = powerOf2;
    mData = new float[mCapacity];
    std::memset(mData, 0, mCapacity * sizeof(float));
}

RingBuffer::~RingBuffer() {
    delete[] mData;
}

int32_t RingBuffer::write(const float* data, int32_t numFrames) {
    int32_t writePos = mWritePos.load(std::memory_order_relaxed);
    int32_t readPos = mReadPos.load(std::memory_order_relaxed);

    int32_t used = writePos - readPos;
    int32_t available = mCapacity - used;

    if (available <= 0) {
        return 0;
    }

    int32_t toWrite = numFrames < available ? numFrames : available;

    int32_t firstSegment = mCapacity - writePos;
    int32_t actualWrite = toWrite < firstSegment ? toWrite : firstSegment;

    std::memcpy(mData + writePos, data, actualWrite * sizeof(float));

    if (toWrite > firstSegment) {
        std::memcpy(mData, data + firstSegment, (toWrite - firstSegment) * sizeof(float));
    }

    mWritePos.store((writePos + toWrite) & (mCapacity - 1), std::memory_order_release);

    return toWrite;
}

int32_t RingBuffer::read(float* data, int32_t numFrames) {
    int32_t writePos = mWritePos.load(std::memory_order_acquire);
    int32_t readPos = mReadPos.load(std::memory_order_relaxed);

    int32_t used = writePos - readPos;
    if (used <= 0) {
        return 0;
    }

    int32_t toRead = numFrames < used ? numFrames : used;

    int32_t firstSegment = mCapacity - readPos;
    int32_t actualRead = toRead < firstSegment ? toRead : firstSegment;

    std::memcpy(data, mData + readPos, actualRead * sizeof(float));

    if (toRead > firstSegment) {
        std::memcpy(data + firstSegment, mData, (toRead - firstSegment) * sizeof(float));
    }

    mReadPos.store((readPos + toRead) & (mCapacity - 1), std::memory_order_release);

    return toRead;
}

int32_t RingBuffer::available() const {
    int32_t writePos = mWritePos.load(std::memory_order_acquire);
    int32_t readPos = mReadPos.load(std::memory_order_acquire);
    int32_t used = writePos - readPos;
    return used > 0 ? used : 0;
}

void RingBuffer::reset() {
    mWritePos.store(0, std::memory_order_release);
    mReadPos.store(0, std::memory_order_release);
    std::memset(mData, 0, mCapacity * sizeof(float));
}