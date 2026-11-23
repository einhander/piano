#pragma once

#include "effects/guitar/GuitarRigEffect.h"
#include "synth/InstrumentChannelContract.h"

#include <array>
#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>

namespace piano {
namespace guitar {

// Per-channel guitar rig slots (mirrors InstrumentEffectManager's lifecycle
// rules, but a SEPARATE manager — the rig never joins the LSP slot tables).
//
// One mono-pair GuitarRigEffect per instrument channel, state for all
// kInstrumentChannelCount (128) channels, sized for the rig's 12 params —
// deliberately NOT mPending[128][9][512]: a plain mDesired[kChannels]
// [kParamCount] atomic row (defaults live, no per-set flag needed).
//
// ── LIVE AMP/CAB SWAP PROTOCOL (spec §10, docs/guitar-fx-port.md §Live rig
//    swap) ──────────────────────────────────────────────────────────────────
// fx_amp_set_model and fx_cab_generate_ir are NOT live-safe (see the effect
// header). Changing the amp model or the cabinet on a RUNNING rig therefore
// replaces the entire GuitarRigEffect object through a lock-free two-phase
// mailbox protocol. Per channel:
//
//   cell              writers                       readers            meaning
//   ───────────────  ────────────────────────────  ─────────────────  ─────────────
//   mPublished[c]    worker: instantiate/reprepare/  callback (load/   CURRENT — the
//   (CURRENT)          quiescent apply/reset, under  exchange at       rig the audio
//                      the quiescent contract or     commit)           callback
//                      control lock, NEVER while                      processes
//                      the callback may run
//   mPending[c]      worker builds + CAS-         callback peek-      PENDING — fully
//   (PENDING)        publishes (single-slot,      load (top of        built
//                    fails = busy, NEVER          step) +             replacement
//                    overwrites); callback        exchange ONLY at    rig, worker-
//                    takes it (exchange)          commit              owned until the
//                    ONLY at swap commit;                             callback takes
//                    worker takes it back                             it (quiescent
//                    ONLY under the                                   drain/apply)
//                    quiescent contract
//                    (§10.4 apply/drain)
//   mRetired[c]      callback stores EXACTLY       worker exchange     RETIRED — ex-
//   (RETIRED)        once per swap, only when      + delete + adopt    CURRENT, handed
//                    the slot is observed empty;   (sync/adopt)        back for worker
//                    worker takes it back ONLY                            reclaim; the
//                      under the quiescent                              callback never
//                      contract (defensive)                             reads it
//   mSwapArmed[c]    worker sets true BEFORE       all threads         swap in flight:
//                    publishing PENDING; callback                        busy → new
//                    clears at commit                                    requests
//                    (and worker at                                    rejected
//                    quiescent drain)
//   mSwapCount[c]    callback +1 at commit         worker (busy tests  COMMITTED swaps only —
//                                                  + status            see ACK honesty below
//                                                    reporting)
//   mSuperseded[c]   worker +1 when an accepted    status reporting    accepted swaps the
//                    PENDING is DESTROYED off-                        callback never committed:
//                    thread (quiescent drain /                        superseded by a staged
//                    staged rebuild, channel                        rebuild / torn down by
//                    reset)                                           reset (config accounting)
//
// Audio-thread state machine (process(), top of the channel's step, runs for
// EVERY published channel — gate open or not):
//
//   IDLE      + PENDING seen:
//               rig already fully dry (wantsFrames()==false) → commit now
//                 (no audible state to disturb; rack-off swaps land here)
//               else → setRampSeconds(10ms), force bypassed (wet ramps to 0),
//                      FADE_OUT
//   FADE_OUT: continue processing with CURRENT while its wet ramps to 0;
//             at wet==0 → if RETIRED slot is occupied DEFER (retry next
//             block — spec §10.3: the callback must never drop or overwrite
//             a retired rig; the protocol makes occupancy unreachable, the
//             retry is the documented safe fallback), else commit:
//             take PENDING (exchange), CURRENT = PENDING (exchange),
//             RETIRED = old (single release store), mSwapCount++, armed=false,
//             FADE_IN
//   FADE_IN:  process with the NEW rig. The rig is PRIMED first (silent):
//             a freshly prepared engine's zeroed internal state emits a
//             start-up transient on real signal, so process() runs the
//             full signal path while the manager keeps the rig force-dry
//             (setBypassed stays true) and the effect emits the dry
//             samples verbatim for ~4 ms (kSwapPrimeSeconds) — the
//             transient happens inaudibly. After the prime the gate drives
//             bypassed as usual and the wet ramps back in (10 ms); when
//             wetApplied reaches wetTarget → restore rampSeconds 5 ms,
//             IDLE. If PENDING appears again (accepted only after worker
//             reclaim, so RETIRED is empty), FADE_OUT.
//
//   IDLE      + armed==true && PENDING mailbox empty (quiescent worker drain
//             superseded an in-flight request while audio was stopped):
//             clear armed, restore ramp, stay IDLE (defensive reconciliation).
//
// Ownership (proof sketch — full text in docs + tests/guitar_rig_swap_test):
//   (a) NO USE-AFTER-FREE from the worker: the callback only ever reads the
//       pointer in mPublished (CURRENT). The worker deletes a rig only when
//       it is provably not CURRENT: RETIRED (the callback moved it out of
//       mPublished BEFORE storing it there — every worker delete site first
//       observes that mPublished[c] no longer equals the pointer) or an
//       un-taken PENDING removed under the quiescent contract (the callback
//       only takes PENDING inside process(), which quiescence excludes).
//   (b) NO DOUBLE-DELETE: mEffects[c] (worker unique_ptr) is the ONLY
//       owning cell; it owns CURRENT while the swap is not committed, and
//       RETIRED from commit until sync. syncAdoptLocked() deletes RETIRED
//       ONLY when it pointer-equals mEffects[c].get() (the same object, one
//       delete via the owning pointer) and then adopts the new CURRENT into
//       mEffects[c] — the two ownership transfers (retire-out, publish-in)
//       are one pointer swap, so at every instant every rig has exactly one
//       owner. The callback NEVER deletes; the worker NEVER deletes a
//       pointer read from mPublished alone.
//   (c) SWAP COMPLETION: the worker sees mSwapCount[c] advance at the commit
//       instant (release), the moment the callback stops touching the old
//       rig; FADE_IN then only touches the new CURRENT, which busy semantics
//       (armed until commit + PENDING slot) keep protected until the next
//       accepted swap retires it. Quiescent paths (reprepare/shutdown/
//       apply/drain) additionally require callback quiescence, so an
//       in-progress fade can never race a free.
//
// DISCRETE-CONFIG DEDUPE (m6, phase-G pre-requisite): MIDI-CC and UI re-send
// discrete model/cab values that often already equal the applied rig config;
// a full rebuild per unchanged CC write is waste with a fade attached.
// requestRigConfig() and setParameterChecked(model|cab) therefore compare
// the requested (ampModel, cabType) PAIR against mAppliedConfig — the config
// the CURRENT engine provably runs with (see member) — and when it matches
// AND no swap is in flight, return TRUE as a no-op without building a
// candidate (zero allocations). The desired row + mirror still update (they
// converge to the same values). A request made while a swap is in flight is
// NEVER deduped — it surfaces the honest busy false, so the UI contract
// (false = retry) cannot be masked. A builder failure leaves mAppliedConfig
// at the untouched old config, so the retry after a failure is never
// deduped away.
//
// ACK honesty (supersedes the earlier "monotonic ACK" claim): an accepted
// swap (requestRigSwap()/setParameterChecked(model|cab) == true) means the
// config WILL land — either as a COMMIT (mSwapCount++ on a processed block)
// or as a quiescent SUPERSEDE: reprepareQuiescent()/drainAllQuiescent()
// destroy the un-taken PENDING and rebuild from the same mDesired row (the
// staged rig already carries the pending config), resetChannelRange() tears
// the channel down entirely. mSwapCount counts COMMITTED swaps only; every
// destruction of an accepted-but-unconsumed PENDING bumps mSupersededCount
// instead, and the packed rigStatus() below exposes both facts. A rejected
// request never changes any counter and leaves CURRENT running untouched.
//
// ── LOCK-ORDER INVARIANT (audit EVERY lock-taking site against this) ─────
// Exactly ONE acquisition order is permitted in this class:
//
//     mLifetimeMutex  →  mControlBusy        (release in reverse)
//
// Control sites (instantiate, reclaimRetired, requestRigSwap ×2 guarded
// sections, rigStatus, mirrorToRig, the requestRigConfig/setParameterChecked
// mirror blocks) take both NON-BLOCKING (try_lock + test_and_set) and
// return false on contention — never block: these run on the UI thread, the
// MIDI-CC controller thread and the preset-restore path without
// marshalling (PlaybackService.kt). Quiescent/teardown sites
// (reprepareQuiescent, drainAllQuiescent, applyPendingConfig,
// resetChannelRange) hold the lifetime mutex FIRST and only then spin the
// busy flag; with the single order a flag holder can never be waiting for
// the mutex, so those spins cannot deadlock. (The previous inversion —
// requestRigSwap/instantiate/reclaimRetired taking busy→lifetime against
// the quiescent paths' lifetime→busy — let a UI swap request × audio-device
// reopen interleave wedge both threads forever behind a closed gate.)
// Const getters that dereference a published rig (getParameterChecked/
// isAvailable/effectLatencyFrames) hold the lifetime mutex ONLY — safe
// because every delete of a published rig happens under that mutex.
// Control-busy is therefore implied by the mutex for mutual-exclusion
// purposes; it is kept as the explicit control-section marker the status
// code reports and to future-proof the invariant.
//
// Threading:
//  - instantiate()/reprepareQuiescent()/resetChannelRange()/
//    applyPendingConfig()/drainAllQuiescent() are control or worker-thread
//    only and NEVER reachable from the audio callback; process() publishes/
//    reads only atomics + its own scratch.
//  - A rig is instantiated lazily on the setEffectEnabledChecked(true) path
//    (JNI "enabled" call). Params set before instantiate are honored: the
//    mDesired row is applied to the effect before prepare().
//  - Audio callback gating per channel: published && rackEnabled &&
//    effectEnabled. The manager drives GuitarRigEffect::setBypassed from the
//    gate every block; the effect self-ramps and reports wantsFrames()==false
//    once fully dry, at which point the manager skips deinterleave/interleave
//    and the effect skips ALL DSP including the cabinet FFT. (During a swap
//    the gate drives bypassed only outside FADE_OUT; the protocol forces the
//    fade.)
//  - kAmpModel/kCabType changes on a live rig call requestRigSwap(): true =
//    swap accepted (it lands within ~20 ms of callback processing), false =
//    busy (a previous swap still in flight, or a control section is
//    currently held — retry shortly) or the builder failed (errorState is
//    set; CURRENT keeps running with its old model). The desired row is
//    updated either way.
class GuitarRigManager {
public:
    // ErrorState codes. BadChannel is returned by errorState() for an
    // invalid channel id — previously mislabeled AllocationFailed.
    enum ErrorState : int { Ok = 0, AllocationFailed = 1, EngineInitFailed = 2, BadChannel = 3 };

