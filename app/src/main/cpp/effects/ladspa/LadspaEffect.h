#pragma once

#include "AudioEffect.h"
#include "ladspa/ladspa.h"
#include "lsp/LspEffectIds.h"

#include <atomic>
#include <memory>

namespace piano {
namespace ladspa {

// Adapter that wraps one LADSPA plugin instance as a piano::AudioEffect.
//
// Lifecycle (per the LADSPA spec + plan Phase 4):
//   prepare()   worker/control thread — instantiate, connect ports, activate
//   process()   audio thread          — run() only
//   ~LadspaEffect / deactivate        non-audio thread
//
// Realtime safety: prepare() allocates the control-port storage and the
// scratch port array once; process() only writes already-allocated control
// port floats and calls the plugin's run(). No alloc/lock/I/O in process().
class LadspaEffect : public AudioEffect {
public:
    // slot selects the binding (EQ/Comp/Limiter). The descriptor is looked up
    // from the LadspaRegistry by the factory before construction; if nullptr,
    // the effect is unavailable.
    LadspaEffect(int slot, const LADSPA_Descriptor* descriptor);
    ~LadspaEffect() override;

    bool prepare(double sampleRate, int maxFrames) override;
    void process(float* left, float* right, int numFrames) noexcept override;

    void setParameter(uint32_t parameterId, float value) noexcept override;
    float getParameter(uint32_t parameterId) const noexcept override;

    void setBypassed(bool bypassed) noexcept override;
    bool isBypassed() const noexcept override;

    const char* stableId() const noexcept override;
    bool isAvailable() const noexcept override;
    int getLatencyFrames() const noexcept override;

    LadspaEffect(const LadspaEffect&) = delete;
    LadspaEffect& operator=(const LadspaEffect&) = delete;

private:
    // Apply the current atomic parameter values into the LADSPA control-port
    // storage, immediately before run(). Realtime-safe (plain float writes).
    void applyParameters() noexcept;

    void cleanupInstance();

    int mSlot;
    const LADSPA_Descriptor* mDescriptor;  // immutable after construction
    LADSPA_Handle mHandle = nullptr;
    bool mActivated = false;
    bool mAvailable = false;
    bool mPrepared = false;

    double mSampleRate = 0.0;
    int mMaxFrames = 0;
    int mLatencyPort = -1;
    int mLatencyFrames = 0;

    // The binding for this slot (audio port indexes, bypass/latency ports).
    const lsp::LadspaBinding* mBinding;

    // Control-port storage: one float per LADSPA port (PortCount entries).
    // Allocated once in prepare(); the audio thread writes into the same
    // floats before run() and the plugin reads them. No reallocation in
    // process().
    std::unique_ptr<float[]> mPortValues;
    unsigned long mPortCount = 0;

    // Cached pointers into mPortValues for the audio ports (set in prepare()).
    float* mInL = nullptr;
    float* mInR = nullptr;
    float* mOutL = nullptr;
    float* mOutR = nullptr;

    // Pending parameter values, written atomically from any thread and read
    // at the top of process(). A fixed array (sized once in prepare()) —
    // std::atomic is non-copyable/non-movable, so a std::vector cannot hold
    // these directly without indirection; a fixed array avoids that and any
    // reallocation in the audio path.
    struct AtomicParam {
        uint32_t paramId;
        int port;
        std::atomic<float> value;
        bool logarithmic;
    };
    std::unique_ptr<AtomicParam[]> mParams;  // fixed, sized in prepare()
    int mParamCount = 0;

    std::atomic<bool> mBypassed{true};  // default bypassed (safe)
};

} // namespace ladspa
} // namespace piano
