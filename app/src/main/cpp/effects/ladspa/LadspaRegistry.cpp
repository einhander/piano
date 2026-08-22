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
    //
    // Try the absolute path first; if that fails (the common case on Android
    // 11+/sdk 30+ where extractNativeLibs=false keeps the .so inside the APK
    // and never materializes it on disk), fall back to the bare soname. The
    // bundle is pre-loaded via System.loadLibrary("lsp-plugins-ladspa") in
    // NativeEngineBridge, so the linker has already mapped it (and resolved
    // its NEEDED deps) into the app namespace; dlopen by soname then returns
    // that already-loaded handle. This is path-independent and works whether
    // or not the lib was extracted to nativeLibraryDir.
    void* h = nullptr;
    std::string soname;

    dlerror();
    h = dlopen(soPath, RTLD_NOW | RTLD_LOCAL);
    if (!h) {
        const char* err1 = dlerror();
        LSP_LOGE("dlopen(\"%s\") failed: %s — trying soname fallback", soPath,
                 err1 ? err1 : "unknown");

        // Derive the soname (basename of the path).
        const char* slash = std::strrchr(soPath, '/');
        soname = slash ? (slash + 1) : soPath;

        dlerror();
        h = dlopen(soname.c_str(), RTLD_NOW | RTLD_LOCAL);
        if (!h) {
            const char* err2 = dlerror();
            mLastError = std::string("dlopen by path (") + (err1 ? err1 : "?") +
                         ") and by soname \"" + soname + "\" (" +
                         (err2 ? err2 : "not found") +
                         ") both failed; is System.loadLibrary preload active?";
            LSP_LOGE("open: %s", mLastError.c_str());
            return false;
        }
        LSP_LOGI("bundle loaded by soname fallback: %s", soname.c_str());
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