    static constexpr int kChannels = piano::kInstrumentChannelCount;

    // Crossfade durations around a live swap (spec §10.3: ~10 ms each way).
    static constexpr float kSwapRampSeconds = 0.01f;
    // Silent priming window after a swap commit: a freshly prepared rig's
    // zeroed internal state (amp DC/sag, gate, cab overlap buffers) has a
    // start-up transient on real signal; it is run inaudibly for this many
    // seconds so the fade-in that follows carries no fresh-engine click
    // (measured: without priming the host stress max|Δ| is 0.483, with 4 ms
    // priming it returns to the no-swap baseline — see README.guitar-rig-swap).
    static constexpr float kSwapPrimeSeconds = 0.004f;

    // Rack-wide click-fade convention outside swaps.
    static constexpr float kDefaultRampSeconds = 0.005f;

    GuitarRigManager();
    ~GuitarRigManager();
    GuitarRigManager(const GuitarRigManager&) = delete;
    GuitarRigManager& operator=(const GuitarRigManager&) = delete;

    // Worker thread, one-shot at engine init. Allocates the per-channel
    // planar deinterleave scratch only; rigs are lazy.
    bool prepare(double sampleRate, int maxFrames);
    // Worker thread while callbacks are quiesced (FluidSynth worker gate,
    // same sequence as InstrumentEffectManager::reprepareQuiescent on a
    // sample-rate change). DRAINS every mailbox first (retired reclaimed
    // inline, un-taken pending destroyed — it is superseded by the staged
    // rebuild, which builds from the same mDesired row and therefore already
    // carries the pending config), then rebuilds every published rig at the
    // new rate. A channel whose rebuild fails is released (dry) and flagged
    // EngineInitFailed. Returns false if any rebuild failed.
    bool reprepareQuiescent(double sampleRate, int maxFrames);

