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
    // Track per-slot diagnosis: was the LADSPA Label found in the registry?
    // available==0 can mean EITHER "label not found" (descriptor == nullptr,
    // the original hypothesis) OR "label found but prepare()/instantiate()
    // failed" (mDescriptor non-null but the LSP instantiate returned nullptr
    // — the wrapper->init() cleanup-bypass path). These have completely
    // different fixes, so we must distinguish them in the error report.
    int labelsFound = 0;
    for (int i = 0; i < lsp::kMasterEffectCount; ++i) {
        const lsp::LadspaBinding* b = lsp::bindingForSlot(i);
        const LADSPA_Descriptor* d = (b) ? ladspa::LadspaRegistry::instance().findByLabel(b->label) : nullptr;
        if (mEffects[i] && mEffects[i]->isAvailable()) {
            ++available;
        } else if (d != nullptr) {
            ++labelsFound;
            LSP_LOGE("loadBundle: slot %d label FOUND (\"%s\") but effect unavailable — "
                     "prepare()/instantiate() failed (see lsp_prepare_marker.log on next launch)",
                     i, b ? b->label : "?");
        } else {
            LSP_LOGE("loadBundle: slot %d label NOT FOUND (\"%s\")",
                     i, b ? b->label : "?");
        }
    }
    if (available == 0) {
        const char* dump = ladspa::LadspaRegistry::instance().descriptorDump();
        mLoadError = "bundle loaded but 0/3 effects available";
        mLoadError += "\nRegistry dump: ";
        mLoadError += (dump && *dump) ? dump : "(none)";
        mLoadError += "\nPer-slot diagnosis:";
        for (int i = 0; i < lsp::kMasterEffectCount; ++i) {
            const lsp::LadspaBinding* b = lsp::bindingForSlot(i);
            const LADSPA_Descriptor* d = (b) ? ladspa::LadspaRegistry::instance().findByLabel(b->label) : nullptr;
            mLoadError += "\n  slot ";
            mLoadError += std::to_string(i);
            mLoadError += " (";
            mLoadError += b ? b->stableId : "?";
            mLoadError += ") label=\"";
            mLoadError += b ? b->label : "?";
            mLoadError += "\" -> ";
            if (d != nullptr) {
                mLoadError += "FOUND (uid=";
                mLoadError += std::to_string(d->UniqueID);
                mLoadError += ") but prepare()/instantiate() FAILED "
                              "(LSP wrapper->init() returned non-OK; the "
                              "sub-step + rc are in lsp_prepare_marker.log, "
                              "surfaced on the NEXT launch)";
            } else {
                mLoadError += "NOT FOUND in the descriptor table";
            }
        }
        mLoadError += "\nSummary: ";
        mLoadError += std::to_string(labelsFound);
        mLoadError += "/3 labels found, 0 prepared. ";
        mLoadError += (labelsFound == lsp::kMasterEffectCount)
            ? "Root cause is instantiate(), not label lookup — the LSP "
              "wrapper fails to init on-device (likely a missing "
              "builtin://manifest or resource the BuiltinLoader can't "
              "resolve in the APK native-lib dir)."
            : "Root cause is label lookup (some expected Label is absent).";
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
