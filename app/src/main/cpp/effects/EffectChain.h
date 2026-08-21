#pragma once

#include "AudioEffect.h"
#include "lsp/LspEffectIds.h"

#include <memory>

namespace piano {

// Fixed master effect chain: EQ → Compressor → Limiter.
//
// Realtime contract (plan Phase 5/18):
//  - prepare() allocates the planar scratch buffers and creates/prepares the
//    effects (worker/control thread).
//  - process() deinterleaves the stereo input ONCE into the scratch L/R
//    buffers, runs each effect in order on the planar data, and interleaves
//    back ONCE. No per-effect deinterleave. No alloc/lock/I/O.
//  - The effect array is fixed (3 slots); it never resizes in process().
//
// Insertion point in the engine:
//     mMixer.mix(output, safeFrames);
//     mMasterEffects.process(output, safeFrames);   // ← here
//     mMasterBus.process(output, safeFrames);
class EffectChain {
public:
    EffectChain();
    ~EffectChain();

    EffectChain(const EffectChain&) = delete;
    EffectChain& operator=(const EffectChain&) = delete;

    // Allocate scratch buffers and prepare all effects at the given sample
    // rate / max block size. Returns false only if scratch allocation fails;
    // individual effect failures leave that effect bypassed (the chain still
    // runs). NOT realtime-safe.
    bool prepare(double sampleRate, int maxFrames);

    // Realtime processing on an interleaved stereo buffer (L R L R ...),
    // numFrames * 2 elements. In-place. When the chain is unavailable or all
    // effects are bypassed, this is effectively a no-op passthrough.
    void process(float* interleaved, int numFrames) noexcept;

    // Access an effect by slot (0..kMasterEffectCount-1). Returns nullptr if
    // the slot is out of range. The pointer is stable for the chain's lifetime
    // after prepare().
    AudioEffect* effect(int slot) const;

    int effectCount() const { return lsp::kMasterEffectCount; }

    // True if the bundle loaded and at least one effect prepared.
    bool isAvailable() const;

    // Load the LSP bundle and (re)build the effects. Worker thread. Returns
    // the number of effects that became available (0..3).
    int loadBundle(const char* soPath, double sampleRate, int maxFrames);

private:
    void disposeEffects();

    float* mLeft = nullptr;
    float* mRight = nullptr;
    int mMaxFrames = 0;
    double mSampleRate = 0.0;
    bool mPrepared = false;

    // Fixed array of 3 effect pointers. Immutable snapshot after prepare().
    AudioEffect* mEffects[lsp::kMasterEffectCount] = {};
};

} // namespace piano