    // Control thread. Creates + prepares the rig for the channel (honoring
    // the mDesired param row). Idempotent.
    bool instantiate(int channel);

    bool setEffectEnabledChecked(int channel, bool enabled);
    void setEffectEnabled(int channel, bool enabled) noexcept;
    bool effectEnabled(int channel) const noexcept;
    // Rack gate. Behaviour across a swap (spec §10.7): rack-off keeps the
    // desired config intact and a pending swap STILL LANDS — the callback
    // commits it on the immediate (dry-rig) path, no fade needed.
    void setRackEnabled(int channel, bool enabled) noexcept;
    bool rackEnabled(int channel) const noexcept;

    bool setParameterChecked(int channel, uint32_t parameterId, float value) noexcept;
    bool getParameterChecked(int channel, uint32_t parameterId, float& value) const noexcept;

    int paramCount() const noexcept;
    bool paramInfo(int index, uint32_t& id, float& minValue, float& maxValue,
                   float& defaultValue, bool& logarithmic, bool& integer,
                   bool& toggled) const noexcept;
    const char* paramName(int index) const noexcept;

    bool isAvailable(int channel) const noexcept;
    int errorState(int channel) const noexcept;

    // ── Live-swap control surface ───────────────────────────────────────────
    // Worker thread. Serialize a rebuild of the channel's rig from the
    // current mDesired row (amp model + cab + all live params + fresh IR)
    // and hand it to the callback through the PENDING mailbox. The candidate
    // is built OFF the control guards (only the reclaim/busy-check and the
    // publish take them, in the single permitted order) so a long IR build
    // never stalls the global control path / other channels' knobs. Returns
    // true = swap accepted (lands on a processed audio block, ~10 ms out/in
    // fades). false = busy (a previous swap not yet consumed AND reclaimed,
    // or still armed), control-busy (retry shortly), builder failed
    // (errorState set, CURRENT untouched), or the channel has no published
    // rig (the desired row is simply honored at the next instantiate —
    // nothing to swap).
    bool requestRigSwap(int channel);
    // Explicit two-discrete-param form of the same operation (spec §10.2).
    // DEDUPE (m6): with (ampModel, cabType) equal to the CURRENT rig's applied
    // config and no swap in flight, returns true without building anything.
    bool requestRigConfig(int channel, int ampModel, int cabType);

