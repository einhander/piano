#include "GuitarRigManager.h"

#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <thread>

namespace piano {
namespace guitar {

namespace {

std::array<float, kParamCount> rigDefaultParams() {
    int count = 0;
    const EffectParameterDescriptor* d = guitarRigParamDescriptors(count);
    std::array<float, kParamCount> out{};
    for (int i = 0; i < count; ++i)
        out[static_cast<uint32_t>(i)] = d[i].defaultValue;
    return out;
}

// Blocking control-flag lock (worker-thread paths only).
struct BusyLock {
    std::atomic_flag& flag;
    explicit BusyLock(std::atomic_flag& f) : flag(f) {
        while (flag.test_and_set(std::memory_order_acquire)) std::this_thread::yield();
    }
    ~BusyLock() { flag.clear(std::memory_order_release); }
};

} // namespace

GuitarRigManager::GuitarRigManager() {
    const std::array<float, kParamCount> defaults = rigDefaultParams();
    for (int c = 0; c < kChannels; ++c) {
        mPublished[c].store(nullptr, std::memory_order_relaxed);
        mRackEnabled[c].store(false, std::memory_order_relaxed);
        mEffectEnabled[c].store(false, std::memory_order_relaxed);
        mErrorState[c].store(Ok, std::memory_order_relaxed);
        for (int i = 0; i < kParamCount; ++i)
            mDesired[c][i].store(defaults[i], std::memory_order_relaxed);
    }
    mLeft.fill(nullptr);
    mRight.fill(nullptr);
}

GuitarRigManager::~GuitarRigManager() {
    // Owner must stop/quiesce audio before destruction (same rule as
    // InstrumentEffectManager: rigs are destroyed only off the audio thread).
    for (int c = 0; c < kChannels; ++c) {
        std::free(mLeft[c]);
        std::free(mRight[c]);
    }
}

bool GuitarRigManager::prepare(double sampleRate, int maxFrames) {
    if (sampleRate <= 0.0 || !std::isfinite(sampleRate) || maxFrames <= 0) return false;
    if (mPrepared) return sampleRate == mSampleRate && maxFrames == mMaxFrames;
    std::array<float*, kChannels> left{}, right{};
    for (int c = 0; c < kChannels; ++c) {
        left[c] = static_cast<float*>(std::malloc(sizeof(float) * static_cast<size_t>(maxFrames)));
        right[c] = static_cast<float*>(std::malloc(sizeof(float) * static_cast<size_t>(maxFrames)));
        if (!left[c] || !right[c]) {
            for (int i = 0; i <= c; ++i) { std::free(left[i]); std::free(right[i]); }
            return false;
        }
    }
    mLeft = left;
    mRight = right;
    mSampleRate = sampleRate;
    mMaxFrames = maxFrames;
    mPrepared = true;
    return true;
}

bool GuitarRigManager::reprepareQuiescent(double sampleRate, int maxFrames) {
    if (sampleRate <= 0.0 || !std::isfinite(sampleRate) || maxFrames <= 0) return false;
    std::lock_guard<std::mutex> lifetimeLock(mLifetimeMutex);
    BusyLock busy(mControlBusy);
    // Stage everything fallible BEFORE mutating published state.
    std::array<float*, kChannels> left{}, right{};
    for (int c = 0; c < kChannels; ++c) {
        left[c] = static_cast<float*>(std::malloc(sizeof(float) * static_cast<size_t>(maxFrames)));
        right[c] = static_cast<float*>(std::malloc(sizeof(float) * static_cast<size_t>(maxFrames)));
        if (!left[c] || !right[c]) {
            for (int i = 0; i < kChannels; ++i) { std::free(left[i]); std::free(right[i]); }
            return false;
        }
    }
    std::array<std::unique_ptr<GuitarRigEffect>, kChannels> staged{};
    std::array<bool, kChannels> failed{};
    bool anyFailed = false;
    for (int c = 0; c < kChannels; ++c) {
        if (!mEffects[c]) continue; // channel never had a rig: nothing to rebuild
        auto candidate = std::make_unique<GuitarRigEffect>();
        for (int i = 0; i < kParamCount; ++i)
            candidate->setParameter(static_cast<uint32_t>(i), mDesired[c][i].load(std::memory_order_relaxed));
        if (!candidate->prepare(sampleRate, maxFrames) || !candidate->isAvailable()) {
            staged[c].reset();
            failed[c] = true;
            anyFailed = true;
            continue;
        }
        staged[c] = std::move(candidate);
    }
    // Commit: release old rigs + scratch, install the staged set. Failed
    // channels end up released (dry) with EngineInitFailed.
    for (int c = 0; c < kChannels; ++c) {
        mPublished[c].store(nullptr, std::memory_order_release);
        mEffects[c].reset();
        std::free(mLeft[c]);
        std::free(mRight[c]);
    }
    mLeft = left;
    mRight = right;
    for (int c = 0; c < kChannels; ++c) {
        mEffects[c] = std::move(staged[c]);
        if (mEffects[c]) {
            mPublished[c].store(mEffects[c].get(), std::memory_order_release);
            mErrorState[c].store(Ok, std::memory_order_release);
        } else if (failed[c]) {
            mErrorState[c].store(EngineInitFailed, std::memory_order_release);
        }
    }
    mSampleRate = sampleRate;
    mMaxFrames = maxFrames;
    mPrepared = true;
    return !anyFailed;
}

bool GuitarRigManager::instantiate(int channel) {
    if (channel < 0 || channel >= kChannels || !mPrepared) return false;
    if (mControlBusy.test_and_set(std::memory_order_acquire)) return false;
    struct Unlock { std::atomic_flag& f; ~Unlock() { f.clear(std::memory_order_release); } } unlock{mControlBusy};
    auto& effect = mEffects[channel];
    if (effect) {
        mPublished[channel].store(effect.get(), std::memory_order_release);
        return effect->isAvailable();
    }
    auto candidate = std::make_unique<GuitarRigEffect>();
    // Pending-before-instantiate honored: the whole desired row is applied
    // BEFORE prepare(), so prepare configures the model/cab from the stored
    // values (a fresh engine is not "live", so no rejection here).
    for (int i = 0; i < kParamCount; ++i)
        candidate->setParameter(static_cast<uint32_t>(i), mDesired[channel][i].load(std::memory_order_relaxed));
    if (!candidate->prepare(mSampleRate, mMaxFrames) || !candidate->isAvailable()) {
        mErrorState[channel].store(EngineInitFailed, std::memory_order_release);
        return false;
    }
    effect = std::move(candidate);
    mPublished[channel].store(effect.get(), std::memory_order_release);
    mErrorState[channel].store(Ok, std::memory_order_release);
    return true;
}

bool GuitarRigManager::setEffectEnabledChecked(int channel, bool enabled) {
    if (channel < 0 || channel >= kChannels) return false;
    if (enabled && !instantiate(channel)) return false;
    setEffectEnabled(channel, enabled);
    return true;
}

void GuitarRigManager::setEffectEnabled(int channel, bool enabled) noexcept {
    if (channel < 0 || channel >= kChannels) return;
    mEffectEnabled[channel].store(enabled, std::memory_order_release);
}

bool GuitarRigManager::effectEnabled(int channel) const noexcept {
    return channel >= 0 && channel < kChannels &&
           mEffectEnabled[channel].load(std::memory_order_acquire);
}

void GuitarRigManager::setRackEnabled(int channel, bool enabled) noexcept {
    if (channel < 0 || channel >= kChannels) return;
    mRackEnabled[channel].store(enabled, std::memory_order_release);
}

bool GuitarRigManager::rackEnabled(int channel) const noexcept {
    return channel >= 0 && channel < kChannels &&
           mRackEnabled[channel].load(std::memory_order_acquire);
}

bool GuitarRigManager::setParameterChecked(int channel, uint32_t parameterId, float value) noexcept {
    if (channel < 0 || channel >= kChannels || parameterId >= kParamCount) return false;
    float v = value;
    if (!guitarRigClampParam(parameterId, v)) return false;
    mDesired[channel][parameterId].store(v, std::memory_order_release);
    GuitarRigEffect* effect = mPublished[channel].load(std::memory_order_acquire);
    if (effect) {
        effect->setParameter(parameterId, v);
        // Interim live policy: model/cab are a reconfigure, not a parameter
        // change — reported rejected while the rig is live+published; the
        // stored desired value applies at instantiate/prepare/quiescence.
        if (parameterId == kParamAmpModel || parameterId == kParamCabType) return false;
    }
    return true;
}

bool GuitarRigManager::getParameterChecked(int channel, uint32_t parameterId, float& value) const noexcept {
    if (channel < 0 || channel >= kChannels || parameterId >= kParamCount) return false;
    std::lock_guard<std::mutex> lifetimeLock(mLifetimeMutex);
    const GuitarRigEffect* effect = mPublished[channel].load(std::memory_order_acquire);
    value = effect ? effect->getParameter(parameterId)
                   : mDesired[channel][parameterId].load(std::memory_order_relaxed);
    return true;
}

int GuitarRigManager::paramCount() const noexcept {
    int count = 0;
    guitarRigParamDescriptors(count);
    return count;
}

bool GuitarRigManager::paramInfo(int index, uint32_t& id, float& minValue, float& maxValue,
                                 float& defaultValue, bool& logarithmic, bool& integer,
                                 bool& toggled) const noexcept {
    int count = 0;
    const EffectParameterDescriptor* d = guitarRigParamDescriptors(count);
    if (!d || index < 0 || index >= count) return false;
    id = d[index].id;
    minValue = d[index].minValue;
    maxValue = d[index].maxValue;
    defaultValue = d[index].defaultValue;
    logarithmic = d[index].logarithmic;
    integer = d[index].integer;
    toggled = d[index].toggled;
    return true;
}

const char* GuitarRigManager::paramName(int index) const noexcept {
    int count = 0;
    const EffectParameterDescriptor* d = guitarRigParamDescriptors(count);
    if (!d || index < 0 || index >= count) return "";
    return d[index].displayName;
}

bool GuitarRigManager::isAvailable(int channel) const noexcept {
    if (channel < 0 || channel >= kChannels) return false;
    std::lock_guard<std::mutex> lifetimeLock(mLifetimeMutex);
    const GuitarRigEffect* effect = mPublished[channel].load(std::memory_order_acquire);
    return effect && effect->isAvailable();
}

int GuitarRigManager::errorState(int channel) const noexcept {
    if (channel < 0 || channel >= kChannels) return AllocationFailed;
    return mErrorState[channel].load(std::memory_order_acquire);
}

bool GuitarRigManager::hasPendingConfig(int channel) const noexcept {
    if (channel < 0 || channel >= kChannels) return false;
    std::lock_guard<std::mutex> lifetimeLock(mLifetimeMutex);
    const GuitarRigEffect* effect = mPublished[channel].load(std::memory_order_acquire);
    return effect && effect->hasPendingConfig();
}

bool GuitarRigManager::applyPendingConfig(int channel) {
    if (channel < 0 || channel >= kChannels) return false;
    std::lock_guard<std::mutex> lifetimeLock(mLifetimeMutex);
    GuitarRigEffect* effect = mPublished[channel].load(std::memory_order_acquire);
    if (!effect) return false;
    const bool ok = effect->applyConfigNow();
    mErrorState[channel].store(ok ? Ok : EngineInitFailed, std::memory_order_release);
    return ok;
}

int GuitarRigManager::effectLatencyFrames(int channel) const noexcept {
    if (channel < 0 || channel >= kChannels) return 0;
    std::lock_guard<std::mutex> lifetimeLock(mLifetimeMutex);
    const GuitarRigEffect* effect = mPublished[channel].load(std::memory_order_acquire);
    return effect && effect->isAvailable() ? effect->getLatencyFrames() : 0;
}

void GuitarRigManager::process(float* const buses[kChannels], int frames) noexcept {
    if (!buses || frames <= 0 || !mPrepared || frames > mMaxFrames) return;
    for (int channel = 0; channel < kChannels; ++channel) {
        float* bus = buses[channel];
        if (!bus) continue;
        GuitarRigEffect* effect = mPublished[channel].load(std::memory_order_acquire);
        if (!effect) continue;
        const bool gate = mRackEnabled[channel].load(std::memory_order_acquire) &&
                          mEffectEnabled[channel].load(std::memory_order_acquire);
        effect->setBypassed(!gate);
        // Fully dry (never enabled yet, or the ramp-to-dry finished): skip
        // everything, including the copy — the bus is NOT touched.
        if (!effect->wantsFrames()) continue;
        float* left = mLeft[channel];
        float* right = mRight[channel];
        if (!left || !right) continue;
        for (int f = 0; f < frames; ++f) {
            left[f] = bus[f * 2];
            right[f] = bus[f * 2 + 1];
        }
        effect->process(left, right, frames);
        for (int f = 0; f < frames; ++f) {
            bus[f * 2] = left[f];
            bus[f * 2 + 1] = right[f];
        }
    }
}

void GuitarRigManager::releaseChannelLocked(int channel) noexcept {
    mPublished[channel].store(nullptr, std::memory_order_release);
    mEffects[channel].reset();
    mRackEnabled[channel].store(false, std::memory_order_release);
    mEffectEnabled[channel].store(false, std::memory_order_release);
    mErrorState[channel].store(Ok, std::memory_order_release);
    const std::array<float, kParamCount> defaults = rigDefaultParams();
    for (int i = 0; i < kParamCount; ++i)
        mDesired[channel][i].store(defaults[i], std::memory_order_relaxed);
}

bool GuitarRigManager::resetChannelRange(int first, int count) noexcept {
    if (first < 0 || count < 0 || first > kChannels || count > kChannels - first) return false;
    std::lock_guard<std::mutex> lifetimeLock(mLifetimeMutex);
    BusyLock busy(mControlBusy);
    for (int c = first; c < first + count; ++c) releaseChannelLocked(c);
    return true;
}

} // namespace guitar
} // namespace piano
