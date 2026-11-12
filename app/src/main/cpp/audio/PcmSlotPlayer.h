#pragma once

#include <array>
#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>

// Worker-thread PCM publisher and allocation-free audio-thread renderer.
// Destructor requires audio callback stopped and quiescent.
class PcmSlotPlayer {
public:
    static constexpr int32_t kMaxSlots = 16;
    static constexpr uint64_t kMaxAssetBytes = 64ull * 1024 * 1024;
    static constexpr uint64_t kMaxResidentBytes = 128ull * 1024 * 1024;
    static constexpr uint32_t kQueueCapacity = 64;

    explicit PcmSlotPlayer(uint64_t maxAssetBytes = kMaxAssetBytes,
                           uint64_t maxResidentBytes = kMaxResidentBytes);
    ~PcmSlotPlayer();
    PcmSlotPlayer(const PcmSlotPlayer&) = delete;
    PcmSlotPlayer& operator=(const PcmSlotPlayer&) = delete;

    bool load(int slot, const int16_t* stereoPcm, int64_t frames,
              int32_t sourceRate, std::string* error = nullptr,
              uint64_t uploadCapacity = 0);
    bool start(int slot, bool loop);
    bool setLoop(int slot, bool loop);
    bool stop(int slot);
    bool free(int slot);
    bool stopAll();
    void renderAdd(float* interleavedOutput, int32_t frames, int32_t outputRate);
    bool isPlaying(int slot) const;

private:
    struct Asset;
    enum class Op : uint8_t { Replace, Start, Stop, Free, StopAll, SetLoop };
    struct Command { Op op{}; int32_t slot{}; Asset* asset{}; bool loop{}; };
    bool enqueue(const Command& command);
    void drainRetired();
    bool validSlot(int slot) const;

    std::mutex workerMutex_;
    std::array<Command, kQueueCapacity> commands_{};
    std::atomic<uint32_t> write_{0}, read_{0};
    std::array<Asset*, kQueueCapacity> retired_{};
    std::atomic<uint32_t> retiredWrite_{0}, retiredRead_{0};
    std::array<std::atomic<bool>, kMaxSlots> playing_{};
    struct Voice { Asset* asset = nullptr; double position = 0; bool loop = false; };
    std::array<Voice, kMaxSlots> voices_{};
    uint64_t residentBytes_ = 0;
    uint64_t maxAssetBytes_, maxResidentBytes_;
};
