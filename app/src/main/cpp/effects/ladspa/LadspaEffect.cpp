#include "LadspaEffect.h"

#include <cmath>
#include <cstring>
#include <fcntl.h>
#include <unistd.h>
#include <string>

namespace piano {
namespace ladspa {

// Write a one-line marker to <filesDir>/lsp_prepare_marker.log so that when
// the LSP instantiate/connect_port/activate call aborts the process, the
// crash handler (or next launch) can read which call was in progress. The
// file is opened/flushed synchronously (worker thread, not audio thread).
static void writePrepareMarker(const char* slot, const char* phase) {
    int fd = ::open("/data/data/com.piano.sequencer/files/lsp_prepare_marker.log",
                    O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (fd < 0) return;
    char buf[128];
    int len = snprintf(buf, sizeof(buf), "slot=%s phase=%s\n", slot, phase);
    ::write(fd, buf, static_cast<size_t>(len));
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

    // Allocate the control-port storage (one float per port). Owned for the
    // lifetime of the instance; zeroed so all control ports start at 0.
    mPortValues = std::make_unique<float[]>(mPortCount);
    std::memset(mPortValues.get(), 0, mPortCount * sizeof(float));

    // Instantiate at the actual sample rate.
    {
        char slotStr[16];
        snprintf(slotStr, sizeof(slotStr), "%d", mSlot);
        writePrepareMarker(slotStr, "instantiate");
    }
    mHandle = mDescriptor->instantiate(mDescriptor, static_cast<unsigned long>(sampleRate));
    if (!mHandle) {
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
        char slotStr[16];
        snprintf(slotStr, sizeof(slotStr), "%d", mSlot);
        writePrepareMarker(slotStr, "connect_port");
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
        char slotStr[16];
        snprintf(slotStr, sizeof(slotStr), "%d", mSlot);
        writePrepareMarker(slotStr, "activate");
    }
    if (mDescriptor->activate) {
        mDescriptor->activate(mHandle);
        mActivated = true;
    }

    // Clear the marker — if we reach here, prepare() succeeded.
    writePrepareMarker("", "done");
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
