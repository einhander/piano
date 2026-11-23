#pragma once

#include "effects/guitar/GuitarRigEffect.h"
#include "synth/InstrumentChannelContract.h"

#include <array>
#include <atomic>
#include <memory>
#include <mutex>

namespace piano {
namespace guitar {

// Per-channel guitar rig slots (mirrors InstrumentEffectManager's lifecycle
// rules, but a SEPARATE manager — the rig never joins the LSP slot tables).
//
// One mono-pair GuitarRigEffect per instrument channel, state for all
// kInstrumentChannelCount (128) channels, sized for the rig's 12 params —
// deliberately NOT mPending[128][9][512]: a plain mDesired[kChannels]
// [kParamCount] atomic row (defaults live, no per-set flag needed).
//
// Threading:
//  - instantiate()/reprepareQuiescent()/resetChannelRange() are control or
//    worker-thread only and NEVER reachable from the audio callback;
//    process() publishes/reads only atomics + its own scratch.
//  - A rig is instantiated lazily on the setEffectEnabledChecked(true) path
//    (JNI "enabled" call). Params set before instantiate are honored: the
//    mDesired row is applied to the effect before prepare().
//  - Audio callback gating per channel: published && rackEnabled && effectEnabled.
//    The manager drives GuitarRigEffect::setBypassed from the gate every
//    block; the effect self-ramps and reports wantsFrames()==false once
//    fully dry, at which point the manager skips deinterleave/interleave and
//    the effect skips ALL DSP including the cabinet FFT.
//  - Interim policy: amp-model/cab changes on a live rig are a pending flag
//    (setParameterChecked returns false; the UI surfaces it). The manager
//    applies them at instantiate/prepare and at quiescence via
//    applyPendingConfig(); a sample-rate reprepare applies current values.
class GuitarRigManager {
public:
    enum ErrorState : int { Ok = 0, AllocationFailed = 1, EngineInitFailed = 2 };

    static constexpr int kChannels = piano::kInstrumentChannelCount;

    GuitarRigManager();
    ~GuitarRigManager();
    GuitarRigManager(const GuitarRigManager&) = delete;
    GuitarRigManager& operator=(const GuitarRigManager&) = delete;

    // Worker thread, one-shot at engine init. Allocates the per-channel
    // planar deinterleave scratch only; rigs are lazy.
    bool prepare(double sampleRate, int maxFrames);
    // Worker thread while callbacks are quiesced (FluidSynth worker gate,
    // same sequence as InstrumentEffectManager::reprepareQuiescent on a
    // sample-rate change). Rebuilds every published rig at the new rate from
    // the mDesired rows; a channel whose rebuild fails is released (dry) and
    // flagged EngineInitFailed. Returns false if any rebuild failed.
    bool reprepareQuiescent(double sampleRate, int maxFrames);

    // Control thread. Creates + prepares the rig for the channel (honoring
    // the mDesired param row). Idempotent.
    bool instantiate(int channel);

    bool setEffectEnabledChecked(int channel, bool enabled);
    void setEffectEnabled(int channel, bool enabled) noexcept;
    bool effectEnabled(int channel) const noexcept;
    void setRackEnabled(int channel, bool enabled) noexcept;
    bool rackEnabled(int channel) const noexcept;

    bool setParameterChecked(int channel, uint32_t parameterId, float value) noexcept;
    bool getParameterChecked(int channel, uint32_t parameterId, float& value) const noexcept;

    int paramCount() const noexcept;
    bool paramInfo(int index, uint32_t& id, float& minValue, float& maxValue,
                   float& defaultValue, bool& logarithmic, bool& integer,
                   bool& toggled) const noexcept;
    const char* paramName(int index) const noexcept;

    bool isAvailable(int channel) const noexcept;
    int errorState(int channel) const noexcept;
    bool hasPendingConfig(int channel) const noexcept;
    // Control/worker thread, audio quiescent: applies the pending amp
    // model/cab config. Returns false when the rig could not be rebuilt.
    bool applyPendingConfig(int channel);
    int effectLatencyFrames(int channel) const noexcept;

    // Audio thread. Interleaved stereo buses (like InstrumentEffectManager).
    void process(float* const buses[kChannels], int frames) noexcept;

    // Worker thread while callbacks are quiesced: releases the rigs and
    // clears all state for [first, first+count).
    bool resetChannelRange(int first, int count) noexcept;

private:
    // Caller must hold control-busy + lifetime (reprepare/reset).
    void releaseChannelLocked(int channel) noexcept;

    std::array<std::unique_ptr<GuitarRigEffect>, kChannels> mEffects{};
    std::array<std::atomic<GuitarRigEffect*>, kChannels> mPublished{};
    std::array<std::atomic<bool>, kChannels> mRackEnabled{};
    std::array<std::atomic<bool>, kChannels> mEffectEnabled{};
    // Desired-state atomics (defaults from the descriptor table). The single
    // source of truth for pending-before-instantiate parameter honoring.
    std::array<std::array<std::atomic<float>, kParamCount>, kChannels> mDesired{};
    std::array<std::atomic<int>, kChannels> mErrorState{};
    std::array<float*, kChannels> mLeft{};
    std::array<float*, kChannels> mRight{};
    double mSampleRate = 0.0;
    int mMaxFrames = 0;
    bool mPrepared = false;
    std::atomic_flag mControlBusy = ATOMIC_FLAG_INIT;
    // Control/getter lifetime guard. Audio callback never takes this mutex.
    mutable std::mutex mLifetimeMutex;
};

} // namespace guitar
} // namespace piano
