#include "Mp3AudioRecorder.h"

#include <lame.h>
#include <algorithm>
#include <cerrno>
#include <chrono>
#include <cmath>
#include <unistd.h>

namespace {
constexpr auto kQuiesceTimeout = std::chrono::seconds(3);
constexpr auto kPollDelay = std::chrono::milliseconds(1);
}

Mp3AudioRecorder::CallbackToken::CallbackToken(Mp3AudioRecorder& owner) noexcept
    : owner_(owner), accepted_(false), producerSlot_(false) {
    owner_.inFlightCallbacks_.fetch_add(1, std::memory_order_seq_cst);
    accepted_ = owner_.accepting_.load(std::memory_order_seq_cst);
    if (accepted_) {
        bool expected = false;
        producerSlot_ = owner_.producerActive_.compare_exchange_strong(
            expected, true, std::memory_order_seq_cst);
        if (!producerSlot_) {
            owner_.overflow_.store(true, std::memory_order_release);
            accepted_ = false;
        }
    }
}

Mp3AudioRecorder::CallbackToken::~CallbackToken() {
    if (producerSlot_) owner_.producerActive_.store(false, std::memory_order_seq_cst);
    owner_.inFlightCallbacks_.fetch_sub(1, std::memory_order_seq_cst);
}

Mp3AudioRecorder::~Mp3AudioRecorder() {
    // Shutdown-only fallback. Unlike the JNI stop, destruction cannot leave a
    // joinable worker behind; wait off-callback for an admitted callback to exit.
    accepting_.store(false, std::memory_order_seq_cst);
    while (inFlightCallbacks_.load(std::memory_order_seq_cst) != 0)
        std::this_thread::sleep_for(kPollDelay);
    if (worker_.joinable()) {
        producerDone_.store(true, std::memory_order_release);
        while (!workerDone_.load(std::memory_order_acquire))
            std::this_thread::sleep_for(kPollDelay);
        worker_.join();
    }
}

bool Mp3AudioRecorder::start(int fd, int sampleRate) {
    if (fd < 0 || sampleRate <= 0) return false;
    std::lock_guard<std::mutex> lock(controlMutex_);
    if (state_.load(std::memory_order_acquire) == State::Capturing ||
        state_.load(std::memory_order_acquire) == State::Finalizing ||
        !workerDone_.load(std::memory_order_acquire)) return false;

    // A failed worker can disable acceptance while a callback already holding
    // an accepted token is still copying into ring_. Close admission again and
    // wait off-callback before joining/resetting session storage. If this times
    // out, do not take ownership of fd or modify/reuse the ring.
    accepting_.store(false, std::memory_order_seq_cst);
    const auto quiesceDeadline = std::chrono::steady_clock::now() + kQuiesceTimeout;
    while (inFlightCallbacks_.load(std::memory_order_seq_cst) != 0 ||
           producerActive_.load(std::memory_order_seq_cst)) {
        if (std::chrono::steady_clock::now() >= quiesceDeadline) return false;
        std::this_thread::sleep_for(kPollDelay);
    }
    if (worker_.joinable()) worker_.join(); // previous session finished; reap before restart

    // Acceptance stays false while the worker and session state are prepared.
    fd_ = fd;
    sampleRate_ = sampleRate;
    writeFrame_.store(0, std::memory_order_relaxed);
    readFrame_.store(0, std::memory_order_relaxed);
    producerDone_.store(false, std::memory_order_relaxed);
    overflow_.store(false, std::memory_order_relaxed);
    workerOk_.store(false, std::memory_order_relaxed);
    workerDone_.store(false, std::memory_order_release);
    try {
        worker_ = std::thread(&Mp3AudioRecorder::workerMain, this);
    } catch (...) {
        workerDone_.store(true, std::memory_order_release);
        fd_ = -1; // ownership not taken on failure
        return false;
    }
    state_.store(State::Capturing, std::memory_order_release);
    accepting_.store(true, std::memory_order_seq_cst);
    return true;
}

void Mp3AudioRecorder::capture(const float* pcm, int frames) noexcept {
    if (frames <= 0 || overflow_.load(std::memory_order_relaxed)) return;
    const uint32_t write = writeFrame_.load(std::memory_order_relaxed);
    const uint32_t read = readFrame_.load(std::memory_order_acquire);
    const uint32_t used = write - read;
    if (used > kCapacityFrames || static_cast<uint32_t>(frames) > kCapacityFrames - used) {
        overflow_.store(true, std::memory_order_release);
        return;
    }
    for (int frame = 0; frame < frames; ++frame) {
        const uint32_t slot = (write + static_cast<uint32_t>(frame)) & (kCapacityFrames - 1);
        ring_[slot * 2] = pcm[frame * 2];
        ring_[slot * 2 + 1] = pcm[frame * 2 + 1];
    }
    writeFrame_.store(write + static_cast<uint32_t>(frames), std::memory_order_release);
}

