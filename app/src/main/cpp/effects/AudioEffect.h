#pragma once

#include <cstdint>

namespace piano {

// Metadata for a single effect parameter, exposed to the control/UI layer.
// LADSPA port indexes are NOT exposed beyond the adapter; callers use stable
// parameter ids (see LspEffectIds.h).
struct EffectParameterDescriptor {
    uint32_t id;            // stable parameter id (Piano-owned)
    const char* stableName; // stable key, e.g. "threshold"
    const char* displayName;

    float minValue;
    float maxValue;
    float defaultValue;

    bool logarithmic;
    bool integer;
    bool toggled;
};

// Abstract realtime-safe audio effect.
//
// Contract:
//  - prepare() allocates/instantiates everything (worker/control thread).
//  - process() is called on the audio thread and must be realtime-safe:
//    no alloc/free, no locks, no I/O, no JNI. It operates on planar stereo
//    (separate left/right float arrays, numFrames samples each).
//  - setParameter()/setBypassed() are atomic and may be called from any
//    thread; the audio thread reads the values at the top of process().
//  - activate()/deactivate() and destruction happen off the audio thread.
class AudioEffect {
public:
    virtual ~AudioEffect() = default;

    // One-time setup at a given sample rate and max block size. Returns false
    // on failure (the chain will then bypass this effect). Idempotent: a
    // second prepare() at a new rate re-instantiates the plugin.
    virtual bool prepare(double sampleRate, int maxFrames) = 0;

    // Realtime DSP on planar stereo buffers (in-place allowed).
    virtual void process(float* left, float* right, int numFrames) noexcept = 0;

    // Atomic parameter set (control/UI thread). parameterId is a stable
    // Piano-owned id. Unknown ids are ignored.
    virtual void setParameter(uint32_t parameterId, float value) noexcept = 0;
    virtual float getParameter(uint32_t parameterId) const noexcept = 0;

    // Bypass: when true, process() must leave the buffers untouched (hard
    // bypass). Atomic.
    virtual void setBypassed(bool bypassed) noexcept = 0;
    virtual bool isBypassed() const noexcept = 0;

    // Human-readable stable id, e.g. "lsp.compressor".
    virtual const char* stableId() const noexcept = 0;

    // Whether prepare() succeeded and the effect is usable.
    virtual bool isAvailable() const noexcept = 0;

    // Reporting latency (in frames) for chain delay compensation. 0 if the
    // effect reports none. Read off the audio thread.
    virtual int getLatencyFrames() const noexcept = 0;
};

} // namespace piano
