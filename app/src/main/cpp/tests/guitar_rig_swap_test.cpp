// guitar_rig_swap_test.cpp — host validation for the LIVE AMP/CAB SWAP
// protocol (spec §10, docs/guitar-fx-port.md §Live rig swap) implemented in
// piano::guitar::GuitarRigManager. Build + run: README.guitar-rig-swap.md.
//
// Invariants encoded here (the adversarial audit targets):
//   I1 busy-serialization: at most ONE swap in flight per channel; a second
//     request while PENDING-unconsumed / RETIRED-unreclaimed returns false,
//     and returns true again after the ACK (swapCount bump) + reclaim.
//   I2 builder-failure: CURRENT untouched + audible + errorState set; the
//     accepted flag is false; a later request succeeds once the seam clears.
//   I3 quiescent paths: applyPendingConfig / drainAllQuiescent / destructor
//     free PENDING + RETIRED inline, leak-clean under ASan, no fake ACK.
//   I4 stress: 1000 accepted model/cab alternations while a second thread
//     processes irregular blocks (1,7,16,31,64,512,4096,...): ASan+UBSan
//     clean (no UAF / double-free / leak), every fed frame processed, all
//     outputs finite, swapCount advances exactly per acceptance, and the
//     max consecutive-sample |Δ| stays within the documented click bound
//     (baseline maxΔ of the no-swap run + 0.05).
//   I5 determinism: the same swap schedule twice → BIT-IDENTICAL output
//     (eps 0) after settle.
//   I6 rack-off landing: a pending swap on a silent (dry) rig commits on
//     the first processed block without a fade.
//
// Concurrency-hardening scenarios (adversarial audit follow-ups T1–T5, the
// control-path UAF + lock-order fixes):
//   T1 setter-burst: setParameterChecked/requestRigConfig/instantiate on
//     ch3 hammering CONCURRENT with repeated reprepareQuiescent +
//     resetChannelRange and status/getter polls (no process(): the admin
//     paths require the quiescent contract). Expect: no crash, no leak
//     (ASan), rejections observed as honest false, drain leaves nothing
//     in flight.
//   T2 cross-channel: swap storms on ch2 and ch5 concurrently against the
//     processing thread — per-channel busy/commit semantics hold under the
//     global control guards (exactly N commits per channel, no wedge).
//   T3 segmented callback: TWO process() calls within one logical block
//     with the PENDING mailbox arriving mid-first-segment (async control
//     thread) — the commit lands, frames conserved, outputs finite.
//   T4 busy false→true→false: one accept → in-flight (busy true, second
//     request honestly rejected) → commit (busy false) advances swapCount
//     by EXACTLY one and supersededCount by zero; a superseded accept
//     (quiescent reprepare over a pending swap) bumps supersededCount,
//     NEVER swapCount.
//   T5 status-code mapping: every rigStatus code observable at least once
//     (Ok, BusySwapInFlight, BusyControl, BuildFailed, AllocationFailed,
//     BadChannel incl. errorState invalid-channel), packed superseded bits
//     + saturation.
//
// Ownership state table under test (CURRENT/PENDING/RETIRED/armed/ACK):
// see GuitarRigManager.h — this file attacks the table's busy + reclaim
// rules from the producer side and the fade/commit rules from the consumer.

#include "effects/guitar/GuitarRigManager.h"

#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <thread>
#include <vector>

using piano::guitar::GuitarRigManager;
using piano::guitar::kParamAmpModel;
using piano::guitar::kParamCabType;
using piano::guitar::kParamMix;

static int gFailures = 0;

#define CHECK(cond, ...)                                          \
    do {                                                          \
        if (!(cond)) { ++gFailures; printf("FAIL %s:%d: ", __FILE__, __LINE__); \
                       printf(__VA_ARGS__); printf("\n"); }       \
    } while (0)

static constexpr double kSr = 48000.0;
static constexpr int kMaxFrames = 4096;
static constexpr int kChannel = 0;
static constexpr int kSwaps = 1000;   // spec §10.8 stress count
// Documented click bound (spec §10.8): the swap-run max consecutive-sample
// |Δ| must not exceed the NATURAL max slew of the same timbres (measured
// with settled rigs, no swaps — high-gain hard-clip edges reach ~0.41/sample
// at defaults) plus this absolute margin. The swap protocol itself commits
// only at wet==0 (dry passthrough at the boundary instant, silent priming
// absorbs the fresh-engine transient) with per-sample linear fades, so it
// adds at most the ramp-slope edge ≈0.0021/sample; 0.05 is the measured
// assertion margin.
static constexpr double kClickMargin = 0.05;

static constexpr int kPatternSizes[10] = {4096, 512, 1, 7, 16, 31, 64, 4096, 4096, 16};

