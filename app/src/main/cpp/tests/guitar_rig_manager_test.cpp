// guitar_rig_manager_test.cpp — host validation for piano::guitar::GuitarRigManager.
// Build + run commands: README.guitar-rig-manager.md (ASan + UBSan).
//
// Scenarios:
//   1 channel isolation: rig enabled on ch3 only → ch3 changed, ch4 byte-identical
//   2 rack OFF gates the rig: bus bit-identical
//   3 disable ramps to dry, then the bus is NOT touched again (no FFT when off)
//   4 param-before-instantiate honored (incl. model/cab before the rig is live)
//   5 live model/cab interim rejection + quiescent applyPendingConfig
//   6 enable/disable/reprepare cycles leak-free (ASan at exit), flags survive
//   7 resetChannelRange clears state
//   8 oversized frame count is a documented no-op

#include "effects/guitar/GuitarRigManager.h"

#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

using piano::guitar::GuitarRigManager;
using piano::guitar::kParamAmpModel;
using piano::guitar::kParamCabType;
using piano::guitar::kParamDriveGain;
using piano::guitar::kParamMix;

static int gFailures = 0;

#define CHECK(cond, ...)                                          \
    do {                                                          \
        if (!(cond)) { ++gFailures; printf("FAIL %s:%d: ", __FILE__, __LINE__); \
                       printf(__VA_ARGS__); printf("\n"); }       \
    } while (0)

static constexpr double kSr = 48000.0;
static constexpr int kFrames = 1024;

struct BusSet {
    std::vector<float*> buses;
    std::vector<float*> owned;
    BusSet() : buses(GuitarRigManager::kChannels, nullptr) {}
    ~BusSet() { for (float* p : owned) std::free(p); }
    void allocate(int channel, int frames) {
        float* p = static_cast<float*>(std::malloc(sizeof(float) * frames * 2));
        owned.push_back(p);
        buses[channel] = p;
    }
    void fillSine(int channel, int frames, float amp, double phase0 = 0.0) {
        float* b = buses[channel];
        for (int i = 0; i < frames; ++i) {
            const float v = static_cast<float>(
                amp * std::sin(2.0 * 3.14159265358979 * 110.0 * (i + phase0) / kSr));
            b[i * 2] = v;
            b[i * 2 + 1] = v * 0.5f;
        }
    }
};

static bool byteIdentical(const float* a, const float* b, int frames) {
    return std::memcmp(a, b, sizeof(float) * static_cast<size_t>(frames) * 2) == 0;
}

static double busRms(const float* b, int frames) {
    double s = 0.0;
    for (int i = 0; i < frames; ++i) s += static_cast<double>(b[i * 2]) * b[i * 2];
    return std::sqrt(s / frames);
}

