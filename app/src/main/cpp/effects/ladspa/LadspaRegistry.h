#pragma once

#include "ladspa/ladspa.h"

#include <mutex>
#include <string>

namespace piano {
namespace ladspa {

// Loads the LSP LADSPA bundle (.so) once via dlopen and exposes descriptor
// lookup by LADSPA Label. dlopen + enumeration happen on a worker/control
// thread (never the audio thread). Once loaded, descriptor pointers are
// immutable and safe to read from the audio thread.
//
// The .so path is resolved at runtime; if it cannot be opened, lookup returns
// nullptr and the effect chain falls back to bypass (the engine stays up).
class LadspaRegistry {
public:
    static LadspaRegistry& instance();

    // Open the bundle at the given absolute path. Returns true on success.
    // Idempotent: re-opening with a different path reloads. NOT realtime-safe
    // (dlopen + dlerror). Call from a worker thread.
    bool open(const char* soPath);

    // True if a bundle is currently loaded.
    bool isLoaded() const;

    // Find a descriptor by its LADSPA Label (URI). Returns nullptr if not
    // loaded or not found. Safe to call from the audio thread once open() has
    // returned (the descriptor table is immutable after load).
    const LADSPA_Descriptor* findByLabel(const char* label) const;

    // Number of descriptors exposed (0 if not loaded).
    unsigned long descriptorCount() const;

    // Human-readable reason for the last open() failure (empty on success).
    // Worker-thread only; not for the audio thread.
    const char* lastError() const;

    LadspaRegistry(const LadspaRegistry&) = delete;
    LadspaRegistry& operator=(const LadspaRegistry&) = delete;

private:
    LadspaRegistry();
    ~LadspaRegistry();

    mutable std::mutex mMutex;  // guards open()/close() only
    void* mHandle = nullptr;
    const LADSPA_Descriptor* (*mDescriptorFn)(unsigned long) = nullptr;
    unsigned long mCount = 0;
    bool mLoaded = false;
    std::string mLastError;  // set on open() failure (worker thread)
};

} // namespace ladspa
} // namespace piano
