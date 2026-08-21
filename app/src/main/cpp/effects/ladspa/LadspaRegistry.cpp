#include "LadspaRegistry.h"
#include "lsp/LspLog.h"

#include <dlfcn.h>
#include <cstring>
#include <string>

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

    mLastError.clear();

    if (!soPath || soPath[0] == '\0') {
        mLastError = "empty bundle path";
        LSP_LOGE("open: %s", mLastError.c_str());
        return false;
    }

    // Clear any stale dlerror, then dlopen. RTLD_LOCAL keeps LSP symbols out of
    // the global namespace; RTLD_NOW resolves all NEEDED deps immediately so a
    // missing dependency (the usual Android failure) surfaces here, with a
    // usable dlerror() message, rather than on first audio callback.
    dlerror();
    void* h = dlopen(soPath, RTLD_NOW | RTLD_LOCAL);
    if (!h) {
        const char* err = dlerror();
        mLastError = err ? err : "dlopen returned NULL (no dlerror)";
        LSP_LOGE("dlopen(\"%s\") failed: %s", soPath, mLastError.c_str());
        return false;
    }

    dlerror();
    auto fn = reinterpret_cast<const LADSPA_Descriptor* (*)(unsigned long)>(
        dlsym(h, "ladspa_descriptor"));
    if (!fn) {
        const char* err = dlerror();
        mLastError = std::string("dlsym(ladspa_descriptor) failed: ") +
                     (err ? err : "symbol not found");
        LSP_LOGE("%s (path=%s)", mLastError.c_str(), soPath);
        dlclose(h);
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
    LSP_LOGI("bundle loaded: %s (%lu descriptors)", soPath, n);
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

const char* LadspaRegistry::lastError() const {
    return mLastError.c_str();
}

} // namespace ladspa
} // namespace piano