int main() {
    // ── 1+2: channel isolation + rack gate; 3: ramp-to-dry then untouched ─
    {
        GuitarRigManager mgr;
        CHECK(mgr.prepare(kSr, kFrames), "prepare");
        BusSet buses;
        buses.allocate(3, kFrames);
        buses.allocate(4, kFrames);
        buses.fillSine(3, kFrames, 0.3);
        buses.fillSine(4, kFrames, 0.3);
        std::vector<float> snap3(buses.buses[3], buses.buses[3] + kFrames * 2);
        std::vector<float> snap4(buses.buses[4], buses.buses[4] + kFrames * 2);

        // Rig instantiated+enabled on ch3 but rack OFF → bit-identical.
        CHECK(mgr.instantiate(3), "instantiate ch3");
        CHECK(mgr.setEffectEnabledChecked(3, true), "enable ch3");
        for (int i = 0; i < 4; ++i) mgr.process(buses.buses.data(), kFrames);
        CHECK(byteIdentical(buses.buses[3], snap3.data(), kFrames),
              "rack-off must leave the bus untouched");
        CHECK(byteIdentical(buses.buses[4], snap4.data(), kFrames),
              "disabled channel must be untouched");
        mgr.setRackEnabled(3, true);
        for (int i = 0; i < 4; ++i) {
            buses.fillSine(3, kFrames, 0.3, i * kFrames);
            mgr.process(buses.buses.data(), kFrames);
        }
        CHECK(busRms(buses.buses[3], kFrames) > 1e-6, "ch3 must be processed");
        CHECK(byteIdentical(buses.buses[4], snap4.data(), kFrames),
              "ch4 must remain byte-identical (isolation)");
        printf("[1/2] channel isolation + rack gate: ok\n");

        // ── 3: disable → ramp to dry → bus no longer touched ─────────────
        CHECK(mgr.setEffectEnabledChecked(3, false), "disable ch3");
        for (int i = 0; i < 8; ++i) { // > 5 ms ramp
            buses.fillSine(3, kFrames, 0.3, 100.0 * kFrames + i * kFrames);
            std::memcpy(snap3.data(), buses.buses[3], sizeof(float) * kFrames * 2);
            mgr.process(buses.buses.data(), kFrames);
        }
        std::memcpy(snap3.data(), buses.buses[3], sizeof(float) * kFrames * 2);
        buses.fillSine(3, kFrames, 0.3, 777.0);
        std::memcpy(snap3.data(), buses.buses[3], sizeof(float) * kFrames * 2);
        mgr.process(buses.buses.data(), kFrames);
        CHECK(byteIdentical(buses.buses[3], snap3.data(), kFrames),
              "fully-dry channel must not be touched (no FFT when bypassed)");
    }

    // ── 4: pending params before instantiate ──────────────────────────────
    {
        GuitarRigManager mgr;
        CHECK(mgr.prepare(kSr, kFrames), "prepare");
        CHECK(mgr.setParameterChecked(5, kParamDriveGain, 0.9f), "param before instantiate");
        CHECK(mgr.setParameterChecked(5, kParamAmpModel, 2.0f), "model before instantiate (not live)");
        CHECK(mgr.instantiate(5), "instantiate ch5");
        float v = 0.0f;
        CHECK(mgr.getParameterChecked(5, kParamDriveGain, v) && v == 0.9f, "drive gain honored");
        CHECK(mgr.getParameterChecked(5, kParamAmpModel, v) && v == 2.0f, "model honored at create");
        CHECK(!mgr.hasPendingConfig(5), "fresh rig applies config, nothing pending");

        // ── 5: live model/cab rejection + quiescent apply ────────────────
        CHECK(!mgr.setParameterChecked(5, kParamAmpModel, 1.0f), "live model change must reject");
        CHECK(mgr.hasPendingConfig(5), "rejected change must stay pending");
        CHECK(mgr.getParameterChecked(5, kParamAmpModel, v) && v == 1.0f, "desired value visible");
        CHECK(mgr.setParameterChecked(5, kParamDriveGain, 0.2f), "live continuous param accepted");
        CHECK(!mgr.setParameterChecked(5, kParamCabType, 0.0f), "live cab change must reject");
        CHECK(mgr.hasPendingConfig(5), "cab change pending too");
        CHECK(mgr.applyPendingConfig(5), "quiescent apply");
        CHECK(!mgr.hasPendingConfig(5), "apply clears pending");
        CHECK(mgr.getParameterChecked(5, kParamCabType, v) && v == 0.0f, "applied value reported");
        CHECK(mgr.setParameterChecked(5, kParamMix, 0.5f), "mix is a live-safe parameter");

        // ── 6: reprepare keeps published channels + values ───────────────
        CHECK(mgr.setEffectEnabledChecked(5, true), "enable ch5");
        CHECK(mgr.reprepareQuiescent(44100.0, kFrames), "reprepare quiescent");
        CHECK(mgr.isAvailable(5), "ch5 rig survived reprepare");
        CHECK(mgr.effectEnabled(5), "enabled flag unchanged by reprepare");
        CHECK(mgr.getParameterChecked(5, kParamDriveGain, v) && v == 0.2f, "params survived reprepare");
        BusSet bs;
        bs.allocate(5, kFrames);
        bs.fillSine(5, kFrames, 0.3);
        mgr.setRackEnabled(5, true);
        for (int i = 0; i < 4; ++i) mgr.process(bs.buses.data(), kFrames);
        CHECK(std::isfinite(busRms(bs.buses[5], kFrames)), "finite after reprepare");
        CHECK(busRms(bs.buses[5], kFrames) > 0.0, "audible after reprepare (mix 0.5 keeps dry)");

        // ── 7: resetChannelRange clears ──────────────────────────────────
        CHECK(mgr.resetChannelRange(4, 4), "reset 4..7");
        CHECK(!mgr.isAvailable(5) && !mgr.effectEnabled(5) && !mgr.rackEnabled(5),
              "reset must clear ch5");
        CHECK(mgr.getParameterChecked(5, kParamDriveGain, v) && v == 0.35f,
              "reset restores default params");

        // ── 8: oversized frames no-op ────────────────────────────────────
        {
            BusSet bs2;
            bs2.allocate(6, kFrames);
            bs2.fillSine(6, kFrames, 0.3);
            std::vector<float> snap(bs2.buses[6], bs2.buses[6] + kFrames * 2);
            CHECK(mgr.instantiate(6) && mgr.setEffectEnabledChecked(6, true), "enable ch6");
            mgr.setRackEnabled(6, true);
            mgr.process(bs2.buses.data(), kFrames + 1); // > mMaxFrames
            CHECK(byteIdentical(bs2.buses[6], snap.data(), kFrames), "oversized call no-op");
        }
    } // destructor here: instantiate/enable/disable/reprepare cycles under ASan

    printf("%s (failures: %d)\n", gFailures == 0 ? "ALL PASS" : "FAILURES", gFailures);
    return gFailures == 0 ? 0 : 1;
}
