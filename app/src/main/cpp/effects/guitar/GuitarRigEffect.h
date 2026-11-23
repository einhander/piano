#pragma once

#include "effects/AudioEffect.h"
#include "effects/guitar/GuitarRigParams.h"

#include <array>
#include <atomic>
#include <cstdint>

// Opaque 0xFX engine handle (vendored, pinned SHA 5b24877; see
// docs/guitar-fx-port.md). Only the public header is ever included.
struct fx_engine;

namespace piano {
namespace guitar {

// GuitarRigEffect — stereo guitar rig over the vendored 0xFX DSP core.
//
// Topology: TWO independent MONO fx_engine_t instances (one per side). The
// upstream engine is mono (fx_engine_process takes one float* in / one
// float* out), so the AudioEffect planar stereo contract is satisfied by
// feeding each engine its own channel pointer — no internal deinterleave
// and provably zero L/R bleed (test 7 asserts the untouched-side property).
//
// Signal path per side (upstream order): input metering → tuner feed
// (always on) → noise gate → PRE pedals (the three drive types, created
// once, toggled by bypass) → amp → cabinet (synthetic IR, FFT overlap-add)
// → mic sim (DI default) → NaN/inf sanitize → looper mix (never armed) →
// master volume + hard clip → output.
//
// ── Real-time safety, verified against the pinned sources ────────────────
//  * in-place (in==out): SAFE. fx_engine_process copies the input into
//    scratch_a BEFORE any processing (engine.c:96) and only writes the
//    output pointer at the very end (engine.c:211); the tuner feed in
//    between (engine.c:105) reads the untouched input. So calling with
//    input==output==channel pointer is bit-safe.
//  * pedal bypass: FLAG ONLY. fx_pedal_set_bypass writes p->bypass
//    (engine.c:342); fx_pedal_process returns early on the flag
//    (pedals.c:2614). No realloc on toggle → live-safe. The drive-type
//    switch is therefore implemented as bypass flips of the three
//    pre-created pedals — never add/remove (add/remove calloc/free →
//    worker-only).
//  * pedal params: fx_pedal_set_param writes one float in a static array
//    (engine.c:329-334); jade/rodent/mammoth *_process bodies contain zero
//    allocations and read p->params[] per block → live-safe.
//  * amp params: fx_amp_set_param writes amp->params[] with a 0..1 clamp
//    (engine.c:489-495); tone-stack coefficients are lazily recomputed
//    inside fx_amp_process from a value cache (amp.c tone_cache) → live-safe.
//  * cab bypass: flag only (engine.c:574-577), fx_cab_process checks it
//    (cab_ir.c) → live-safe.
//  * block clamp: fx_engine_process SILENTLY drops frames beyond
//    FX_MAX_BLOCK_SIZE=4096 (engine.c:84). This effect owns the chunking:
//    every callback is split into ≤4096-frame chunks and ALL frames are
//    consumed. The vendored cab overlap-add patch makes segmentation
//    bit-exact (docs/guitar-fx-port.md §Patches).
//  * cabinet FFT cost: fft_size = next_pow2(block_size+ir_len-1) = 8192
//    (cab_ir.c:233) regardless of the actual n, and fx_cab_process pays a
//    full-size FFT pair PER CALL even for tiny n (measured ~64× per-frame
//    cost at n=64 vs n=4096, tests/README.cab-segmented-convolution.md).
//    The chunk loop therefore uses the LARGEST allowed chunks.
//
// ── Interim configuration policy (live model/cab swap lands next lane) ──
// kAmpModel (fx_amp_set_model → fx_amp_init memsets the amp state, amp.c:676)
// and kCabType (fx_cab_generate_ir mallocs + re-plans the FFT, engine.c:514)
// are NOT live-safe: on a published, prepared engine their changes are
// recorded in the pending atomics, hasPendingConfig() is set, and the audio
// rate path ignores them. The manager applies model/cab only at
// instantiate/prepare/quiescence via applyConfigNow(). All other parameters
// (drive type/gain/tone, amp gain/bass/mid/treble, cab engage, output, mix)
// are live-safe as verified above.
//
// ── Output/level normalization scheme ────────────────────────────────────
// The engine's master volume (fx_engine_set_master_volume) and the amp-model
// FX_AMP_PARAM_MASTER / FX_AMP_PARAM_VOLUME are deliberately NOT repurposed
// as the rig's Output knob: the amp's MASTER/VOLUME are squared inside the
// power-amp model (amp.c:808-812 `mv=master*master`, `vv=volume*volume`) so
// moving them changes distortion character, not just level; the engine
// master multiplies pre-clip and gates the hard-clip threshold. Both stay at
// their upstream defaults (1.0 and 0.5). Piano's kOutput instead applies as
// an external linear trim around the dry/wet crossfade:
//     out = dry*(1-mixA) + (2*kOutput)*wet*mixA
// with outGain=2*kOutput → unity at the 0.5 default, max +6 dB, and the
// upstream ±1 hard clip still limits the wet path before the trim. The
// crossfade is level-normalized (dry energy falls exactly as wet rises, so
// mix=0.5 has no double-level bump), and mixA (applied mix) ramps linearly
// over 5 ms — the rack-wide click-fade convention used by
// InstrumentEffectManager/TremoloEffect — toward the target
// (bypassed || mix==0 ? 0 : kMix). At mixA==0 the effect is fully dry:
// process() returns without touching the buffers (bit-identical passthrough,
// no FFT cost) and wantsFrames() reports false so the manager can skip even
// the copy.
class GuitarRigEffect final : public AudioEffect {
public:
    // Upstream silent clamp (engine.c:84). We never hand the engine more.
    static constexpr int kMaxEngineBlock = 4096;
    // Measured in tests/guitar_rig_effect_test.cpp (impulse @0 through the
    // whole rig at 48 kHz, n=4096, default config): the first non-zero
    // output frame is frame 0 — every rig stage is causal direct-form or
    // minimum-phase (the synthetic cab IR is a minimum-phase reconstruction,
    // cab_ir.c Step 2), and the overlap-add partitioned convolution emits
    // block-aligned with no added delay. Group delay = 0 frames.
    static constexpr int kMeasuredLatencyFrames = 0;

