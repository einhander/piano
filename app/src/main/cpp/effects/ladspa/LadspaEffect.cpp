#include "LadspaEffect.h"

#include <cmath>
#include <algorithm>
#include <cstring>
#include <fcntl.h>
#include <unistd.h>
#include <string>

namespace piano {
namespace ladspa {

namespace {
float inputControlDefault(const LADSPA_PortRangeHint& hint, double sampleRate) {
    const auto flags = hint.HintDescriptor;
    const bool hasMin = LADSPA_IS_HINT_BOUNDED_BELOW(flags);
    const bool hasMax = LADSPA_IS_HINT_BOUNDED_ABOVE(flags);
    const float scale = LADSPA_IS_HINT_SAMPLE_RATE(flags)
            ? static_cast<float>(sampleRate) : 1.0f;
    const float lo = hint.LowerBound * scale;
    const float hi = hint.UpperBound * scale;
    float value = 0.0f;
    switch (flags & LADSPA_HINT_DEFAULT_MASK) {
        case LADSPA_HINT_DEFAULT_MINIMUM: value = hasMin ? lo : 0.0f; break;
        case LADSPA_HINT_DEFAULT_LOW:
        case LADSPA_HINT_DEFAULT_MIDDLE:
        case LADSPA_HINT_DEFAULT_HIGH:
        case LADSPA_HINT_DEFAULT_MAXIMUM: {
            if (!hasMin || !hasMax) break;
            const float t = (flags & LADSPA_HINT_DEFAULT_MASK) == LADSPA_HINT_DEFAULT_LOW ? 0.25f
                    : (flags & LADSPA_HINT_DEFAULT_MASK) == LADSPA_HINT_DEFAULT_MIDDLE ? 0.5f
                    : (flags & LADSPA_HINT_DEFAULT_MASK) == LADSPA_HINT_DEFAULT_HIGH ? 0.75f : 1.0f;
            if (t == 1.0f) value = hi;
            else if (LADSPA_IS_HINT_LOGARITHMIC(flags) && lo > 0.0f && hi > 0.0f)
                value = std::exp(std::log(lo) * (1.0f - t) + std::log(hi) * t);
            else value = lo * (1.0f - t) + hi * t;
            break;
        }
        case LADSPA_HINT_DEFAULT_0: value = 0.0f; break;
        case LADSPA_HINT_DEFAULT_1: value = 1.0f; break;
        case LADSPA_HINT_DEFAULT_100: value = 100.0f; break;
        case LADSPA_HINT_DEFAULT_440: value = 440.0f; break;
        // DEFAULT_NONE (and unknown encodings) supplies no suggested value:
        // start at host-neutral zero, then honor any declared bounds below.
        default: value = 0.0f; break;
    }
    if (LADSPA_IS_HINT_TOGGLED(flags)) value = value > 0.0f ? 1.0f : 0.0f;
    if (LADSPA_IS_HINT_INTEGER(flags)) value = std::round(value);
    if (hasMin) value = std::max(value, lo);
    if (hasMax) value = std::min(value, hi);
    return value;
}
}

// Write a one-line marker to <filesDir>/lsp_prepare_marker.log so that when
// the LSP instantiate/connect_port/activate call aborts the process, the
// crash handler (or next launch) can read which call was in progress. The
// file is opened/flushed synchronously (worker thread, not audio thread).
// The LSP-side instantiate() also writes to this same file with finer-grained
// sub-step markers; whichever writes last wins (O_TRUNC).
static void writePrepareMarker(const char* line) {
    int fd = ::open("/data/data/com.piano.sequencer/files/lsp_prepare_marker.log",
                    O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (fd < 0) return;
    ::write(fd, line, strlen(line));
    ::write(fd, "\n", 1);
    ::close(fd);
}

LadspaEffect::LadspaEffect(int slot, const LADSPA_Descriptor* descriptor)
    : mSlot(slot), mDescriptor(descriptor), mBinding(lsp::bindingForSlot(slot)) {
}

LadspaEffect::~LadspaEffect() {
    cleanupInstance();
}

void LadspaEffect::cleanupInstance() {
    if (mDescriptor && mHandle) {
        if (mActivated && mDescriptor->deactivate) {
            mDescriptor->deactivate(mHandle);
        }
        if (mDescriptor->cleanup) {
            mDescriptor->cleanup(mHandle);
        }
        mHandle = nullptr;
    }
    mActivated = false;
    mAvailable = false;
    mPrepared = false;
}

bool LadspaEffect::prepare(double sampleRate, int maxFrames) {
    // Re-prepare: tear down any existing instance first (non-audio thread).
    cleanupInstance();

    if (!mDescriptor || !mBinding || !mDescriptor->instantiate) {
        return false;
    }
    if (sampleRate <= 0.0 || maxFrames <= 0) {
        return false;
    }

    mSampleRate = sampleRate;
    mMaxFrames = maxFrames;
    mPortCount = mDescriptor->PortCount;
    if (mPortCount == 0) {
        return false;
    }

    // Allocate control storage and initialize LADSPA input controls from hints.
    mPortValues = std::make_unique<float[]>(mPortCount);
    std::memset(mPortValues.get(), 0, mPortCount * sizeof(float));
    if (mDescriptor->PortDescriptors && mDescriptor->PortRangeHints) {
        for (unsigned long p = 0; p < mPortCount; ++p) {
            if (LADSPA_IS_PORT_CONTROL(mDescriptor->PortDescriptors[p]) &&
                LADSPA_IS_PORT_INPUT(mDescriptor->PortDescriptors[p])) {
                mPortValues[p] = inputControlDefault(mDescriptor->PortRangeHints[p], sampleRate);
            }
        }
    }

    // Instantiate at the actual sample rate. Log plugin identity + sample rate
    // for diagnostics — the LSP-side instantiate() also writes sub-step markers
    // to the same file (lsp_prepare_marker.log), but only after this call
    // enters the LSP code. This marker survives if the crash happens before
    // the LSP marker is written.
    {
        char buf[256];
        snprintf(buf, sizeof(buf),
                 "slot=%d phase=instantiate sr=%.0f label=%s",
                 mSlot, sampleRate,
                 mDescriptor->Label ? mDescriptor->Label : "?");
        writePrepareMarker(buf);
    }
    mHandle = mDescriptor->instantiate(mDescriptor, static_cast<unsigned long>(sampleRate));
    if (!mHandle) {
        // In diagnostic mode (LSP_ANDROID_INSTANTIATE_DIAGNOSTIC), instantiate()
        // returns nullptr after a failed wrapper->init() WITHOUT cleaning up
        // the partially-initialized objects. This is expected — the app should
        // continue without this effect, not crash. The LSP marker file has the
        // last sub-step and wrapper->init() return code.
        return false;
    }

    // Connect the audio ports to dedicated slots in mPortValues. The audio
    // thread will NOT use these slots for audio data — instead it connects the
    // in/out ports to the caller's planar buffers per run() (LADSPA allows
    // re-connecting ports between run() calls). We keep dedicated pointers for
    // clarity, but connect_port is called every process() (cheap: pointer
    // store, no alloc).
    mInL  = mPortValues.get() + mBinding->audioInL;
    mInR  = mPortValues.get() + mBinding->audioInR;
    mOutL = mPortValues.get() + mBinding->audioOutL;
    mOutR = mPortValues.get() + mBinding->audioOutR;

    // Connect EVERY port (control + audio) to its mPortValues slot once, here.
    // process() later re-connects only the 4 audio ports to the caller's
    // buffers; the control ports stay pointing at mPortValues, which is what
    // applyParameters() writes and the plugin reads in run(). Without this,
    // control ports are unconnected and the plugin reads garbage.
    {
        char buf[128];
        snprintf(buf, sizeof(buf), "slot=%d phase=connect_port", mSlot);
        writePrepareMarker(buf);
    }
    for (unsigned long p = 0; p < mPortCount; ++p) {
        mDescriptor->connect_port(mHandle, p, &mPortValues[p]);
    }

    // Build the atomic parameter table for this slot from the stable table.
    int count = 0;
    const lsp::ParamPort* table = lsp::paramTable(mSlot, count);
    mParams = std::make_unique<AtomicParam[]>(count);
    mParamCount = count;
    for (int i = 0; i < count; ++i) {
        mParams[i].paramId = table[i].paramId;
        mParams[i].port = table[i].port;
        mParams[i].value.store(table[i].def);
        mParams[i].logarithmic = table[i].logarithmic;
    }

    // Apply defaults into the port storage immediately so the plugin starts
    // from a known state.
    for (int i = 0; i < mParamCount; ++i) {
        const AtomicParam& p = mParams[i];
        if (p.port >= 0 && static_cast<unsigned long>(p.port) < mPortCount) {
            mPortValues[p.port] = p.value.load(std::memory_order_relaxed);
        }
    }

    // Activate (if supported). LADSPA activate() is non-realtime; called once
    // before the first run().
    {
        char buf[128];
        snprintf(buf, sizeof(buf), "slot=%d phase=activate", mSlot);
        writePrepareMarker(buf);
    }
    if (mDescriptor->activate) {
        mDescriptor->activate(mHandle);
        mActivated = true;
    }

    // Clear the marker — if we reach here, prepare() succeeded.
    writePrepareMarker("done");
    mLatencyPort = mBinding->latencyPort;
    mLatencyFrames = 0;

    mPrepared = true;
    mAvailable = true;
    return true;
}

void LadspaEffect::applyParameters() noexcept {
    // Copy the atomic pending values into the LADSPA control-port storage.
    // Plain float writes — realtime-safe.
    for (int i = 0; i < mParamCount; ++i) {
        const AtomicParam& p = mParams[i];
        if (p.port >= 0 && static_cast<unsigned long>(p.port) < mPortCount) {
            mPortValues[p.port] = p.value.load(std::memory_order_relaxed);
        }
    }
}

void LadspaEffect::process(float* left, float* right, int numFrames) noexcept {
    if (!mAvailable || !mHandle || !mDescriptor || numFrames <= 0) {
        return;
    }

    // Hard bypass: leave the buffers untouched.
    if (mBypassed.load(std::memory_order_relaxed)) {
        return;
    }

    // Clamp to the prepared max (matches the engine's safeFrames contract).
    if (numFrames > mMaxFrames) {
        numFrames = mMaxFrames;
    }

    // Push pending parameter values into the control ports.
    applyParameters();

    // Connect the audio ports to the caller's planar buffers for this run.
    // connect_port is a pointer store — realtime-safe.
    mDescriptor->connect_port(mHandle, static_cast<unsigned long>(mBinding->audioInL),  left);
    mDescriptor->connect_port(mHandle, static_cast<unsigned long>(mBinding->audioInR),  right);
    mDescriptor->connect_port(mHandle, static_cast<unsigned long>(mBinding->audioOutL), left);
    mDescriptor->connect_port(mHandle, static_cast<unsigned long>(mBinding->audioOutR), right);

    // run() — realtime DSP. The plugin reads control ports from mPortValues
    // (set above) and the audio ports (connected above).
    mDescriptor->run(mHandle, static_cast<unsigned long>(numFrames));

    // Read latency (CTL_OUT) once it is stable. This is a read of a float the
    // plugin wrote during run(); cheap and safe.
    if (mLatencyPort >= 0 && static_cast<unsigned long>(mLatencyPort) < mPortCount) {
        float lat = mPortValues[mLatencyPort];
        if (std::isfinite(lat) && lat >= 0.0f) {
            mLatencyFrames = static_cast<int>(lat);
        }
    }
}

void LadspaEffect::setParameter(uint32_t parameterId, float value) noexcept {
    for (int i = 0; i < mParamCount; ++i) {
        if (mParams[i].paramId == parameterId) {
            mParams[i].value.store(value, std::memory_order_relaxed);
            return;
        }
    }
}

float LadspaEffect::getParameter(uint32_t parameterId) const noexcept {
    for (int i = 0; i < mParamCount; ++i) {
        if (mParams[i].paramId == parameterId) {
            return mParams[i].value.load(std::memory_order_relaxed);
        }
    }
    return 0.0f;
}

void LadspaEffect::setBypassed(bool bypassed) noexcept {
    mBypassed.store(bypassed, std::memory_order_relaxed);
}

bool LadspaEffect::isBypassed() const noexcept {
    return mBypassed.load(std::memory_order_relaxed);
}

const char* LadspaEffect::stableId() const noexcept {
    return mBinding ? mBinding->stableId : "";
}

bool LadspaEffect::isAvailable() const noexcept {
    return mAvailable;
}

int LadspaEffect::getLatencyFrames() const noexcept {
    return mLatencyFrames;
}

} // namespace ladspa
} // namespace piano
