#pragma once

#include <array>
#include <atomic>
#include <cstdint>
#include <cstddef>
#include <memory>
#include <mutex>
#include <string>

// On-disk PCM cache layout (little-endian; every supported target ABI and
// the host test build are little-endian). Keep in sync with
// com.piano.sequencer.audio.Mp3PcmCache (Kotlin writer/validator).
struct PcmCacheHeader {
    char magic[4];        // "PCMC"
    uint32_t version;     // 1
    uint32_t channels;    // always 2 (interleaved stereo)
    uint32_t sampleRate;
    int64_t frames;
    uint64_t reserved;    // zero
};                        // 32 bytes, no padding

static_assert(sizeof(PcmCacheHeader) == 32, "unexpected PcmCacheHeader size");
constexpr uint32_t kPcmCacheVersion = 1;
constexpr uint32_t kPcmCacheChannels = 2;
constexpr unsigned char kPcmCacheMagic[4] = {'P', 'C', 'M', 'C'};

// Worker-thread PCM publisher and allocation-free audio-thread renderer.
// Destructor requires audio callback stopped and quiescent.
class PcmSlotPlayer {
public:
    static constexpr int32_t kMaxSlots = 16;
    static constexpr uint64_t kMaxAssetBytes = 128ull * 1024 * 1024;
    static constexpr uint64_t kMaxResidentBytes = 256ull * 1024 * 1024;
    static constexpr uint32_t kQueueCapacity = 64;

    explicit PcmSlotPlayer(uint64_t maxAssetBytes = kMaxAssetBytes,
                           uint64_t maxResidentBytes = kMaxResidentBytes);
    ~PcmSlotPlayer();
    PcmSlotPlayer(const PcmSlotPlayer&) = delete;
    PcmSlotPlayer& operator=(const PcmSlotPlayer&) = delete;

    bool load(int slot, const int16_t* stereoPcm, int64_t frames,
              int32_t sourceRate, std::string* error = nullptr,
              uint64_t uploadCapacity = 0);
    // Worker-thread only (blocking file I/O): adopt a pre-decoded PCM cache
    // file (PcmCacheHeader layout) without a Java heap copy or re-decode.
    bool loadFromDisk(int slot, const char* path, std::string* error = nullptr);
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
    bool validSlot(int s) const;
    // Moves `asset` into the command queue; deletes it if enqueue fails.
    // Budget preconditions must already hold.
    bool adoptQueued(int slot, Asset* asset, std::string* error);

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
