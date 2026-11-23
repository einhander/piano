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
        mPending[c].store(nullptr, std::memory_order_relaxed);
        mRetired[c].store(nullptr, std::memory_order_relaxed);
        mSwapArmed[c].store(false, std::memory_order_relaxed);
        mSwapCount[c].store(0, std::memory_order_relaxed);
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
    // InstrumentEffectManager: rigs are destroyed only off the audio
    // thread). The quiescent drain (a) ADOPTS a committed-but-never-synced
    // swap — after a commit mEffects owns the RETIRED rig and mPublished
    // owns the new, un-owned one; syncAdopt moves the retired delete and
    // the new rig's ownership into mEffects so member destruction frees
    // both (a missing adopt here leaks the last swapped-in rig under ASan)
    // — and (b) destroys any un-taken PENDING / corrupt-state RETIRED.
    for (int c = 0; c < kChannels; ++c) {
        std::free(mLeft[c]);
        std::free(mRight[c]);
        drainChannelQuiescentLocked(c);
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
    // Drain all swap mailboxes FIRST (quiescent contract holds: this runs
    // inside the FluidSynth worker gate). RETIRED rigs are reclaimed
    // inline; an un-taken PENDING is destroyed — it is superseded by the
    // staged rebuild below, which builds from the same mDesired row and
    // therefore already carries the pending model/cab config (spec §10.4).
    for (int c = 0; c < kChannels; ++c) drainChannelQuiescentLocked(c);
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

// ── swap-protocol worker helpers ──────────────────────────────────────────
// Lifetime mutex held by the caller at every site below; where noted, the
// quiescent contract (audio callback not running) as well.

bool GuitarRigManager::syncAdoptLocked(int channel) {
    // Restore the invariant "mEffects[c] uniquely owns the CURRENT rig"
    // after the callback committed a swap: mEffects[c] then holds the
    // RETIRED object (it owned the previous CURRENT) and mPublished[c]
    // holds the un-owned new CURRENT. The two transfers (retire-out,
    // publish-in) were ONE pointer swap on the callback side, so deleting
    // via mEffects and re-adopting published moves ownership with exactly
    // one delete of the old rig.
    GuitarRigEffect* retired = mRetired[channel].load(std::memory_order_acquire);
    if (!retired) return false;
    if (!mEffects[channel] || mEffects[channel].get() != retired) {
        // CORRUPT (unreachable under the protocol): a retired rig the
        // owning cell does not own. NEVER delete a pointer we cannot prove
        // we exclusively own — leave it for the quiescent drain, which may
        // destroy it only after proving nothing else references it either.
        return false;
    }
    GuitarRigEffect* pub = mPublished[channel].load(std::memory_order_acquire);
    mEffects[channel].reset(pub);  // deletes `retired` (its only owner path)
    mRetired[channel].store(nullptr, std::memory_order_release);
    return true;
}

void GuitarRigManager::drainChannelQuiescentLocked(int channel) {
    // QUIESCENT CONTRACT REQUIRED (reprepare gate / shutdown / explicit
    // apply / reset): the callback is not running, so mailbox contents are
    // provably unreferenced by audio.
    syncAdoptLocked(channel);
    // Spec §10.4: a PENDING the callback never consumed is destroyed BY THE
    // WORKER here (superseded by the caller's rebuild / apply / teardown).
    GuitarRigEffect* pending = mPending[channel].exchange(nullptr, std::memory_order_acq_rel);
    if (pending) delete pending;
    GuitarRigEffect* retired = mRetired[channel].exchange(nullptr, std::memory_order_acq_rel);
    if (retired) {
        // Reachable only when syncAdopt rejected a corrupt state. Proof of
        // exclusivity under quiescence: the callback is not running and the
        // cell is cleared; `retired` is not CURRENT (the callback moved it
        // out of mPublished before retiring it) and mEffects[c] does not
        // own it (syncAdopt's equality check failed).
        if (retired != mPublished[channel].load(std::memory_order_acquire) &&
            (!mEffects[channel] || mEffects[channel].get() != retired))
            delete retired;
        else
            mRetired[channel].store(retired, std::memory_order_release);  // paranoid keep
    }
    mSwapArmed[channel].store(false, std::memory_order_release);
    if (GuitarRigEffect* pub = mPublished[channel].load(std::memory_order_acquire))
        pub->setRampSeconds(kDefaultRampSeconds);
    // The audio-thread SwapRuntime.phase self-heals on the next processed
    // block (abandoned-fade branch in process()).
}

bool GuitarRigManager::reclaimRetired(int channel) {
    if (channel < 0 || channel >= kChannels) return false;
    if (mControlBusy.test_and_set(std::memory_order_acquire)) return false;
    struct Unlock {
        std::atomic_flag& f;
        ~Unlock() { f.clear(std::memory_order_release); }
    } unlock{mControlBusy};
    std::lock_guard<std::mutex> lifetimeLock(mLifetimeMutex);
    return syncAdoptLocked(channel);
}

uint64_t GuitarRigManager::swapCount(int channel) const noexcept {
    if (channel < 0 || channel >= kChannels) return 0;
    return mSwapCount[channel].load(std::memory_order_acquire);
}

bool GuitarRigManager::swapArmed(int channel) const noexcept {
    if (channel < 0 || channel >= kChannels) return false;
    return mSwapArmed[channel].load(std::memory_order_acquire);
}

bool GuitarRigManager::hasPendingConfig(int channel) const noexcept {
    if (channel < 0 || channel >= kChannels) return false;
    // Swap in flight: an accepted-but-unconsumed PENDING, or a committed
    // swap whose RETIRED reclaim has not happened yet (armed clears at
    // commit, so also report retired-occupied-with-pending-cleared — the
    // busy test in requestRigSwap covers it authoritatively).
    if (mSwapArmed[channel].load(std::memory_order_acquire)) return true;
    if (mPending[channel].load(std::memory_order_acquire)) return true;
    return false;
}

bool GuitarRigManager::requestRigSwap(int channel) {
    if (channel < 0 || channel >= kChannels || !mPrepared) return false;
    if (mControlBusy.test_and_set(std::memory_order_acquire)) return false;  // control busy
    struct Unlock {
        std::atomic_flag& f;
        ~Unlock() { f.clear(std::memory_order_release); }
    } unlock{mControlBusy};
    std::lock_guard<std::mutex> lifetimeLock(mLifetimeMutex);
    // Serialize: reclaim a completed swap's retired rig (live-safe: the
    // callback parked it and never reads the RETIRED cell) BEFORE the busy
    // test, so a finished swap never wedges the channel.
    syncAdoptLocked(channel);
    if (mSwapArmed[channel].load(std::memory_order_acquire) ||
        mPending[channel].load(std::memory_order_acquire) != nullptr ||
        mRetired[channel].load(std::memory_order_acquire) != nullptr) {
        return false;  // busy: previous swap still in flight / unreclaimed
    }
    GuitarRigEffect* pub = mPublished[channel].load(std::memory_order_acquire);
    if (!pub) return true;  // nothing live: the desired row is honored at instantiate
    // Spec §10.2: copy the FULL desired config (incl. current live params)
    // and build a complete rig OFF the callback thread — both mono engines,
    // pedals pre-created bypassed, amp model + fresh synthetic IR via
    // prepare()'s applyConfigNow, every param value from mDesired.
    auto candidate = std::make_unique<GuitarRigEffect>();
    for (int i = 0; i < kParamCount; ++i)
        candidate->setParameter(static_cast<uint32_t>(i),
                                mDesired[channel][i].load(std::memory_order_relaxed));
    if (mFailNextBuild) {  // test seam: simulate a builder allocation failure
        mFailNextBuild = false;
        mErrorState[channel].store(AllocationFailed, std::memory_order_release);
        return false;  // CURRENT untouched
    }
    if (!candidate->prepare(mSampleRate, mMaxFrames) || !candidate->isAvailable()) {
        mErrorState[channel].store(EngineInitFailed, std::memory_order_release);
        return false;  // builder failure: CURRENT untouched, UI-visible error
    }
    // Arm FIRST (single producer of this cell), then hand over PENDING with
    // a single-slot CAS, NOT a plain store: the busy test above raced the
    // callback (which can take PENDING only at commit, but the worker's own
    // syncAdopt ran before that commit could park the retired rig), so the
    // slot may legitimately differ from the loaded state. A non-empty
    // mailbox at CAS time = busy. The candidate is still worker-owned here,
    // so destroy it and report busy — an unconsumed PENDING is NEVER
    // overwritten or lost (spec §10.2).
    mSwapArmed[channel].store(true, std::memory_order_relaxed);
    GuitarRigEffect* expected = nullptr;
    if (!mPending[channel].compare_exchange_strong(expected, candidate.get(),
                                                   std::memory_order_acq_rel,
                                                   std::memory_order_acquire)) {
        mSwapArmed[channel].store(false, std::memory_order_release);
        return false;  // busy: slot occupied (re-checked reclaim first)
    }
    candidate.release();  // ownership: worker until the callback takes it
    mErrorState[channel].store(Ok, std::memory_order_release);
    return true;
}

bool GuitarRigManager::requestRigConfig(int channel, int ampModel, int cabType) {
    if (channel < 0 || channel >= kChannels) return false;
    if (ampModel < 0 || ampModel > 2 || cabType < 0 || cabType > 3) return false;
    const float model = static_cast<float>(ampModel);
    const float cab = static_cast<float>(cabType);
    mDesired[channel][kParamAmpModel].store(model, std::memory_order_release);
    mDesired[channel][kParamCabType].store(cab, std::memory_order_release);
    if (GuitarRigEffect* pub = mPublished[channel].load(std::memory_order_acquire)) {
        pub->setParameter(kParamAmpModel, model);  // mirror only, not engine
        pub->setParameter(kParamCabType, cab);
    }
    return requestRigSwap(channel);
}

bool GuitarRigManager::applyPendingConfig(int channel) {
    if (channel < 0 || channel >= kChannels) return false;
    std::lock_guard<std::mutex> lifetimeLock(mLifetimeMutex);
    BusyLock busy(mControlBusy);
    if (!mPublished[channel].load(std::memory_order_acquire)) return false;
    // QUIESCENT contract (same gate as reprepareQuiescent): slow path of
    // the protocol, applied by hand.
    syncAdoptLocked(channel);
    bool ok;
    // Spec §10.4: with an un-taken PENDING, apply it to CURRENT directly —
    // destroy the old rig inline (mEffects is its only owner here) and
    // install the worker-built replacement.
    GuitarRigEffect* pending = mPending[channel].exchange(nullptr, std::memory_order_acq_rel);
    if (pending) {
        // The candidate snapshots mDesired at REQUEST time; a live param
        // may have moved while it sat unconsumed (mirror-only continuous
        // params never re-trigger a swap) — refresh the mirrors, then apply
        // the discrete engine config in place (quiescence makes this the
        // engine-side supersede the callback would otherwise fade through).
        for (int i = 0; i < kParamCount; ++i)
            pending->setParameter(static_cast<uint32_t>(i),
                                  mDesired[channel][i].load(std::memory_order_relaxed));
        mEffects[channel].reset(pending);
        mPublished[channel].store(pending, std::memory_order_release);
        ok = pending->applyConfigNow() && pending->isAvailable();
    } else {
        // Empty mailbox: reconcile a mirror-only model/cab state directly
        // (covers a builder failure that left the live rig unconfigured,
        // and the legacy quiescent-apply usage).
        ok = mEffects[channel] && mEffects[channel]->applyConfigNow();
    }
    GuitarRigEffect* retired = mRetired[channel].exchange(nullptr, std::memory_order_acq_rel);
    if (retired) {
        if (retired != mPublished[channel].load(std::memory_order_acquire) &&
            (!mEffects[channel] || mEffects[channel].get() != retired))
            delete retired;  // corrupt-state cleanup, quiescence proves exclusivity
        else
            mRetired[channel].store(retired, std::memory_order_release);
    }
    mSwapArmed[channel].store(false, std::memory_order_release);
    if (GuitarRigEffect* pub = mPublished[channel].load(std::memory_order_acquire))
        pub->setRampSeconds(kDefaultRampSeconds);
    mErrorState[channel].store(ok ? Ok : EngineInitFailed, std::memory_order_release);
    return ok;
}

void GuitarRigManager::drainAllQuiescent() {
    // Shutdown / sample-rate-gate path (NativeEngine calls this with the
    // audio callback detached). Mailboxes hold worker-owned objects; the
    // callback provably references none of them.
    std::lock_guard<std::mutex> lifetimeLock(mLifetimeMutex);
    BusyLock busy(mControlBusy);
    for (int c = 0; c < kChannels; ++c) drainChannelQuiescentLocked(c);
}

// ── lifecycle + control surface ───────────────────────────────────────────

bool GuitarRigManager::instantiate(int channel) {
    if (channel < 0 || channel >= kChannels || !mPrepared) return false;
    if (mControlBusy.test_and_set(std::memory_order_acquire)) return false;
    struct Unlock {
        std::atomic_flag& f;
        ~Unlock() { f.clear(std::memory_order_release); }
    } unlock{mControlBusy};
    std::lock_guard<std::mutex> lifetimeLock(mLifetimeMutex);
    syncAdoptLocked(channel);  // a completed swap may have changed CURRENT
    auto& effect = mEffects[channel];
    if (effect) {
        mPublished[channel].store(effect.get(), std::memory_order_release);
        return effect->isAvailable();
    }
    auto candidate = std::make_unique<GuitarRigEffect>();
    // Pending-before-instantiate honored: the whole desired row is applied
    // BEFORE prepare(), so prepare configures the model/cab from the stored
    // values (a fresh engine is not "live", so no swap is needed).
    for (int i = 0; i < kParamCount; ++i)
        candidate->setParameter(static_cast<uint32_t>(i),
                                mDesired[channel][i].load(std::memory_order_relaxed));
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
        // Mirror the desired value on the live rig (getParameterChecked
        // reports the DESIRED config, not the engine-confirmed one, until
        // a swap commits).
        effect->setParameter(parameterId, v);
        // Spec §10.6: the discrete model/cab params INITIATE a live swap.
        // true = accepted (lands on a processed block); false = busy or
        // builder failure (errorState set; old rig keeps running).
        if (parameterId == kParamAmpModel || parameterId == kParamCabType)
            return requestRigSwap(channel);
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

int GuitarRigManager::effectLatencyFrames(int channel) const noexcept {
    if (channel < 0 || channel >= kChannels) return 0;
    std::lock_guard<std::mutex> lifetimeLock(mLifetimeMutex);
    const GuitarRigEffect* effect = mPublished[channel].load(std::memory_order_acquire);
    return effect && effect->isAvailable() ? effect->getLatencyFrames() : 0;
}

// ── audio callback ─────────────────────────────────────────────────────────

void GuitarRigManager::commitSwapAudio(int channel) noexcept {
    // Single-slot RETIRED rule (spec §10.3): the callback stores into the
    // cell EXACTLY ONCE per swap and never overwrites. Occupancy here means
    // the worker has not reclaimed the previous swap — unreachable under the
    // busy rules (an accepted request implies retired was empty and only one
    // swap can be in flight); DEFER and retry on the next processed block
    // rather than dropping or overwriting the retired rig.
    if (mRetired[channel].load(std::memory_order_acquire) != nullptr) return;
    GuitarRigEffect* pending = mPending[channel].exchange(nullptr, std::memory_order_acq_rel);
    if (!pending) {
        // Worker drained (quiescent supersede) mid-fade: disarm + go Idle.
        mSwapArmed[channel].store(false, std::memory_order_release);
        mSwapRuntime[channel].phase = kPhaseIdle;
        return;
    }
    pending->setRampSeconds(kSwapRampSeconds);  // the fade-IN uses 10 ms too
    pending->startPriming(mSampleRate > 0.0
                              ? static_cast<int>(mSampleRate * kSwapPrimeSeconds + 0.5)
                              : 0);

    // THE swap: one atomic exchange moves PENDING into CURRENT and yields
    // the old CURRENT (which this thread just stopped processing, wet==0).
    GuitarRigEffect* old = mPublished[channel].exchange(pending, std::memory_order_acq_rel);
    if (!old) {
        // Defensive (unreachable: commits run only for published channels;
        // publication is cleared only under the quiescent contract). Put the
        // rig back if the slot is still empty; otherwise stash it in the
        // EMPTY retired slot so the worker's quiescent drain proves it
        // unowned and frees it — the callback never deletes, never loses.
        GuitarRigEffect* expected = nullptr;
        if (!mPending[channel].compare_exchange_strong(expected, pending,
                                                       std::memory_order_acq_rel,
                                                       std::memory_order_acquire)) {
            expected = nullptr;
            if (mRetired[channel].compare_exchange_strong(expected, pending,
                                                          std::memory_order_acq_rel,
                                                          std::memory_order_acquire)) {
                mSwapArmed[channel].store(false, std::memory_order_release);
                mSwapRuntime[channel].phase = kPhaseIdle;
            }
        }
        return;
    }
    mRetired[channel].store(old, std::memory_order_release);  // one store per swap
    mSwapCount[channel].fetch_add(1, std::memory_order_acq_rel);  // ACK (§10.5c)
    mSwapArmed[channel].store(false, std::memory_order_release);
    mSwapRuntime[channel].phase = kPhaseFadeIn;
}

void GuitarRigManager::process(float* const buses[kChannels], int frames) noexcept {
    if (!buses || frames <= 0 || !mPrepared || frames > mMaxFrames) return;
    for (int channel = 0; channel < kChannels; ++channel) {
        float* bus = buses[channel];
        if (!bus) continue;
        GuitarRigEffect* effect = mPublished[channel].load(std::memory_order_acquire);
        if (!effect) continue;

        // ── live-swap state machine — TOP of the channel's step, runs for
        //    every published channel regardless of the gate, so pending
        //    swaps land on a rack-off channel too (spec §10.7) ─────────────
        SwapRuntime& rt = mSwapRuntime[channel];
        const bool pendingSeen = mPending[channel].load(std::memory_order_acquire) != nullptr;
        if (pendingSeen) {
            if (rt.phase == kPhaseIdle) {
                if (!effect->wantsFrames()) {
                    // Rig fully dry / bypassed: the swap is inaudible —
                    // commit immediately, no fade needed.
                    commitSwapAudio(channel);
                } else {
                    // Fade-out: force the ramp target to 0 through the
                    // rig's own level-normalized crossfade (the old rig
                    // keeps processing at full wet-ness × ramp).
                    effect->setRampSeconds(kSwapRampSeconds);
                    effect->setBypassed(true);
                    rt.phase = kPhaseFadeOut;
                }
            }
            if (rt.phase == kPhaseFadeOut) {
                GuitarRigEffect* cur = mPublished[channel].load(std::memory_order_acquire);
                if (cur && cur->wetApplied() == 0.0f) commitSwapAudio(channel);
            }
        } else if (rt.phase != kPhaseIdle &&
                   !mSwapArmed[channel].load(std::memory_order_acquire)) {
            // Abandoned fade: a quiescent drain destroyed the PENDING this
            // fade was chasing while audio was stopped. Abandon; restore the
            // 5 ms convention; the gate drives bypassed again below.
            effect->setRampSeconds(kDefaultRampSeconds);
            rt.phase = kPhaseIdle;
        }
        // FADE_IN completion (if active) is evaluated below, AFTER the
        // re-load — a commit earlier in THIS call leaves the top-loaded
        // `effect` pointing at the just-RETIRED rig, which the worker may
        // legitimately be destroying; never touch a rig this callback has
        // handed over.
        // The commit above may have replaced the rig — refresh the pointer
        // BEFORE any further effect access (TSan-caught protocol bug: using
        // the old pointer after a commit is a use-after-retire).
        effect = mPublished[channel].load(std::memory_order_acquire);
        if (!effect) continue;

        const bool gate = mRackEnabled[channel].load(std::memory_order_acquire) &&
                          mEffectEnabled[channel].load(std::memory_order_acquire);
        // During FADE_OUT the protocol owns the bypass flag (forced dry);
        // while the committed rig is PRIMING (silent) it stays forced-dry
        // too (target 0 keeps wet at 0 so the priming window cannot leak);
        // otherwise the gate drives it as in phase D.
        if (rt.phase != kPhaseFadeOut && !effect->isPriming())
            effect->setBypassed(!gate);
        // FADE_IN completion: AFTER the gate re-drive, so a just-primed rig
        // (born bypassed, target would read 0) is not completed early — the
        // gate above already restored the target and the 10 ms ramp-in runs.
        if (rt.phase == kPhaseFadeIn && !effect->isPriming() &&
            effect->wetApplied() >= effect->wetTarget()) {
            effect->setRampSeconds(kDefaultRampSeconds);
            rt.phase = kPhaseIdle;
        }
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

// ── teardown paths (worker, quiesced) ─────────────────────────────────────

void GuitarRigManager::releaseChannelLocked(int channel) noexcept {
    mPublished[channel].store(nullptr, std::memory_order_release);
    mEffects[channel].reset();
    mRackEnabled[channel].store(false, std::memory_order_release);
    mEffectEnabled[channel].store(false, std::memory_order_release);
    mErrorState[channel].store(Ok, std::memory_order_release);
    mSwapArmed[channel].store(false, std::memory_order_release);
    const std::array<float, kParamCount> defaults = rigDefaultParams();
    for (int i = 0; i < kParamCount; ++i)
        mDesired[channel][i].store(defaults[i], std::memory_order_relaxed);
}

bool GuitarRigManager::resetChannelRange(int first, int count) noexcept {
    if (first < 0 || count < 0 || first > kChannels || count > kChannels - first) return false;
    std::lock_guard<std::mutex> lifetimeLock(mLifetimeMutex);
    BusyLock busy(mControlBusy);
    for (int c = first; c < first + count; ++c) {
        // Quiesced: mailboxes hold worker-owned rigs, free them BEFORE
        // releasing the channel so neither the effect nor a rig leaks.
        GuitarRigEffect* pending = mPending[c].exchange(nullptr, std::memory_order_acq_rel);
        if (pending) delete pending;
        GuitarRigEffect* retired = mRetired[c].exchange(nullptr, std::memory_order_acq_rel);
        if (retired && (!mEffects[c] || mEffects[c].get() != retired)) delete retired;
        releaseChannelLocked(c);
    }
    return true;
}

} // namespace guitar
} // namespace piano