bool Mp3AudioRecorder::stop() {
    std::lock_guard<std::mutex> lock(controlMutex_);
    const State current = state_.load(std::memory_order_acquire);
    if (current == State::Idle) return false;
    if (current == State::Failed && !worker_.joinable()) return false;

    // Linearization point: callbacks whose acceptance read precedes this store
    // are covered by inFlightCallbacks_; later entrants cannot touch ring_.
    accepting_.store(false, std::memory_order_seq_cst);
    state_.store(State::Finalizing, std::memory_order_release);
    const auto deadline = std::chrono::steady_clock::now() + kQuiesceTimeout;
    while (inFlightCallbacks_.load(std::memory_order_seq_cst) != 0) {
        if (std::chrono::steady_clock::now() >= deadline) return false;
        std::this_thread::sleep_for(kPollDelay); // control thread only
    }
    producerDone_.store(true, std::memory_order_release);
    while (!workerDone_.load(std::memory_order_acquire))
        std::this_thread::sleep_for(kPollDelay);
    if (worker_.joinable()) worker_.join();
    const bool ok = workerOk_.load(std::memory_order_acquire) &&
                    !overflow_.load(std::memory_order_acquire);
    fd_ = -1; // worker closed it after finalization
    sampleRate_ = 0;
    state_.store(ok ? State::Idle : State::Failed, std::memory_order_release);
    return ok;
}

bool Mp3AudioRecorder::writeAll(const unsigned char* bytes, int size) noexcept {
    int offset = 0;
    while (offset < size) {
        const ssize_t written = ::write(fd_, bytes + offset, static_cast<size_t>(size - offset));
        if (written < 0 && errno == EINTR) continue;
        if (written <= 0) return false;
        offset += static_cast<int>(written);
    }
    return true;
}

void Mp3AudioRecorder::workerMain() {
    while (!accepting_.load(std::memory_order_seq_cst) &&
           !producerDone_.load(std::memory_order_acquire))
        std::this_thread::yield();
    lame_t lame = lame_init();
    bool ok = lame != nullptr;
    if (ok) {
        lame_set_in_samplerate(lame, sampleRate_);
        lame_set_num_channels(lame, 2);
        lame_set_quality(lame, 5);
        ok = lame_init_params(lame) >= 0;
    }

    std::array<float, kChunkFrames * 2> chunk{};
    std::array<short, kChunkFrames * 2> pcm{};
    std::array<unsigned char, kMp3Bytes> encoded{};
    while (ok) {
        const uint32_t read = readFrame_.load(std::memory_order_relaxed);
        const uint32_t written = writeFrame_.load(std::memory_order_acquire);
        const uint32_t available = written - read;
        if (available == 0) {
            if (producerDone_.load(std::memory_order_acquire)) break;
            std::this_thread::sleep_for(kPollDelay);
            continue;
        }
        const int frames = static_cast<int>(std::min<uint32_t>(available, kChunkFrames));
        for (int f = 0; f < frames; ++f) {
            const uint32_t slot = (read + static_cast<uint32_t>(f)) & (kCapacityFrames - 1);
            chunk[f * 2] = ring_[slot * 2];
            chunk[f * 2 + 1] = ring_[slot * 2 + 1];
            for (int c = 0; c < 2; ++c) {
                const float value = std::max(-1.0f, std::min(1.0f, chunk[f * 2 + c]));
                pcm[f * 2 + c] = static_cast<short>(std::lrint(value * 32767.0f));
            }
        }
        const int bytes = lame_encode_buffer_interleaved(
            lame, pcm.data(), frames, encoded.data(), static_cast<int>(encoded.size()));
        if (bytes < 0 || !writeAll(encoded.data(), bytes)) { ok = false; break; }
        readFrame_.store(read + static_cast<uint32_t>(frames), std::memory_order_release);
    }

    if (ok && lame) {
        const int bytes = lame_encode_flush(lame, encoded.data(), static_cast<int>(encoded.size()));
        if (bytes < 0 || !writeAll(encoded.data(), bytes)) ok = false;
    }
    if (lame) lame_close(lame);
    if (fd_ >= 0) {
        if (::close(fd_) != 0) ok = false;
        fd_ = -1;
    }
    if (!ok) {
        accepting_.store(false, std::memory_order_seq_cst);
        state_.store(State::Failed, std::memory_order_release);
    }
    workerOk_.store(ok, std::memory_order_release);
    workerDone_.store(true, std::memory_order_release);
}