    // Worker thread, LIVE-SAFE (no quiescence needed): if a completed swap
    // parked the ex-CURRENT rig in RETIRED and the callback has proven it
    // (pointer equality with the mEffects cell, see state table), reclaim +
    // delete it and adopt the new CURRENT. True if something was reclaimed.
    // requestRigSwap runs this itself before its busy test, so the normal UI
    // lane needs no separate call; exposed for tests/inspection.
    bool reclaimRetired(int channel);

    // Monotonic completed-swap counter for the channel — the COMMIT ACK of
    // the swap protocol (commits ONLY; see ACK honesty in the class doc).
    // Callback bumps at commit; superseded accepts bump supersededCount().
    uint64_t swapCount(int channel) const noexcept;
    // Monotonic count of accepted swaps destroyed off-thread by a quiescent
    // drain (config lands via the staged rebuild) or a channel reset. The
    // honest counterpart of swapCount for the "accepted ⇒ lands" contract.
    uint64_t supersededCount(int channel) const noexcept;
    // Swap in flight: PENDING unconsumed OR the request still armed. The UI
    // lane reports "применяется на лету / busy" honestly from this.
    bool hasPendingConfig(int channel) const noexcept;
    bool swapArmed(int channel) const noexcept;

    // ── Discrete-config dedupe (m6 — see the class comment) ────────────────
    // Pack a discrete (ampModel, cabType) rig-config pair as tracked in
    // mAppliedConfig (model<<2|cab, 0..15).
    static constexpr int packModelCab(int ampModel, int cabType) noexcept {
        return (ampModel << 2) | cabType;
    }
    // Packed discrete config the CURRENT engine provably runs with
    // (-1 = no published rig / not reconciled). Exposed for tests/status.
    int appliedConfig(int channel) const noexcept;
    // true = a request for this (model, cab) pair is a real no-op: a rig is
    // published, provably runs exactly this config, and no swap is in flight.
    bool discreteConfigDeduped(int channel, int ampModel, int cabType) const noexcept;

