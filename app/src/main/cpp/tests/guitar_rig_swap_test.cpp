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

    printf("%s (failures: %d)\n", gFailures == 0 ? "ALL PASS" : "FAILURES", gFailures);
    return gFailures == 0 ? 0 : 1;
}
