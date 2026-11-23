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
// ── Configuration policy: SAFE LIVE SWAP (docs/guitar-fx-port.md §Live
//    rig swap, spec §10) ────────────────────────────────────────────────────
// kAmpModel (fx_amp_set_model → fx_amp_init memsets the amp state, amp.c:676)
// and kCabType (fx_cab_generate_ir mallocs + re-plans the FFT, engine.c:514)
// are NOT live-safe: this object NEVER mutates its engines' model/IR while a
// callback may be processing. GuitarRigManager implements the full two-phase
// protocol instead: the worker thread BUILDS a complete replacement rig
// (this class, fully prepared from the desired row) and hands it over a
// single-slot atomic mailbox; the audio callback commits the swap at its own
// top-of-step — ramping the old rig's wet to 0 (forced bypass through the
// existing crossfade ramp at setRampSeconds(10 ms)), exchanging pointers,
// retiring the old rig into the worker-owned RETIRED mailbox, then ramping
// the new rig back in. applyConfigNow() below therefore stays a QUIESCENT
// path only (prepare + manager quiescent apply). All other parameters (drive
// type/gain/tone, amp gain/bass/mid/treble, cab engage, output, mix) are
// live-safe as verified above and keep flowing through setParameter().
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
    // bypass atomic, the swap-prime counter and audio-thread-owned ramp
    // state, so call it from the audio thread. TRUE while priming (a
    // just-committed rig must run its engines even at wet==0).
    bool wantsFrames() const noexcept {
        return mPrimeFrames > 0 || mWetApplied > 0.0f ||
               (!mBypassed.load(std::memory_order_acquire) &&
                mParams[kParamMix].load(std::memory_order_relaxed) > 0.0f);
    }

    // Control/worker thread, ONLY while the audio callback is quiescent (or
    // before the engine is ever published): applies the current kAmpModel +
    // kCabType/kCabEnabled atomics to both live engines. This is the
    // quiescent reconciliation path (used by prepare() and by the manager's
    // applyPendingConfig on the no-mailbox branch); live discrete changes go
    // through the manager's two-phase swap, never through this call while
    // audio runs. Returns false if the IR regeneration failed.
    bool applyConfigNow() noexcept;

    // ── Live-swap support (driven by GuitarRigManager's per-channel state
    //    machine from the audio callback; see the policy comment above) ────
    // Audio-thread only: current applied wet level (crossfade ramp value).
    float wetApplied() const noexcept { return mWetApplied; }
    // Audio-thread only: the ramp target process() is chasing right now
    // (0 while bypassed, else the mix param).
    float wetTarget() const noexcept {
        return mBypassed.load(std::memory_order_acquire)
                   ? 0.0f
                   : mParams[kParamMix].load(std::memory_order_relaxed);
    }
    // ── m6 dedupe support (GuitarRigManager only) ───────────────────────────
    // Immutable discrete-config snapshot this rig was BUILT with: the packed
    // (ampModel<<2|cabType) pair read from the desired row by the manager's
    // buildCandidate(). The commit records it into the manager's applied-config
    // key — reading the live mirror at commit time is NOT safe: the next
    // accepted request mirrors into the just-committed rig and would corrupt
    // the key (false dedupe = silently dropped change; T2 in
    // tests/guitar_rig_swap_test.cpp is the regression probe). Set once by
    // the builder before the rig is ever published; read-only afterwards.
    void setBuildConfigSnapshot(int packed) noexcept { mBuildConfigSnapshot = packed; }
    int buildConfigSnapshot() const noexcept { return mBuildConfigSnapshot; }

    // ── Silent engine priming after a live-swap commit ────────────────────
    // A freshly prepared rig's internal state (amp DC-blocker / power-sag /
    // gate / cab overlap buffers) starts at zero, so its FIRST blocks of a
    // real signal contain an internal transient. Injecting that transient
    // through the fading-in crossfade is the dominant measured click
    // (host stress: max|Δ| 0.483 without priming). startPriming(n) (the
    // manager's commit, audio thread) makes the next n frames of process()
    // run the FULL wet signal path but emit the dry samples verbatim, so
    // the transient happens inaudibly and the fade-in that follows is
    // continuous. isPriming()/wantsFrames() keep the manager from skipping
    // the (cheap, already-budgeted) DSP during the priming window.
    void startPriming(int frames) noexcept { mPrimeFrames = frames; }
    bool isPriming() const noexcept { return mPrimeFrames > 0; }
    // Dry/wet ramp duration in seconds. Default is the 5 ms rack-wide click-
    // fade convention; the manager raises it to 10 ms around a live rig swap
    // (fade-out with the old rig, fade-in with the new one). Atomic so it may
    // be stored from the callback state machine; read once per process()
    // block.
    void setRampSeconds(float seconds) noexcept {
        mRampSeconds.store(seconds, std::memory_order_relaxed);
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
    std::atomic<float> mRampSeconds{0.005f}; // 5 ms convention; 10 ms during swap

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
    // Audio-thread-owned silent-priming countdown (see startPriming()).
    int mPrimeFrames = 0;
    // Builder-only immutable config snapshot (see setBuildConfigSnapshot()).
    // Written once before publish by GuitarRigManager::buildCandidate,
    // never by the audio path; plain int is fine (single writer, publish
    // boundary is the mPublished exchange / ownership transfer).
    int mBuildConfigSnapshot = -1;
};

} // namespace guitar
} // namespace piano