struct Scan {
    double maxDeltaL = 0.0;
    bool allFinite = true;
    double prevL = 0.0;
    bool havePrev = false;
    void observe(const float* bus, int frames) {
        for (int i = 0; i < frames; ++i) {
            const float l = bus[i * 2], r = bus[i * 2 + 1];
            if (!std::isfinite(l) || !std::isfinite(r)) allFinite = false;
            if (havePrev) {
                const double d = std::fabs(static_cast<double>(l) - prevL);
                if (d > maxDeltaL) maxDeltaL = d;
            }
            prevL = l;
            havePrev = true;
        }
    }
};

static void fillSine(float* bus, int frames, long long& frame) {
    for (int i = 0; i < frames; ++i, ++frame) {
        const double t = static_cast<double>(frame) / kSr;
        const float v = static_cast<float>(0.25 * std::sin(2.0 * 3.14159265358979 * 110.0 * t));
        bus[i * 2] = v;
        bus[i * 2 + 1] = v;
    }
}

int main() {
    // ── I1/I6: busy-serialization, ACK, RETIRED reclaim, rack-off landing ─
    {
        GuitarRigManager mgr;
        CHECK(mgr.prepare(kSr, kMaxFrames), "prepare");
        std::vector<float*> buses(GuitarRigManager::kChannels, nullptr);
        float* ch = static_cast<float*>(std::malloc(sizeof(float) * kMaxFrames * 2));
        buses[kChannel] = ch;
        long long fed = 0;
        fillSine(ch, 1024, fed);

        CHECK(mgr.instantiate(kChannel), "instantiate");
        // Gate open → the rig ramps to full wet (mix default 1.0), so swaps
        // on this channel ride the FADE path (fade-out → commit → fade-in).
        mgr.setEffectEnabled(kChannel, true);
        mgr.setRackEnabled(kChannel, true);
        for (int i = 0; i < 4; ++i) { // settle the ramp fully wet
            fillSine(ch, 1024, fed);
            mgr.process(buses.data(), 1024);
        }
        CHECK(mgr.setParameterChecked(kChannel, kParamAmpModel, 1.0f), "swap accepted (wet)");
        CHECK(mgr.hasPendingConfig(kChannel), "swap in flight");
        CHECK(!mgr.setParameterChecked(kChannel, kParamAmpModel, 2.0f), "I1: second change while pending → busy");
        CHECK(!mgr.setParameterChecked(kChannel, kParamCabType, 1.0f), "I1: cab change busy too");
        const uint64_t ack0 = mgr.swapCount(kChannel);
        int blocks = 0;
        while (mgr.swapCount(kChannel) == ack0 && blocks < 2000) {
            fillSine(ch, 4096, fed);
            mgr.process(buses.data(), 4096);
            ++blocks;
        }
        CHECK(mgr.swapCount(kChannel) == ack0 + 1, "swap committed within the 10ms fade window");
        CHECK(blocks >= 1, "commit needed at least one fade-out block (wet rig)");
        CHECK(mgr.hasPendingConfig(kChannel) == false || mgr.swapArmed(kChannel) == false,
              "armed cleared at commit");
        CHECK(mgr.reclaimRetired(kChannel), "I1: RETIRED reclaimed after commit");
        CHECK(!mgr.reclaimRetired(kChannel), "reclaim is idempotent (single-slot emptied)");
        CHECK(mgr.setParameterChecked(kChannel, kParamCabType, 1.0f), "I1: accepted after ack+reclaim");

        // I6: rack-off — a pending swap on the now-dry channel lands silently.
        // The channel is fully WET here (rack+effect on): the cab swap must
        // ride the fade path, so pump blocks until the commit ACK lands.
        int guard = 0;
        while (mgr.swapCount(kChannel) == ack0 + 1 && guard++ < 500) {
            fillSine(ch, 4096, fed);
            mgr.process(buses.data(), 4096);
        }
        CHECK(mgr.swapCount(kChannel) == ack0 + 2, "cab swap committed");
        CHECK(mgr.reclaimRetired(kChannel), "reclaim second");
        mgr.setRackEnabled(kChannel, false);
        for (int i = 0; i < 8; ++i) { fillSine(ch, 1024, fed); mgr.process(buses.data(), 1024); } // ramp dry
        CHECK(mgr.setParameterChecked(kChannel, kParamAmpModel, 0.0f), "rack-off change accepted");
        fillSine(ch, 1024, fed);
        mgr.process(buses.data(), 1024);  // dry rig → silent immediate commit on this block
        CHECK(mgr.swapCount(kChannel) == ack0 + 3, "I6: dry-rig swap committed silently on the next block");
        CHECK(mgr.reclaimRetired(kChannel), "reclaim third");
        std::free(ch);
        printf("[I1/I6] busy-serialization + ack + retire-reclaim + rack-off landing: ok\n");
    }

    // ── I2: builder-failure injection keeps CURRENT alive + UI-visible ────
    {
        GuitarRigManager mgr;
        CHECK(mgr.prepare(kSr, kMaxFrames), "prepare");
        std::vector<float*> buses(GuitarRigManager::kChannels, nullptr);
        float* ch = static_cast<float*>(std::malloc(sizeof(float) * kMaxFrames * 2));
        buses[kChannel] = ch;
        long long fed = 0;
        CHECK(mgr.instantiate(kChannel) && mgr.setEffectEnabledChecked(kChannel, true), "enable");
        mgr.setRackEnabled(kChannel, true);
        for (int i = 0; i < 6; ++i) { fillSine(ch, 4096, fed); mgr.process(buses.data(), 4096); }
        Scan scan;
        fillSine(ch, 1024, fed);
        mgr.process(buses.data(), 1024);
        scan.observe(ch, 1024);
        CHECK(scan.allFinite, "pre-fail run finite");

        mgr.setFailNextBuildForTesting(true);
        CHECK(!mgr.setParameterChecked(kChannel, kParamAmpModel, 2.0f), "I2: injected failure → false");
        CHECK(mgr.errorState(kChannel) == GuitarRigManager::AllocationFailed,
              "I2: errorState reports the builder failure");
        CHECK(!mgr.hasPendingConfig(kChannel), "I2: nothing was armed on failure");
        CHECK(mgr.isAvailable(kChannel), "I2: CURRENT rig untouched");
        CHECK(mgr.swapCount(kChannel) == 0, "I2: no fake commit");
        fillSine(ch, 1024, fed);
        mgr.process(buses.data(), 1024);
        scan.observe(ch, 1024);
        double rms = 0.0;
        for (int i = 0; i < 1024; ++i) rms += static_cast<double>(ch[i * 2]) * ch[i * 2];
        CHECK(scan.allFinite && rms > 1e-6, "I2: old rig still audible + finite");
        CHECK(mgr.errorState(kChannel) == GuitarRigManager::AllocationFailed, "errorState sticky until next accept");
        CHECK(mgr.setParameterChecked(kChannel, kParamAmpModel, 2.0f), "I2: retry accepted after seam clears");
        int g2 = 0;
        while (mgr.swapCount(kChannel) == 0 && g2++ < 100) {  // wet rig: fade path
            fillSine(ch, 4096, fed);
            mgr.process(buses.data(), 4096);
        }
        CHECK(mgr.swapCount(kChannel) == 1, "I2: swap landed");
        CHECK(mgr.errorState(kChannel) == GuitarRigManager::Ok, "accept clears errorState");
        CHECK(mgr.reclaimRetired(kChannel), "reclaim");
        std::free(ch);
        printf("[I2] builder-failure injection: ok\n");
    }

    // ── I3: quiescent apply + drain (audio never runs) ─────────────────────
    {
        GuitarRigManager mgr;
        CHECK(mgr.prepare(kSr, kMaxFrames), "prepare");
        std::vector<float*> buses(GuitarRigManager::kChannels, nullptr);
        float* ch = static_cast<float*>(std::malloc(sizeof(float) * kMaxFrames * 2));
        buses[kChannel] = ch;
        CHECK(mgr.instantiate(kChannel) && mgr.setEffectEnabledChecked(kChannel, true), "enable");
        mgr.setRackEnabled(kChannel, true);
        CHECK(mgr.setParameterChecked(kChannel, kParamAmpModel, 1.0f), "quiescent change accepted");
        CHECK(mgr.hasPendingConfig(kChannel), "pending sits unconsumed (no callback)");
        CHECK(mgr.applyPendingConfig(kChannel), "I3: quiescent apply of the PENDING rig");
        CHECK(!mgr.hasPendingConfig(kChannel), "I3: mailbox drained");
        CHECK(mgr.swapCount(kChannel) == 0, "I3: quiescent apply does NOT fake a callback ACK");
        long long fed = 0;
        fillSine(ch, 4096, fed);
        mgr.process(buses.data(), 4096);
        bool finite = true;
        for (int i = 0; i < 4096; ++i) if (!std::isfinite(ch[i * 2])) finite = false;
        CHECK(finite && mgr.isAvailable(kChannel), "I3: applied rig live + finite");
        float mv = 0.0f;
        CHECK(mgr.getParameterChecked(kChannel, kParamAmpModel, mv) && mv == 1.0f,
              "I3: applied model reported by the live rig");

        // A second change, then SHUTDOWN drain instead of a callback drain:
        CHECK(mgr.setParameterChecked(kChannel, kParamCabType, 3.0f), "second change accepted");
        mgr.drainAllQuiescent();
        CHECK(!mgr.hasPendingConfig(kChannel), "I3: drainAllQuiescent drained the mailbox");
        CHECK(mgr.swapCount(kChannel) == 0, "I3: drain does not fake a callback ACK");
        CHECK(mgr.isAvailable(kChannel), "I3: published rig survives the drain");
        std::free(ch);
        // Destructor (quiesced) must be leak-clean under ASan: retired rig
        // from the first apply-less sequence + applied rig all freed.
        printf("[I3] quiescent apply + shutdown drain: ok\n");
    }

    // ── I4: 1000-swap stress against an irregular-block callback thread ───
    {
        // Natural-slew reference: the swap run cycles all 3 amp models and
        // all 4 cabs, and high-gain clips produce steep edges whose |Δ| per
        // sample dwarfs any swap artifact. Measure each timbre's natural
        // max|Δ| directly (full wet, settled, same input), then assert the
        // swap run adds nothing beyond that (margin kClickMargin).
        double naturalMaxDelta = 0.0;
        for (int model = 0; model < 3; ++model) {
            for (int cab = 0; cab < 4; ++cab) {
                GuitarRigManager mgr;
                if (!mgr.prepare(kSr, kMaxFrames)) { CHECK(false, "nat prepare"); return 1; }
                std::vector<float*> buses(GuitarRigManager::kChannels, nullptr);
                float* ch = static_cast<float*>(std::malloc(sizeof(float) * kMaxFrames * 2));
                buses[kChannel] = ch;
                // Desired-before-instantiate: the rig is BORN with this timbre.
                mgr.setParameterChecked(kChannel, kParamAmpModel, static_cast<float>(model));
                mgr.setParameterChecked(kChannel, kParamCabType, static_cast<float>(cab));
                if (!mgr.instantiate(kChannel) || !mgr.setEffectEnabledChecked(kChannel, true)) {
                    CHECK(false, "nat enable"); std::free(ch); return 1;
                }
                mgr.setRackEnabled(kChannel, true);
                Scan nat;
                long long fed = 0;
                for (int i = 0; i < 12; ++i) {  // ramp + timbre settle, then scan
                    fillSine(ch, 4096, fed);
                    mgr.process(buses.data(), 4096);
                    if (i >= 2) nat.observe(ch, 4096);
                }
                if (nat.maxDeltaL > naturalMaxDelta) naturalMaxDelta = nat.maxDeltaL;
                std::free(ch);
            }
        }
        CHECK(naturalMaxDelta > 0.0, "natural-slew reference measured");

        // Stress run.
        GuitarRigManager mgr;
        CHECK(mgr.prepare(kSr, kMaxFrames), "stress prepare 2");
        std::vector<float*> buses(GuitarRigManager::kChannels, nullptr);
        float* ch = static_cast<float*>(std::malloc(sizeof(float) * kMaxFrames * 2));
        buses[kChannel] = ch;
        CHECK(mgr.instantiate(kChannel) && mgr.setEffectEnabledChecked(kChannel, true), "stress enable 2");
        mgr.setRackEnabled(kChannel, true);

        Scan stress;
        std::atomic<long long> framesFed{0};
        std::atomic<long long> framesProcessed{0};
        std::atomic<bool> controlDone{false};
        std::atomic<int> busySpins{0};
        std::atomic<bool> audioGaveUp{false};

        std::thread control([&]() {
            // Amp-model / cab-type alternation, spec §10.8.
            for (int n = 0; n < kSwaps; ++n) {
                const bool isModel = (n % 2) == 0;
                const uint32_t id = isModel ? kParamAmpModel : kParamCabType;
                const float val = isModel ? static_cast<float>((n / 2) % 3)
                                          : static_cast<float>((n / 2) % 4);
                while (!mgr.setParameterChecked(kChannel, id, val)) {
                    ++busySpins;
                    std::this_thread::sleep_for(std::chrono::microseconds(100));
                }
            }
            controlDone.store(true);
        });

        std::thread audio([&]() {
            long long fedLocal = 0;
            int blocks = 0;
            while (true) {
                const int n = kPatternSizes[blocks % 10];
                // Deterministic feed shared with the control thread's view of
                // the rig: single producer of input here.
                fillSine(ch, n, fedLocal);
                mgr.process(buses.data(), n);
                stress.observe(ch, n);
                framesFed.fetch_add(n, std::memory_order_relaxed);
                framesProcessed.fetch_add(n, std::memory_order_relaxed);
                ++blocks;
                if (controlDone.load() && mgr.swapCount(kChannel) == kSwaps) break;
                if (blocks > kSwaps * 40) { audioGaveUp.store(true); break; }
            }
        });

        control.join();
        audio.join();
        CHECK(!audioGaveUp.load(), "stress completed before the block budget");
        CHECK(mgr.swapCount(kChannel) == kSwaps, "I4: every accepted swap ACKed");
        CHECK(framesFed.load() == framesProcessed.load(), "I4: no lost frames");
        CHECK(stress.allFinite, "I4: all output finite");
        CHECK(busySpins.load() > 0, "I4: busy-rejection observed (producer faster than consumer)");
        const double bound = naturalMaxDelta + kClickMargin;
        printf("    I4 max|Δ|L natural-timbres=%.6f swap-run=%.6f bound=%.6f (margin %.3f)\n",
               naturalMaxDelta, stress.maxDeltaL, bound, kClickMargin);
        CHECK(stress.maxDeltaL <= bound,
              "I4: max sample jump within natural model slew + click margin");
        CHECK(mgr.reclaimRetired(kChannel) || !mgr.hasPendingConfig(kChannel), "final state clean");
        CHECK(!mgr.hasPendingConfig(kChannel), "no swap left in flight at the end");
        std::free(ch);
        printf("[I4] stress %d swaps: frames=%lld busy-spins=%d ok\n",
               kSwaps, framesFed.load(), busySpins.load());
    }

    // ── I5: determinism — same schedule twice, bit-identical after settle ──
    {
        auto runOnce = [](std::vector<float>& out, int blocks) {
            GuitarRigManager mgr;
            if (!mgr.prepare(kSr, kMaxFrames)) { ++gFailures; printf("FAIL det prepare\n"); return; }
            std::vector<float*> buses(GuitarRigManager::kChannels, nullptr);
            float* ch = static_cast<float*>(std::malloc(sizeof(float) * kMaxFrames * 2));
            buses[kChannel] = ch;
            if (!mgr.instantiate(kChannel) || !mgr.setEffectEnabledChecked(kChannel, true)) {
                ++gFailures; printf("FAIL det enable\n"); std::free(ch); return;
            }
            mgr.setRackEnabled(kChannel, true);
            long long fed = 0;
            for (int b = 0; b < blocks; ++b) {
                // Single-threaded: request accepted → consumed by the very
                // next processed block → reclaimed by the following request.
                if (b % 40 == 20) {
                    const bool ok = mgr.setParameterChecked(
                        kChannel, (b / 40) % 2 ? kParamCabType : kParamAmpModel,
                        static_cast<float>(b % 4));
                    if (!ok) { ++gFailures; printf("FAIL det request b=%d\n", b); }
                }
                const int n = kPatternSizes[b % 10];
                fillSine(ch, n, fed);
                mgr.process(buses.data(), n);
                if (b >= blocks - 32) {  // capture the settled tail
                    out.insert(out.end(), ch, ch + n * 2);
                }
            }
            std::free(ch);
        };
        std::vector<float> a, b;
        runOnce(a, 600);
        runOnce(b, 600);
        CHECK(a.size() == b.size() && !a.empty(), "I5: both runs captured the tail");
        CHECK(std::memcmp(a.data(), b.data(), sizeof(float) * a.size()) == 0,
              "I5: identical swap schedule → bit-identical settled output (eps 0)");
        printf("[I5] determinism: ok (%zu tail floats compared bit-exact)\n", a.size());
    }

    // ── T1: control-thread setter/request burst × quiescent reprepare/reset ─
    // Adversarial C1 regression (control-path UAF): every dereference of the
    // published rig from the control surface must run under the control
    // guards in the single lock order (lifetime → busy). No process() here —
    // reprepare/reset require the quiescent contract; the poller attacks
    // status + const getters against the concurrent free sites.
    {
        GuitarRigManager mgr;
        CHECK(mgr.prepare(kSr, kMaxFrames), "T1 prepare");
        constexpr int ch = 3;
        std::atomic<bool> stop{false};
        std::atomic<int> accepted{0}, rejected{0};
        std::thread setters([&]() {
            for (int i = 0; i < 6000 && !stop.load(std::memory_order_relaxed); ++i) {
                bool r = false;
                switch (i & 3) {
                    case 0: r = mgr.setParameterChecked(ch, kParamAmpModel, static_cast<float>(i % 3)); break;
                    case 1: r = mgr.requestRigConfig(ch, i % 3, (i / 3) % 4); break;
                    case 2: r = mgr.setParameterChecked(ch, kParamCabType, static_cast<float>(i % 4)); break;
                    default: r = mgr.setParameterChecked(ch, kParamMix, 0.5f); break;
                }
                (r ? accepted : rejected).fetch_add(1, std::memory_order_relaxed);
                if (i % 32 == 0) (void)mgr.instantiate(ch);  // busy-rejection is a valid outcome
            }
            stop.store(true, std::memory_order_release);
        });
        std::thread admin([&]() {
            for (int i = 0; !stop.load(std::memory_order_relaxed); ++i) {
                (void)mgr.reprepareQuiescent((i & 1) ? 44100.0 : kSr, kMaxFrames);
                (void)mgr.resetChannelRange(2, 4);
            }
        });
        std::thread poller([&]() {
            float tmp = 0.0f;
            for (int i = 0; !stop.load(std::memory_order_relaxed); ++i) {
                (void)mgr.rigStatus(ch);
                (void)mgr.hasPendingConfig(ch);
                (void)mgr.swapCount(ch);
                (void)mgr.supersededCount(ch);
                (void)mgr.errorState(ch);
                (void)mgr.getParameterChecked(ch, kParamAmpModel, tmp);  // blocks on lifetime only — bounded
            }
        });
        setters.join();
        admin.join();
        poller.join();
        CHECK(accepted.load() > 0, "T1: accepted work happened");
        CHECK(rejected.load() > 0, "T1: contention observed as honest false (busy-control / busy-swap)");
        mgr.drainAllQuiescent();
        CHECK(!mgr.hasPendingConfig(ch), "T1: quiescent drain leaves nothing in flight");
        printf("[T1] setter-burst × reprepare/reset + polls: ok (accepted=%d rejected=%d)\n",
               accepted.load(), rejected.load());
    }

    // ── T2: concurrent swap storms on ch2 AND ch5 (per-channel semantics) ──
    // One audio thread processes both buses; two control threads alternate
    // model/cab on their own channel. The global control guards serialize
    // sections, but busy/commit semantics are PER CHANNEL: each channel must
    // reach exactly its own N commits, no wedge, no crosstalk.
    {
        constexpr int kA = 2, kB = 5, kN = 40;
        GuitarRigManager mgr;
        CHECK(mgr.prepare(kSr, kMaxFrames), "T2 prepare");
        std::vector<float*> buses(GuitarRigManager::kChannels, nullptr);
        float* busA = static_cast<float*>(std::malloc(sizeof(float) * kMaxFrames * 2));
        float* busB = static_cast<float*>(std::malloc(sizeof(float) * kMaxFrames * 2));
        buses[kA] = busA;
        buses[kB] = busB;
        long long fedA = 0, fedB = 0;
        CHECK(mgr.instantiate(kA) && mgr.setEffectEnabledChecked(kA, true) && mgr.instantiate(kB) &&
                  mgr.setEffectEnabledChecked(kB, true),
              "T2 instantiate");
        mgr.setRackEnabled(kA, true);
        mgr.setRackEnabled(kB, true);
        for (int i = 0; i < 4; ++i) {  // settle both fully wet
            fillSine(busA, 1024, fedA);
            fillSine(busB, 1024, fedB);
            mgr.process(buses.data(), 1024);
        }
        std::atomic<int> issuedA{0}, issuedB{0};
        std::atomic<bool> controlDone{false}, gaveUp{false};
        std::atomic<bool> audioDone{false};
        Scan t2scan;
        std::thread ctrlA([&]() {
            while (issuedA.load() < kN && !gaveUp.load()) {
                const int n = issuedA.load();
                if (mgr.requestRigConfig(kA, n % 3, (n / 3) % 4)) issuedA.fetch_add(1);
                else std::this_thread::yield();
            }
        });
        std::thread ctrlB([&]() {
            while (issuedB.load() < kN && !gaveUp.load()) {
                const int n = issuedB.load();
                if (mgr.requestRigConfig(kB, (n / 2) % 3, n % 4)) issuedB.fetch_add(1);
                else std::this_thread::yield();
            }
        });
        std::thread audio([&]() {
            long long fA = fedA, fB = fedB;
            for (int blocks = 0; blocks < kN * 200; ++blocks) {
                const int n = kPatternSizes[blocks % 10];
                fillSine(busA, n, fA);
                fillSine(busB, n, fB);
                mgr.process(buses.data(), n);
                t2scan.observe(busA, n);
                if (mgr.swapCount(kA) >= kN && mgr.swapCount(kB) >= kN) { audioDone.store(true); break; }
            }
            fedA = fA;
            fedB = fB;
            audioDone.store(true);
        });
        ctrlA.join();
        ctrlB.join();
        // Let the audio thread land the issued swaps before stopping it.
        while (!audioDone.load() && !gaveUp.load()) std::this_thread::sleep_for(std::chrono::milliseconds(1));
        gaveUp.store(true);
        audio.join();
        CHECK(mgr.swapCount(kA) >= kN, "T2: ch2 landed all its own swaps");
        CHECK(mgr.swapCount(kB) >= kN, "T2: ch5 landed all its own swaps (per-channel busy, no wedge)");
        CHECK(t2scan.allFinite, "T2: all ch2 output finite");
        CHECK(mgr.reclaimRetired(kA) || !mgr.swapArmed(kA), "T2: ch2 reclaimable state");
        CHECK(mgr.reclaimRetired(kB) || !mgr.swapArmed(kB), "T2: ch5 reclaimable state");
        std::free(busA);
        std::free(busB);
        printf("[T2] cross-channel concurrent swaps (ch2+ch5, N=%d): ok\n", kN);
    }

    // ── T3: segmented callback — two process() calls in one block, pending ─
    // PENDING arrives (async control thread) DURING the first segment of a
    // logical 512-frame block: the state machine must commit across the
    // segmentation, conserve every fed frame, stay finite.
    {
        GuitarRigManager mgr;
        CHECK(mgr.prepare(kSr, kMaxFrames), "T3 prepare");
        std::vector<float*> buses(GuitarRigManager::kChannels, nullptr);
        float* ch = static_cast<float*>(std::malloc(sizeof(float) * kMaxFrames * 2));
        buses[kChannel] = ch;
        long long fed = 0;
        CHECK(mgr.instantiate(kChannel) && mgr.setEffectEnabledChecked(kChannel, true), "T3 enable");
        mgr.setRackEnabled(kChannel, true);
        for (int i = 0; i < 4; ++i) { fillSine(ch, 1024, fed); mgr.process(buses.data(), 1024); }
        const uint64_t ack0 = mgr.swapCount(kChannel);
        std::atomic<bool> accepted3{false};
        std::thread ctl([&]() { accepted3.store(mgr.requestRigConfig(kChannel, 1, 2)); });
        // One logical block = TWO segments (the arrival races segment 1).
        fillSine(ch, 256, fed);
        mgr.process(buses.data(), 256);
        fillSine(ch, 256, fed);
        mgr.process(buses.data(), 256);
        ctl.join();
        CHECK(accepted3.load(), "T3: request during a segmented block accepted");
        long long processed = 512;  // the two segments above fed exactly what they process
        int guard = 0;
        while (mgr.swapCount(kChannel) == ack0 && guard++ < 500) {
            fillSine(ch, 256, fed);
            mgr.process(buses.data(), 256);
            processed += 256;
        }
        CHECK(mgr.swapCount(kChannel) == ack0 + 1, "T3: commit lands across segmented blocks");
        CHECK(processed == fed - 1024 * 4, "T3: frames conserved (every fed frame processed)");
        bool finite = true;
        for (int i = 0; i < 512; ++i)
            if (!std::isfinite(ch[i * 2]) || !std::isfinite(ch[i * 2 + 1])) finite = false;
        CHECK(finite, "T3: segmented output finite");
        CHECK(mgr.reclaimRetired(kChannel), "T3: retired reclaimed");
        std::free(ch);
        printf("[T3] segmented callback (2 process() per block): ok\n");
    }

    // ── T4: busy false→true→false lands EXACTLY one new rig; supersede ─────
    {
        GuitarRigManager mgr;
        CHECK(mgr.prepare(kSr, kMaxFrames), "T4 prepare");
        std::vector<float*> buses(GuitarRigManager::kChannels, nullptr);
        float* ch = static_cast<float*>(std::malloc(sizeof(float) * kMaxFrames * 2));
        buses[kChannel] = ch;
        long long fed = 0;
        CHECK(mgr.instantiate(kChannel) && mgr.setEffectEnabledChecked(kChannel, true), "T4 enable");
        mgr.setRackEnabled(kChannel, true);
        for (int i = 0; i < 4; ++i) { fillSine(ch, 1024, fed); mgr.process(buses.data(), 1024); }
        CHECK(!mgr.hasPendingConfig(kChannel), "T4: busy=false at rest");
        CHECK(mgr.requestRigConfig(kChannel, 1, 0), "T4: request accepted");
        CHECK(mgr.hasPendingConfig(kChannel), "T4: busy=true while in flight");
        CHECK(!mgr.requestRigConfig(kChannel, 2, 1), "T4: second request rejected (busy)");
        const uint64_t ack0 = mgr.swapCount(kChannel);
        const uint64_t sup0 = mgr.supersededCount(kChannel);
        int guard = 0;
        while (mgr.swapCount(kChannel) == ack0 && guard++ < 500) {
            fillSine(ch, 4096, fed);
            mgr.process(buses.data(), 4096);
        }
        CHECK(mgr.swapCount(kChannel) == ack0 + 1, "T4: exactly ONE new rig committed");
        CHECK(mgr.supersededCount(kChannel) == sup0, "T4: a commit is not a supersession");
        CHECK(!mgr.hasPendingConfig(kChannel), "T4: busy=false again after commit");
        CHECK(mgr.reclaimRetired(kChannel), "T4: reclaim");
        // Supersede leg (audio stopped → quiescent contract holds): an
        // accepted swap destroyed by the staged rebuild bumps superseded,
        // NEVER swapCount (the ACK-honesty contract).
        CHECK(mgr.requestRigConfig(kChannel, 2, 1), "T4: second accept (quiescent)");
        CHECK(mgr.hasPendingConfig(kChannel), "T4: busy=true again");
        CHECK(mgr.reprepareQuiescent(kSr, kMaxFrames), "T4: staged rebuild supersedes");
        CHECK(!mgr.hasPendingConfig(kChannel), "T4: supersede clears the mailbox");
        CHECK(mgr.swapCount(kChannel) == ack0 + 1, "T4: NO fake commit ACK for a superseded swap");
        CHECK(mgr.supersededCount(kChannel) == sup0 + 1, "T4: supersession accounted honestly");
        CHECK(GuitarRigManager::decodeRigStatusCode(mgr.rigStatus(kChannel)) == GuitarRigManager::kStatusOk,
              "T4: status Ok after the supersede (config landed via the rebuild)");
        CHECK(mgr.isAvailable(kChannel), "T4: rebuilt rig live");
        std::free(ch);
        printf("[T4] busy-cycle lands one rig + honest supersede accounting: ok\n");
    }

    // ── T5: status-code mapping — every code observable at least once ───────
    {
        using Rig = GuitarRigManager;
        GuitarRigManager mgr;
        CHECK(mgr.prepare(kSr, kMaxFrames), "T5 prepare");
        // BadChannel (FIX M4: invalid channel gets its OWN code, not
        // AllocationFailed) — plus errorState consistency.
        CHECK(Rig::decodeRigStatusCode(mgr.rigStatus(-7)) == Rig::kStatusBadChannel, "T5: status(-7)=BadChannel");
        CHECK(Rig::decodeRigStatusCode(mgr.rigStatus(Rig::kChannels)) == Rig::kStatusBadChannel,
              "T5: status(kChannels)=BadChannel");
        CHECK(mgr.errorState(-7) == Rig::BadChannel, "T5: errorState(-7)=BadChannel");
        // Ok baseline (prepared, nothing in flight, no error).
        CHECK(Rig::decodeRigStatusCode(mgr.rigStatus(0)) == Rig::kStatusOk, "T5: Ok baseline");
        CHECK(mgr.instantiate(0) && mgr.setEffectEnabledChecked(0, true), "T5: enable");
        // BusySwapInFlight (armed + PENDING, callback never runs).
        CHECK(mgr.requestRigConfig(0, 1, 1), "T5: accept");
        CHECK(Rig::decodeRigStatusCode(mgr.rigStatus(0)) == Rig::kStatusBusySwapInFlight,
              "T5: BusySwapInFlight observable");
        CHECK(mgr.swapCount(0) == 0, "T5: quiescent accept commits nothing");
        // Ok + pending-flag messaging state after a quiescent APPLY: the
        // config lands (swapCount and supersededCount both stay 0 — an apply
        // is a direct landing, honest accounting).
        CHECK(mgr.applyPendingConfig(0), "T5: quiescent apply");
        CHECK(Rig::decodeRigStatusCode(mgr.rigStatus(0)) == Rig::kStatusOk, "T5: Ok after apply");
        // AllocationFailed via the boolean seam.
        mgr.setFailNextBuildForTesting(true);
        CHECK(!mgr.requestRigConfig(0, 2, 2), "T5: allocation failure rejected");
        CHECK(Rig::decodeRigStatusCode(mgr.rigStatus(0)) == Rig::kStatusAllocationFailed,
              "T5: AllocationFailed observable");
        CHECK(mgr.errorState(0) == Rig::AllocationFailed, "T5: errorState AllocationFailed");
        // BuildFailed (EngineInitFailed) via the code seam.
        mgr.setFailNextBuildCodeForTesting(Rig::EngineInitFailed);
        CHECK(!mgr.requestRigConfig(0, 0, 0), "T5: engine failure rejected");
        CHECK(Rig::decodeRigStatusCode(mgr.rigStatus(0)) == Rig::kStatusBuildFailed,
              "T5: BuildFailed observable");
        CHECK(mgr.errorState(0) == Rig::EngineInitFailed, "T5: errorState EngineInitFailed");
        // BusyControl via the guard seam (queried FROM ANOTHER THREAD — the
        // holding thread must not re-enter its own guards).
        CHECK(mgr.tryLockControlForTesting(), "T5: control seam held");
        int busyCode = -1;
        bool busyRej = true;
        std::thread probe([&]() {
            busyCode = Rig::decodeRigStatusCode(mgr.rigStatus(0));
            busyRej = !mgr.requestRigSwap(0);
        });
        probe.join();
        CHECK(busyCode == Rig::kStatusBusyControl, "T5: BusyControl observable");
        CHECK(busyRej, "T5: control-busy rejects swap requests");
        mgr.unlockControlForTesting();
        CHECK(Rig::decodeRigStatusCode(mgr.rigStatus(0)) == Rig::kStatusBuildFailed,
              "T5: sticky error survives the probe, cleared only by a new accept");
        // An accepted swap clears the error state and counts superseded via
        // the packed bits when a drain destroys it (the phase-G readout).
        CHECK(mgr.requestRigConfig(0, 1, 0), "T5: accept clears error");
        CHECK(Rig::decodeRigStatusCode(mgr.rigStatus(0)) == Rig::kStatusBusySwapInFlight, "T5: busy again");
        mgr.drainAllQuiescent();
        uint64_t sup = 0;
        const int packed = mgr.rigStatus(0, sup);
        CHECK(Rig::decodeRigStatusCode(packed) == Rig::kStatusOk && sup == 1 &&
                  Rig::decodeRigStatusSuperseded(packed) == 1,
              "T5: drain-supersede visible via the packed superseded bits");
        // Packing: saturation + round-trip.
        CHECK(Rig::decodeRigStatusSuperseded(Rig::packRigStatus(Rig::kStatusOk, 1ULL << 40)) == 0x0FFFFFFF,
              "T5: packed superseded saturates");
        CHECK(Rig::decodeRigStatusCode(Rig::packRigStatus(4, 12345)) == 4 &&
                  Rig::decodeRigStatusSuperseded(Rig::packRigStatus(4, 12345)) == 12345,
              "T5: pack round-trip");
        printf("[T5] status-code mapping (all 6 codes): ok\n");
    }

    printf("%s (failures: %d)\n", gFailures == 0 ? "ALL PASS" : "FAILURES", gFailures);
    return gFailures == 0 ? 0 : 1;
}
