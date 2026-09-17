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
    uint32_t bpmMicros = 120000000;

    // Time signature
    int16_t numerator = 4;
    int16_t denominator = 4;  // 4 = quarter note

    // Pulses Per Quarter Note (PPQ) — standard is 960
    int32_t ppq = 960;

    // Transport state
    enum State { Stopped, Playing, Paused };
    std::atomic<State> state{State::Stopped};

    // Ticks per frame — precomputed: ppq * bpm / (sampleRate * 60)
    // Non-atomic: updated from UI thread (infrequent), read from audio thread.
    // On x86: aligned 8-byte read is atomic. On ARM: torn read is negligible risk
    // (one bad tick calculation is harmless — next frame corrects it).
    double tpf = 0.0;
    int64_t frameOrigin = 0;
    double tickOrigin = 0.0;

    void initializeAudio(int32_t rate, uint32_t tempoMicros = 120000000) {
        sampleRate = rate; bpmMicros = tempoMicros; frameOrigin = 0; tickOrigin = 0.0;
        tpf = (static_cast<double>(ppq) * bpmMicros) / (60000000.0 * sampleRate);
    }
    inline double frameToTickAudio(int64_t frame) const {
        return tickOrigin + (frame - frameOrigin) * tpf;
    }
    void applyTempo(uint32_t tempoMicros, int64_t frame) {
        if (tempoMicros == 0) return;
        tickOrigin = frameToTickAudio(frame); frameOrigin = frame; bpmMicros = tempoMicros;
        tpf = (static_cast<double>(ppq) * bpmMicros) / (60000000.0 * sampleRate);
    }
    void applyRate(int32_t rate, int64_t frame) {
        if (rate <= 0) return;
        tickOrigin = frameToTickAudio(frame); frameOrigin = frame; sampleRate = rate;
        tpf = (static_cast<double>(ppq) * bpmMicros) / (60000000.0 * sampleRate);
    }

    // Update ticksPerFrame when BPM changes (caller must ensure serialization)
    void updateTicksPerFrame() {
        tpf = (ppq * bpmMicros) / (sampleRate * 60000000.0);
    }

    // Convert frame position to tick position
    inline double frameToTick(int64_t framePos) const {
        return frameToTickAudio(framePos);
    }

    // Convert tick position to frame position
    inline int64_t tickToFrame(double tick) const {
        return static_cast<int64_t>(frameOrigin + (tick - tickOrigin) / tpf);
    }

    // Get current tick position
    inline double currentTick() const {
        return frameToTickAudio(framePosition.load(std::memory_order_acquire));
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
