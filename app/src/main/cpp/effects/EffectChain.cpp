#include "EffectChain.h"

#include "ladspa/LadspaRegistry.h"
#include "lsp/LspEffectFactory.h"

#include <cstdlib>
#include <cstring>

namespace piano {

EffectChain::EffectChain() = default;

EffectChain::~EffectChain() {
    disposeEffects();
    std::free(mLeft);
    std::free(mRight);
}

void EffectChain::disposeEffects() {
    for (int i = 0; i < lsp::kMasterEffectCount; ++i) {
        delete mEffects[i];
        mEffects[i] = nullptr;
    }
}

bool EffectChain::prepare(double sampleRate, int maxFrames) {
    if (sampleRate <= 0.0 || maxFrames <= 0) {
        return false;
    }

    // (Re)allocate the planar scratch buffers if the max grew.
    if (maxFrames > mMaxFrames || !mLeft || !mRight) {
        std::free(mLeft);
        std::free(mRight);
        mLeft  = static_cast<float*>(std::malloc(maxFrames * sizeof(float)));
        mRight = static_cast<float*>(std::malloc(maxFrames * sizeof(float)));
        if (!mLeft || !mRight) {
            return false;
        }
        mMaxFrames = maxFrames;
    }
    mSampleRate = sampleRate;

    // Prepare each existing effect at the new rate/max. Effects are created in
    // loadBundle(); prepare() here re-instantiates them at the current rate.
    for (int i = 0; i < lsp::kMasterEffectCount; ++i) {
        if (mEffects[i]) {
            mEffects[i]->prepare(sampleRate, maxFrames);
        }
    }
    mPrepared = true;
    return true;
}

int EffectChain::loadBundle(const char* soPath, double sampleRate, int maxFrames) {
    // Open the LADSPA bundle (dlopen) — worker thread only.
    ladspa::LadspaRegistry::instance().open(soPath);

    // Rebuild the fixed chain from the registry.
    disposeEffects();
    for (int i = 0; i < lsp::kMasterEffectCount; ++i) {
        mEffects[i] = lsp::LspEffectFactory::create(i);
    }

    // Size scratch + prepare effects.
    if (!prepare(sampleRate, maxFrames)) {
        return 0;
    }

    int available = 0;
    for (int i = 0; i < lsp::kMasterEffectCount; ++i) {
        if (mEffects[i] && mEffects[i]->isAvailable()) {
            ++available;
        }
    }
    return available;
}

void EffectChain::process(float* interleaved, int numFrames) noexcept {
    if (!mPrepared || numFrames <= 0) {
        return;
    }
    if (numFrames > mMaxFrames) {
        numFrames = mMaxFrames;
    }

    // Fast path: if no effect is available/enabled, the interleaved buffer is
    // left untouched (passthrough). Avoid the deinterleave/interleave cost.
    bool anyActive = false;
    for (int i = 0; i < lsp::kMasterEffectCount; ++i) {
        if (mEffects[i] && mEffects[i]->isAvailable() &&
            !mEffects[i]->isBypassed()) {
            anyActive = true;
            break;
        }
    }
    if (!anyActive) {
        return;
    }

    // Deinterleave once: L R L R ... → mLeft[], mRight[].
    for (int i = 0; i < numFrames; ++i) {
        mLeft[i]  = interleaved[i * 2];
        mRight[i] = interleaved[i * 2 + 1];
    }

    // Run the fixed chain on the planar data (in-place on mLeft/mRight).
    for (int i = 0; i < lsp::kMasterEffectCount; ++i) {
        if (mEffects[i] && mEffects[i]->isAvailable()) {
            mEffects[i]->process(mLeft, mRight, numFrames);
        }
    }

    // Interleave once back into the caller's buffer.
    for (int i = 0; i < numFrames; ++i) {
        interleaved[i * 2]     = mLeft[i];
        interleaved[i * 2 + 1] = mRight[i];
    }
}

AudioEffect* EffectChain::effect(int slot) const {
    if (slot < 0 || slot >= lsp::kMasterEffectCount) {
        return nullptr;
    }
    return mEffects[slot];
}

bool EffectChain::isAvailable() const {
    for (int i = 0; i < lsp::kMasterEffectCount; ++i) {
        if (mEffects[i] && mEffects[i]->isAvailable()) {
            return true;
        }
    }
    return false;
}

} // namespace piano
