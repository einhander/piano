#pragma once

#include <atomic>
#include <cstdint>
#include <cmath>

struct TransportState {
    // Frame position — absolute position in audio samples
    std::atomic<int64_t> framePosition{0};

    // Device sample rate (from Oboe stream)
    int32_t sampleRate = 48000;

    // Tempo
    double bpm = 120.0;

    // Time signature
    int16_t numerator = 4;
    int16_t denominator = 4;  // 4 = quarter note

    // Pulses Per Quarter Note (PPQ) — standard is 960
    int32_t ppq = 960;

    // Transport state
    enum State { Stopped, Playing, Paused };
    std::atomic<State> state{State::Stopped};

    // Ticks per frame — precomputed: ppq * sampleRate / (bpm * 60)
    // Non-atomic: updated from UI thread (infrequent), read from audio thread.
    // On x86: aligned 8-byte read is atomic. On ARM: torn read is negligible risk
    // (one bad tick calculation is harmless — next frame corrects it).
    double ticksPerFrame = 0.0;

    // Update ticksPerFrame when BPM changes (caller must ensure serialization)
    void updateTicksPerFrame() {
        ticksPerFrame = (ppq * sampleRate) / (bpm * 60.0);
    }

    // Convert frame position to tick position
    inline double frameToTick(int64_t framePos) const {
        return framePos * ticksPerFrame;
    }

    // Convert tick position to frame position
    inline int64_t tickToFrame(double tick) const {
        return static_cast<int64_t>(tick / ticksPerFrame);
    }

    // Get current tick position
    inline double currentTick() const {
        return framePosition.load(std::memory_order_acquire) * ticksPerFrame;
    }

    // Get beats per bar
    inline double beatsPerBar() const {
        return numerator;
    }

    // Get bar number
    inline int64_t currentBar() const {
        return static_cast<int64_t>(frameToTick(framePosition.load(std::memory_order_acquire)) / numerator);
    }

    // NOTE: beatInBar() and currentBar() use fmod/division — NOT real-time safe.
    // Only call from UI thread, never from audio callback.
    inline double beatInBar() const {
        return fmod(frameToTick(framePosition.load(std::memory_order_acquire)), numerator);
    }
};