    // ── Control-status surface (JNI nativeGetGuitarRigStatus, spec §10) ───
    // Honest per-channel swap status for the UI lane. Messaging contract
    // (docs/guitar-fx-port.md §Live rig swap — Status codes, phase-G UI):
    //   kStatusBusyControl / kStatusBusySwapInFlight → "busy, retry shortly"
    //   kStatusBuildFailed / kStatusAllocationFailed → "failed, previous
    //                                                 sound retained"
    //   kStatusOk + pending flag                   → "applying (~30 ms)"
    //   kStatusBadChannel                          → invalid channel / non-
    //                                                 guitar.rig effect id
    enum RigStatus : int {
        kStatusOk = 0,               // settled: no swap in flight, no sticky error
        kStatusBusySwapInFlight = 1, // armed/pending/retired — retry shortly
        kStatusBusyControl = 2,      // a control section holds the guards
        kStatusBuildFailed = 3,      // EngineInitFailed: previous sound retained
        kStatusAllocationFailed = 4, // builder allocation failure
        kStatusBadChannel = 5,       // invalid channel id / wrong effect id
    };
    // Packed status for the single-int JNI surface: bits 0–3 = RigStatus
    // code, bits 4–31 = supersededCount saturated to 0x0FFFFFFF. Opportunistic
    // RETIRED reclaim (worker-side recovery path, see class doc) runs first
    // when the control guards are free.
    int rigStatus(int channel);
    int rigStatus(int channel, uint64_t& supersededOut);
    static constexpr int kRigStatusSupersededMask = 0x0FFFFFFF;
    static constexpr int packRigStatus(int code, uint64_t superseded) noexcept {
        const uint64_t sat = superseded > static_cast<uint64_t>(kRigStatusSupersededMask)
                                 ? static_cast<uint64_t>(kRigStatusSupersededMask)
                                 : superseded;
        return (static_cast<int>(sat) << 4) | (code & 0xF);
    }
    static constexpr int decodeRigStatusCode(int packed) noexcept { return packed & 0xF; }
    static constexpr int decodeRigStatusSuperseded(int packed) noexcept {
        return static_cast<int>(static_cast<unsigned int>(packed) >> 4);
    }

    // Control/worker thread, ONLY under the quiescent contract (audio
    // stopped/never started — the same gate reprepareQuiescent uses): apply
    // the protocol's slow path by hand. Reclaims RETIRED inline, applies an
    // un-taken PENDING to CURRENT directly (old rig destroyed inline), and
    // with an empty mailbox just calls applyConfigNow() on the published rig
    // (reconciles a mirror-only model/cab state after a builder failure).
    bool applyPendingConfig(int channel);
    // Control/worker thread, quiescent contract (shutdown path): reclaim
    // every RETIRED + destroy every un-taken PENDING on all channels, clear
    // the armed flags. Published rigs stay live and consistent.
    void drainAllQuiescent();

    int effectLatencyFrames(int channel) const noexcept;

    // Audio thread. Interleaved stereo buses (like InstrumentEffectManager).
    // Runs the per-channel swap state machine at the TOP of every published
    // channel's step, before the gate/wantsFrames skip decisions.
    void process(float* const buses[kChannels], int frames) noexcept;

    // Worker thread while callbacks are quiesced: releases the rigs and
    // clears all state for [first, first+count), draining mailboxes inline.
    bool resetChannelRange(int first, int count) noexcept;

    // TEST SEAM ONLY (host tests): fail the next rig build (simulates an
    // engine/IR allocation failure so the CURRENT-intact + errorState path
    // is exercised). Default failure code AllocationFailed. Never touched by
    // production code.
    void setFailNextBuildForTesting(bool fail) noexcept {
        mFailNextBuildCode = fail ? AllocationFailed : -1;
    }
    // TEST SEAM ONLY: fail the next rig build with an explicit ErrorState
    // code (e.g. EngineInitFailed → rigStatus kStatusBuildFailed).
    void setFailNextBuildCodeForTesting(int code) noexcept { mFailNextBuildCode = code; }
    // TEST SEAM ONLY: take/release the control guards (lifetime mutex → busy
    // flag, the single permitted order) from the calling thread so the
    // BusyControl status code and control-busy rejections are observably
    // deterministic. Callers on OTHER threads may query while held; querying
    // from the holding thread itself is undefined (non-recursive mutex).
    bool tryLockControlForTesting() noexcept;
    void unlockControlForTesting() noexcept;

private:
    enum SwapPhase : int { kPhaseIdle = 0, kPhaseFadeOut = 1, kPhaseFadeIn = 2 };
    // Per-channel audio-thread-owned state machine cell. Worker NEVER reads
    // or writes it (armed/mailbox are the worker-visible mirrors).
    struct SwapRuntime {
        int phase = kPhaseIdle;
    };