    GuitarRigEffect();
    ~GuitarRigEffect() override;
    GuitarRigEffect(const GuitarRigEffect&) = delete;
    GuitarRigEffect& operator=(const GuitarRigEffect&) = delete;

    // Worker/control thread. Creates both engines (calloc'd, ~130 KB each),
    // adds the three PRE pedals on both sides, generates the cab IR, applies
    // the current parameter atomics. Idempotent: re-prepare releases and
    // rebuilds everything.
    bool prepare(double sampleRate, int maxFrames) override;

    // Audio thread. Applies pending atomic parameter state via the
    // verified-live-safe setters, then runs BOTH mono engines chunked at
    // min(maxFrames, 4096) and crossfades with the ramped dry/wet mix.
    void process(float* left, float* right, int numFrames) noexcept override;

    void setParameter(uint32_t parameterId, float value) noexcept override;
    float getParameter(uint32_t parameterId) const noexcept override;

    void setBypassed(bool bypassed) noexcept override;
    bool isBypassed() const noexcept override;
    const char* stableId() const noexcept override { return kGuitarRigStableId; }
    bool isAvailable() const noexcept override;
    int getLatencyFrames() const noexcept override { return kMeasuredLatencyFrames; }

    // Audio-thread advisory: the manager may skip deinterleave + process
    // entirely when this is false (effect fully ramped dry). Reads only the
    // bypass atomic plus audio-thread-owned ramp state, so call it from the
    // audio thread.
    bool wantsFrames() const noexcept {
        return mWetApplied > 0.0f ||
               (!mBypassed.load(std::memory_order_acquire) &&
                mParams[kParamMix].load(std::memory_order_relaxed) > 0.0f);
    }

    // Control/worker thread, only while the audio callback is quiescent (or
    // before the engine is ever published): applies the current kAmpModel +
    // kCabType/kCabEnabled atomics to both live engines and clears the
    // pending flag. Returns false if the IR regeneration failed.
    bool applyConfigNow() noexcept;
    bool hasPendingConfig() const noexcept {
        return mConfigPending.load(std::memory_order_acquire);
    }

private:
    void releaseEngines() noexcept;
    bool createEngines(double sampleRate);
    void applyLiveParams() noexcept; // audio thread, from atomics
    int driveType() const noexcept;
    int ampModelIndex() const noexcept;
    int cabTypeIndex() const noexcept;

    struct fx_engine* mEngineL = nullptr;
    struct fx_engine* mEngineR = nullptr;
    // Pedal ids per side, indices match kDriveTypeJade/Rodent/Mammoth-1.
    int32_t mPedalL[3] = {-1, -1, -1};
    int32_t mPedalR[3] = {-1, -1, -1};

    // Piano-owned atomic parameter mirrors (defaults from the descriptor
    // table). The audio thread is the ONLY writer of engine state; the UI
    // thread only touches these atomics.
    std::array<std::atomic<float>, kParamCount> mParams;

    std::atomic<bool> mBypassed{true}; // safe default, like TremoloEffect
    std::atomic<bool> mPrepared{false};
    std::atomic<bool> mConfigPending{false};

    double mSampleRate = 0.0;
    int mMaxFrames = 0;
    int mChunkFrames = 0; // min(maxFrames, kMaxEngineBlock)

    // Pre-allocated dry copies + ramp state (allocated in prepare only).
    float* mDryL = nullptr;
    float* mDryR = nullptr;

    // Audio-thread-owned applied wet mix (crossfade ramp). Non-atomic by
    // contract: only touched from process()/wantsFrames() on the audio
    // thread.
    float mWetApplied = 0.0f;
};

} // namespace guitar
} // namespace piano
