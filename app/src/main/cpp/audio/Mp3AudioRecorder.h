#pragma once

#include <array>
#include <atomic>
#include <cstdint>
#include <mutex>
#include <thread>

static_assert(std::atomic<uint32_t>::is_always_lock_free,
              "Recorder callback requires lock-free 32-bit atomics");
static_assert(std::atomic<bool>::is_always_lock_free,
              "Recorder callback requires lock-free boolean atomics");

// One callback producer and one encoder worker consumer. Storage is embedded
// in NativeEngine and therefore remains stable for process lifetime.
class Mp3AudioRecorder {
public:
    enum class State : int { Idle = 0, Capturing = 1, Finalizing = 2, Failed = 3 };
    Mp3AudioRecorder() = default;
    ~Mp3AudioRecorder();
    Mp3AudioRecorder(const Mp3AudioRecorder&) = delete;
    Mp3AudioRecorder& operator=(const Mp3AudioRecorder&) = delete;

    bool start(int fd, int sampleRate);
    bool stop();
    bool isRecording() const noexcept {
        return state_.load(std::memory_order_acquire) == State::Capturing;
    }
    State state() const noexcept { return state_.load(std::memory_order_acquire); }

    // Must be the callback's first recorder operation. Token destruction must
    // occur on every callback exit path.
    class CallbackToken {
    public:
        explicit CallbackToken(Mp3AudioRecorder& owner) noexcept;
        ~CallbackToken();
        bool accepted() const noexcept { return accepted_; }
    private:
        Mp3AudioRecorder& owner_;
        bool accepted_;
        bool producerSlot_;
    };

    void capture(const float* interleavedStereo, int frames) noexcept;

private:
    static constexpr uint32_t kCapacityFrames = 1u << 20; // 21.8 s at 48 kHz
    static constexpr int kChunkFrames = 2048;
    static constexpr int kMp3Bytes = 16384;
    void workerMain();
    bool writeAll(const unsigned char* bytes, int size) noexcept;

    alignas(64) std::array<float, kCapacityFrames * 2> ring_{};
    alignas(64) std::atomic<uint32_t> writeFrame_{0};
    alignas(64) std::atomic<uint32_t> readFrame_{0};
    // All three handshake operations use seq_cst: callback entry count precedes
    // acceptance read; stop disables acceptance then waits for count==0.
    std::atomic<uint32_t> inFlightCallbacks_{0};
    std::atomic<bool> producerActive_{false};
    std::atomic<bool> accepting_{false};
    std::atomic<bool> producerDone_{false};
    std::atomic<bool> overflow_{false};
    std::atomic<bool> workerDone_{true};
    std::atomic<bool> workerOk_{false};
    std::atomic<State> state_{State::Idle};
    int fd_ = -1;
    int sampleRate_ = 0;
    std::thread worker_;
    std::mutex controlMutex_; // control/JNI thread only; never touched by callback
};
