#include "LadspaRegistry.h"

#include <dlfcn.h>
#include <cstring>

namespace piano {
namespace ladspa {

LadspaRegistry& LadspaRegistry::instance() {
    static LadspaRegistry reg;
    return reg;
}

LadspaRegistry::LadspaRegistry() = default;

LadspaRegistry::~LadspaRegistry() {
    if (mHandle) {
        dlclose(mHandle);
        mHandle = nullptr;
    }
}

bool LadspaRegistry::open(const char* soPath) {
    std::lock_guard<std::mutex> lock(mMutex);

    // Already loaded with a handle: nothing to do (re-opening the same path is
    // a no-op; a different path is not supported in the first iteration).
    if (mLoaded) {
        return mLoaded;
    }

    if (!soPath || soPath[0] == '\0') {
        return false;
    }

    void* h = dlopen(soPath, RTLD_NOW | RTLD_LOCAL);
    if (!h) {
        // Diagnostic only — never log from the audio thread. open() is
        // worker-thread.
        mLoaded = false;
        return false;
    }

    auto fn = reinterpret_cast<const LADSPA_Descriptor* (*)(unsigned long)>(
        dlsym(h, "ladspa_descriptor"));
    if (!fn) {
        dlclose(h);
        mLoaded = false;
        return false;
    }

    mHandle = h;
    mDescriptorFn = fn;

    // Count descriptors (ladspa_descriptor returns NULL past the last).
    unsigned long n = 0;
    while (fn(n) != nullptr) {
        ++n;
    }
    mCount = n;
    mLoaded = true;
    return true;
}

bool LadspaRegistry::isLoaded() const {
    return mLoaded;
}

const LADSPA_Descriptor* LadspaRegistry::findByLabel(const char* label) const {
    if (!mLoaded || !label) {
        return nullptr;
    }
    for (unsigned long i = 0; i < mCount; ++i) {
        const LADSPA_Descriptor* d = mDescriptorFn(i);
        if (d && d->Label && std::strcmp(d->Label, label) == 0) {
            return d;
        }
    }
    return nullptr;
}

unsigned long LadspaRegistry::descriptorCount() const {
    return mCount;
}

} // namespace ladspa
} // namespace piano
