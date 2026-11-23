#pragma once

#include "AudioEffect.h"
#include "lsp/LspEffectIds.h"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdlib>
#include <limits>

namespace piano {

// Tremolo: monaural sinusoidal depth modulation. Instrument rack slot
// kSlotTremolo (8) — custom C++ DSP with NO LADSPA binding, so it has no LADSPA
// port table and never depends on the LSP bundle. One instance is created per
// (channel, slot) by InstrumentEffectManager, so the LFO phase is independent
// per channel by construction.
//
// DSP (planar stereo, in place):
//   d       = depthPercent / 100                                  (0..1)
//   gain    = 1.0f - d*0.5f*(1.0f - sin(phase))
//   out     = in * gain          (same gain on L and R — mono LFO)
//   phase  += 2*pi*rate/sr, wrapped to [0, 2*pi)
//
// Click-free behaviour:
//  - The LFO phase advances continuously; a rate change alters only the
//    per-sample step, never the phase value, so a rate change introduces no
//    gain discontinuity (by construction).
//  - The APPLIED depth is linearly ramped over 5 ms (step 1/(sr*0.005) — the
//    same crossfade time the rack uses for its wet-mix fade) so dragging the
//    depth control or enabling/disabling the slot does not click. Choice: 5 ms
//    matches the perceptual crossfade already used everywhere in the rack, so
//    the tremolo edge sounds consistent with the other inserts.
//  - Fully released (applied depth == 0) the sample math is skipped entirely,
//    giving bit-exact passthrough.
//
// Realtime rules: no allocation/locking/logging in the audio path. The sine
// table (2048 floats, 8 KiB) is allocated once in prepare(). Per sample the
// cost is ONE interpolated table lookup instead of a transcendental sinf —
// negligible per channel, and bounded even if all 128 channels ran a Tremolo
// simultaneously (the manager only runs published/enabled instances).
class TremoloEffect final : public AudioEffect {
public:
    static constexpr int kSineTableEntries = 2048;
    static constexpr float kTwoPi = 6.28318530717958647692f;

    TremoloEffect() = default;
    ~TremoloEffect() override { std::free(mSine); }

    TremoloEffect(const TremoloEffect&) = delete;
    TremoloEffect& operator=(const TremoloEffect&) = delete;

    bool prepare(double sampleRate, int maxFrames) override {
        if (sampleRate <= 0.0 || maxFrames <= 0) return false;
        std::free(mSine);
        mSine = static_cast<float*>(std::malloc(sizeof(float) * kSineTableEntries));
        if (!mSine) { mPrepared = false; return false; }
        const double inv = 1.0 / static_cast<double>(kSineTableEntries);
        for (int i = 0; i < kSineTableEntries; ++i)
            mSine[i] = static_cast<float>(std::sin(6.28318530717958647692 * inv * i));
        mSampleRate = sampleRate;
        mPrepared = true;
        return true;
    }

    void process(float* left, float* right, int numFrames) noexcept override {
        if (!mPrepared || !left || !right || numFrames <= 0) return;
        const bool bypassed = mBypassed.load(std::memory_order_relaxed);
        const float rate = mRate.load(std::memory_order_acquire);
        const float targetDepth = mDepth.load(std::memory_order_acquire) * 0.01f;
        const float phaseStep = static_cast<float>(rate / mSampleRate); // cycles/sample
        // 5 ms applied-depth ramp (see class comment).
        const float depthStep = static_cast<float>(1.0 / (mSampleRate * 0.005));
        // Phase held in cycles (u; phase = 2*pi*u) so the table index is
        // exactly u * kSineTableEntries.
        float phase = mPhaseCycles.load(std::memory_order_relaxed);
        float depth = mDepthApplied.load(std::memory_order_relaxed);
        for (int i = 0; i < numFrames; ++i) {
            if (depth < targetDepth) depth = std::min(targetDepth, depth + depthStep);
            else if (depth > targetDepth) depth = std::max(targetDepth, depth - depthStep);
            if (!bypassed && depth > 0.0f) {
                float idx = phase * kSineTableEntries;
                int iptr = static_cast<int>(idx);
                if (iptr >= kSineTableEntries) iptr = 0; // float rounding guard
                const float frac = idx - iptr;
                const int next = (iptr + 1) & (kSineTableEntries - 1);
                const float s = mSine[iptr] + frac * (mSine[next] - mSine[iptr]);
                const float gain = 1.0f - depth * 0.5f * (1.0f - s);
                left[i] *= gain;
                right[i] *= gain;
            }
            phase += phaseStep;
            if (phase >= 1.0f) phase -= 1.0f;
        }
        mPhaseCycles.store(phase, std::memory_order_relaxed);
        mDepthApplied.store(depth, std::memory_order_relaxed);
    }

    void setParameter(uint32_t parameterId, float value) noexcept override {
        if (parameterId == lsp::kParamTremoloRate) {
            if (std::isfinite(value)) mRate.store(std::max(0.1f, std::min(20.0f, value)), std::memory_order_relaxed);
        } else if (parameterId == lsp::kParamTremoloDepth) {
            if (std::isfinite(value)) mDepth.store(std::max(0.0f, std::min(100.0f, value)), std::memory_order_relaxed);
        }
    }

    float getParameter(uint32_t parameterId) const noexcept override {
        switch (parameterId) {
            case lsp::kParamTremoloRate: return mRate.load(std::memory_order_relaxed);
            case lsp::kParamTremoloDepth: return mDepth.load(std::memory_order_relaxed);
            default: return 0.0f;
        }
    }

    void setBypassed(bool bypassed) noexcept override { mBypassed.store(bypassed, std::memory_order_relaxed); }
    bool isBypassed() const noexcept override { return mBypassed.load(std::memory_order_relaxed); }
    const char* stableId() const noexcept override { return lsp::kIdTremolo; }
    bool isAvailable() const noexcept override { return mPrepared; }
    int getLatencyFrames() const noexcept override { return 0; }

private:
    // Descriptor defaults: a fresh instance reports the documented defaults
    // (rate 5 Hz, depth 50 %) before the UI pushes anything.
    float* mSine = nullptr; // sine table, 2048 floats (8 KiB), sized in prepare()
    double mSampleRate = 0.0;
    bool mPrepared = false;
    std::atomic<float> mRate{5.0f};
    std::atomic<float> mDepth{50.0f};
    std::atomic<bool> mBypassed{true}; // default bypassed (safe), like LadspaEffect
    std::atomic<float> mPhaseCycles{0.0f};
    std::atomic<float> mDepthApplied{0.0f};
};

} // namespace piano