    // Worker paths below. Caller holds the lifetime mutex AND the control-busy
    // flag (single order, see class doc). Restores the invariant
    // "mEffects[c] owns the published rig": deletes a RETIRED rig only when it
    // pointer-equals mEffects[c], then adopts mPublished[c] into mEffects[c].
    // True if retired was reclaimed.
    bool syncAdoptLocked(int channel);
    // Quiescent contract required: sync + destroy an un-taken PENDING +
    // disarm. Used by reprepare/reset/drain/applyPendingConfig.
    void drainChannelQuiescentLocked(int channel);
    // Callback thread: the commit of the swap protocol (state table above).
    void commitSwapAudio(int channel) noexcept;

    // Caller must hold control guards (lifetime mutex + busy, single order).
    void releaseChannelLocked(int channel) noexcept;

    // Non-blocking control-section helpers in the ONE permitted order
    // (lifetime mutex → busy flag). tryLockControl false = contended.
    bool tryLockControl() noexcept;
    void unlockControl() noexcept;
    // Best-effort guarded mirror of a desired value onto the published rig
    // (FIX C1: the dereference MUST hold the control guards — every delete
    // site of a published rig holds them too). Takes/releases the guards
    // itself; safe to call with nothing held.
    void mirrorToRig(int channel, uint32_t parameterId, float value) noexcept;
    // armed || pending || retired — the authoritative per-channel in-flight
    // predicate (atomic reads only, no locks).
    bool swapInFlight(int channel) const noexcept;
    // Build a fully prepared swap candidate from the mDesired row at the
    // given snapshot rate/frames. Returns null on failure (errorState set).
    // Called OFF the control guards (see requestRigSwap doc).
    std::unique_ptr<GuitarRigEffect> buildCandidate(int channel, double sampleRate, int maxFrames);

    std::array<std::unique_ptr<GuitarRigEffect>, kChannels> mEffects{};
    std::array<std::atomic<GuitarRigEffect*>, kChannels> mPublished{};
    std::array<std::atomic<GuitarRigEffect*>, kChannels> mPending{};
    std::array<std::atomic<GuitarRigEffect*>, kChannels> mRetired{};
    std::array<std::atomic<bool>, kChannels> mSwapArmed{};
    std::array<std::atomic<uint64_t>, kChannels> mSwapCount{};
    std::array<std::atomic<uint64_t>, kChannels> mSupersededCount{};
    std::array<SwapRuntime, kChannels> mSwapRuntime{};  // audio thread only
    std::array<std::atomic<bool>, kChannels> mRackEnabled{};
    std::array<std::atomic<bool>, kChannels> mEffectEnabled{};
    // Desired-state atomics (defaults from the descriptor table). The single
    // source of truth for pending-before-instantiate parameter honoring and
    // for building swap candidates.
    std::array<std::array<std::atomic<float>, kParamCount>, kChannels> mDesired{};
    // Discrete (ampModel, cabType) config the CURRENT engine provably runs
    // with, packed by packModelCab; -1 = no rig / not reconciled (never
    // dedupe). Updated ONLY at config-landing sites (instantiate, staged
    // reprepare rebuild, swap commit ← the candidate's immutable
    // buildConfigSnapshot(), quiescent apply) — never on a rejected or
    // failed request, so a retry after a failure always re-attempts. At the
    // commit it is stored BEFORE publishing the rig and BEFORE clearing the
    // armed flag, so any control thread that observes the channel settled
    // (armed==false, PENDING empty) also observes the key of the rig CURRENT
    // actually runs (no false dedupe of a request that a still-unlanded
    // change would need to re-arm).
    std::array<std::atomic<int>, kChannels> mAppliedConfig{};
    std::array<std::atomic<int>, kChannels> mErrorState{};
    std::array<float*, kChannels> mLeft{};
    std::array<float*, kChannels> mRight{};
    double mSampleRate = 0.0;
    int mMaxFrames = 0;
    std::atomic<bool> mPrepared{false};  // atomic: checked guard-free on control paths
    int mFailNextBuildCode = -1;  // test seam (ErrorState code), control-thread only
    std::atomic_flag mControlBusy = ATOMIC_FLAG_INIT;
    // Control/getter lifetime guard. Audio callback never takes this mutex.
    mutable std::mutex mLifetimeMutex;
};

} // namespace guitar
} // namespace piano
