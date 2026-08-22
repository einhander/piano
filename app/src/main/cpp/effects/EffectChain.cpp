#include "EffectChain.h"
#include "lsp/LspLog.h"

#include "ladspa/LadspaRegistry.h"
#include "lsp/LspEffectFactory.h"

#include <cstdlib>
#include <cstring>
#include <string>

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
        LSP_LOGE("prepare: invalid rate=%g maxFrames=%d", sampleRate, maxFrames);
        return false;
    }

    // (Re)allocate the planar scratch buffers if the max grew.
    if (maxFrames > mMaxFrames || !mLeft || !mRight) {
        std::free(mLeft);
        std::free(mRight);
        mLeft  = static_cast<float*>(std::malloc(maxFrames * sizeof(float)));
        mRight = static_cast<float*>(std::malloc(maxFrames * sizeof(float)));
        if (!mLeft || !mRight) {
            LSP_LOGE("prepare: scratch alloc failed (maxFrames=%d)", maxFrames);
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
    mLoadError.clear();

    // Open the LADSPA bundle (dlopen) — worker thread only.
    bool ok = ladspa::LadspaRegistry::instance().open(soPath);
    if (!ok) {
        // Registry logs the dlerror; capture it here too for the UI/log.
        const char* e = ladspa::LadspaRegistry::instance().lastError();
        mLoadError = std::string("bundle open failed: ") + (e && *e ? e : "unknown");
        LSP_LOGE("loadBundle: %s", mLoadError.c_str());
        return 0;
    }

    // Rebuild the fixed chain from the registry.
    disposeEffects();
    for (int i = 0; i < lsp::kMasterEffectCount; ++i) {
        mEffects[i] = lsp::LspEffectFactory::create(i);
    }

    // Size scratch + prepare effects.
    if (!prepare(sampleRate, maxFrames)) {
        mLoadError = "prepare() failed (scratch allocation)";
        LSP_LOGE("loadBundle: %s", mLoadError.c_str());
        return 0;
    }

    int available = 0;
    for (int i = 0; i < lsp::kMasterEffectCount; ++i) {
        if (mEffects[i] && mEffects[i]->isAvailable()) {
            ++available;
        } else {
            const lsp::LadspaBinding* b = lsp::bindingForSlot(i);
            LSP_LOGE("loadBundle: slot %d unavailable (label lookup failed for \"%s\")",
                     i, b ? b->label : "?");
        }
    }
    if (available == 0) {
        // The bundle opened (dlopen + ladspa_descriptor resolved) but none of
        // the 3 expected LADSPA Labels were found. The two root causes are:
        //   (a) the descriptor table is empty (mCount==0) — the aarch64 cross
        //       build dead-stripped the per-plugin factory registrations so
        //       plug::Factory::root() enumerates nothing;
        //   (b) the table is populated but the Labels don't match the URIs in
        //       kBindings[] (a build/config drift).
        // Append the registry's dump (count + first N labels) and the expected
        // labels to the error so it surfaces in the in-app log — logcat is not
        // reachable on the dev machine, so this string is the only diagnostic
        // channel that reaches the user.
        const char* dump = ladspa::LadspaRegistry::instance().descriptorDump();
        mLoadError = "bundle loaded but no effect descriptors matched "
                     "(label lookup failed for all 3 slots)";
        mLoadError += "\nRegistry dump: ";
        mLoadError += (dump && *dump) ? dump : "(none)";
        mLoadError += "\nExpected labels:";
        for (int i = 0; i < lsp::kMasterEffectCount; ++i) {
            const lsp::LadspaBinding* b = lsp::bindingForSlot(i);
            mLoadError += "\n  slot ";
            mLoadError += std::to_string(i);
            mLoadError += " (";
            mLoadError += b ? b->stableId : "?";
            mLoadError += ") -> ";
            mLoadError += b ? b->label : "?";
        }
        LSP_LOGE("loadBundle: %s", mLoadError.c_str());
    } else {
        LSP_LOGI("loadBundle: %d/%d effects available", available, lsp::kMasterEffectCount);
    }
    return available;
}

const char* EffectChain::loadError() const {
    return mLoadError.c_str();
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
