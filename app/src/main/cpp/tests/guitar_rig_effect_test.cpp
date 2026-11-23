// guitar_rig_effect_test.cpp — host validation for piano::guitar::GuitarRigEffect
// (stereo pair of mono 0xFX engines). Build + run commands: README.guitar-rig-effect.md.
//
// Scenarios (exit 0 + "ALL PASS" = green; run under ASan+UBSan):
//   1  zero input, rig engaged            → exact zeros out, all finite
//   2  fresh (bypassed) rig               → bit-exact passthrough, garbage untouched
//   3  4219-frame call vs 4096+123        → every frame processed, bit-equal, guards intact
//   4  sine through rig, mix=1            → finite, RMS > 0
//   5  NaN/out-of-range params            → ignored/clamped, DSP stays finite
//   6  impulse latency                    → first non-zero frame == getLatencyFrames()
//   7  L-only input                       → R channel bit-identical (independent engines)
//   8  reprepare + teardown               → clean under ASan leak checks at exit
//   9  model/cab live change              → pending flag, applied only via applyConfigNow()

#include "effects/guitar/GuitarRigEffect.h"

#include <cmath>
#include <cstdio>
#include <cstring>
#include <vector>

using piano::guitar::GuitarRigEffect;
using piano::guitar::kParamAmpGain;
using piano::guitar::kParamAmpModel;
using piano::guitar::kParamCabEnabled;
using piano::guitar::kParamCabType;
using piano::guitar::kParamDriveGain;
using piano::guitar::kParamDriveType;
using piano::guitar::kParamMix;
using piano::guitar::kParamOutput;

static int gFailures = 0;

#define CHECK(cond, ...)                                          \
    do {                                                          \
        if (!(cond)) { ++gFailures; printf("FAIL %s:%d: ", __FILE__, __LINE__); \
                       printf(__VA_ARGS__); printf("\n"); }       \
    } while (0)

static constexpr double kSr = 48000.0;
static constexpr int kMaxFrames = 4219; // >4096 exercises the chunk loop

// Fill and return a fresh stereo buffer pair; caller owns.
struct Stereo {
    std::vector<float> l, r;
    Stereo(int n, float v = 0.0f) : l(n, v), r(n, v) {}
    float* L() { return l.data(); }
    float* R() { return r.data(); }
};

// Warm a rig to its steady wet state: ramp (5 ms) reaches mix=1 within the
// first warm-up block, so all later comparisons run with mixA pinned at 1.
static void warmUp(GuitarRigEffect& fx, int frames) {
    Stereo z(frames);
    for (int i = 0; i < 3; ++i) fx.process(z.L(), z.R(), frames);
}

int main() {
    // ── 1: zero input → exact zeros ─────────────────────────────────────
    {
        GuitarRigEffect fx;
        if (!fx.prepare(kSr, kMaxFrames)) { CHECK(false, "prepare failed"); return 1; }
        fx.setBypassed(false);
        warmUp(fx, 1024);
        Stereo s(2048);
        fx.process(s.L(), s.R(), 2048);
        bool allZero = true, allFinite = true;
        for (int i = 0; i < 2048; ++i) {
            if (s.l[i] != 0.0f || s.r[i] != 0.0f) allZero = false;
            if (!std::isfinite(s.l[i]) || !std::isfinite(s.r[i])) allFinite = false;
        }
        CHECK(allZero, "zero input must give exact zero output");
        CHECK(allFinite, "zero-input run must stay finite");
        printf("[1] zero-input silence: %s\n", (allZero && allFinite) ? "ok" : "FAILED");
    }

    // ── 2: bypassed (fresh default) → bit-exact untouched passthrough ───
    {
        GuitarRigEffect fx;
        if (!fx.prepare(kSr, kMaxFrames)) { CHECK(false, "prepare failed"); return 1; }
        CHECK(fx.isBypassed(), "fresh rig must default to bypassed");
        Stereo s(1024);
        // Deterministic garbage; also verify no NaN garbage that could mask
        // accidental processing.
        for (int i = 0; i < 1024; ++i) {
            s.l[i] = std::sin(0.001f * i) * 0.3f - 0.1f;
            s.r[i] = std::cos(0.013f * i) * 0.2f + 0.05f;
        }
        Stereo before = s;
        fx.process(s.L(), s.R(), 1024);
        bool identical = std::memcmp(s.l.data(), before.l.data(), sizeof(float) * 1024) == 0 &&
                         std::memcmp(s.r.data(), before.r.data(), sizeof(float) * 1024) == 0;
        CHECK(identical, "bypassed process must not touch the buffers");
        printf("[2] bypassed bit-exact passthrough: %s\n", identical ? "ok" : "FAILED");
    }

    // ── 3: oversized call == two-chunk reference, guards intact ─────────
    {
        Stereo in(kMaxFrames);
        for (int i = 0; i < kMaxFrames; ++i) {
            const float v = std::sin(2.0f * 3.14159265f * 220.0f * i / kSr) * 0.25f;
            in.l[i] = v; in.r[i] = -v;
        }
        auto runChunked = [&](GuitarRigEffect& fx) {
            Stereo s = in;
            fx.process(s.L(), s.R(), kMaxFrames); // internal [4096,123]
            return s;
        };
        auto runReference = [&](GuitarRigEffect& fx) {
            Stereo s = in;
            fx.process(s.L(), s.R(), 4096);
            fx.process(s.L() + 4096, s.R() + 4096, kMaxFrames - 4096);
            return s;
        };
        GuitarRigEffect a, b;
        if (!a.prepare(kSr, kMaxFrames) || !b.prepare(kSr, kMaxFrames)) {
            CHECK(false, "prepare failed"); return 1;
        }
        a.setBypassed(false); b.setBypassed(false);
        warmUp(a, 4096); warmUp(b, 4096);
        const Stereo sa = runChunked(a);
        const Stereo sb = runReference(b);
        bool bitEqual = std::memcmp(sa.l.data(), sb.l.data(), sizeof(float) * kMaxFrames) == 0 &&
                        std::memcmp(sa.r.data(), sb.r.data(), sizeof(float) * kMaxFrames) == 0;
        CHECK(bitEqual, "4096+123 internal chunking must equal the 4096/123 reference");
        // All frames processed: tail RMS non-zero.
        double tail = 0.0;
        for (int i = kMaxFrames - 123; i < kMaxFrames; ++i) tail += sa.l[i] * sa.l[i];
        CHECK(tail > 1e-12, "tail chunk must be processed (energy present)");

        // Guard slots around the working window must survive untouched.
        GuitarRigEffect c;
        if (!c.prepare(kSr, kMaxFrames)) { CHECK(false, "prepare failed"); return 1; }
        c.setBypassed(false);
        warmUp(c, 4096);
        std::vector<float> buf(8 + kMaxFrames + 8, 0.0f);
        for (int i = 0; i < 8; ++i) buf[i] = 7.0f + i, buf[8 + kMaxFrames + i] = -13.0f - i;
        // copy input into the window, process in place through the guard-owning buffer
        std::memcpy(buf.data() + 8, in.l.data(), sizeof(float) * kMaxFrames);
        c.process(buf.data() + 8, buf.data() + 8, kMaxFrames); // mono-ish on L window
        bool guardsOk = true;
        for (int i = 0; i < 8; ++i) {
            if (buf[i] != 7.0f + i || buf[8 + kMaxFrames + i] != -13.0f - i) guardsOk = false;
        }
        CHECK(guardsOk, "no writes outside [buffer, buffer+numFrames)");
        printf("[3] segmented processing bit-exact + guards: %s\n",
               (bitEqual && guardsOk) ? "ok" : "FAILED");
    }

    // ── 4: sine, mix=1 → finite, RMS>0 ──────────────────────────────────
    {
        GuitarRigEffect fx;
        if (!fx.prepare(kSr, kMaxFrames)) { CHECK(false, "prepare failed"); return 1; }
        fx.setBypassed(false);
        warmUp(fx, 4096);
        Stereo s(4096);
        for (int i = 0; i < 4096; ++i) {
            const float v = std::sin(2.0f * 3.14159265f * 440.0f * (i + 12000) / kSr) * 0.3f;
            s.l[i] = v; s.r[i] = v;
        }
        fx.process(s.L(), s.R(), 4096);
        double rms = 0.0; bool finite = true;
        for (int i = 2048; i < 4096; ++i) { // second half: gate fully open
            if (!std::isfinite(s.l[i]) || !std::isfinite(s.r[i])) finite = false;
            rms += s.l[i] * s.l[i];
        }
        rms = std::sqrt(rms / 2048.0);
        CHECK(finite, "sine pass must stay finite");
        CHECK(rms > 1e-6, "rig must produce audio at mix=1 (rms=%f)", rms);
        printf("[4] 440Hz sine RMS=%.6f finite=%d: %s\n", rms, (int)finite,
               (finite && rms > 1e-6) ? "ok" : "FAILED");
    }

    // ── 5: poisoned params ignored/clamped, DSP finite ──────────────────
    {
        GuitarRigEffect fx;
        if (!fx.prepare(kSr, kMaxFrames)) { CHECK(false, "prepare failed"); return 1; }
        fx.setBypassed(false);
        const float gainBefore = fx.getParameter(kParamDriveGain);
        fx.setParameter(kParamDriveGain, std::nanf("")); 
        CHECK(fx.getParameter(kParamDriveGain) == gainBefore, "NaN param must be ignored");
        fx.setParameter(kParamDriveGain, 1234.0f);
        CHECK(fx.getParameter(kParamDriveGain) == 1.0f, "out-of-range must clamp to max");
        fx.setParameter(kParamDriveGain, -50.0f);
        CHECK(fx.getParameter(kParamDriveGain) == 0.0f, "out-of-range must clamp to min");
        fx.setParameter(kParamDriveType, 1.6f);
        CHECK(fx.getParameter(kParamDriveType) == 2.0f, "integer param must quantize");
        fx.setParameter(kParamDriveType, 0.0f);
        Stereo s(4096);
        for (int i = 0; i < 4096; ++i) s.l[i] = s.r[i] = std::sin(0.05f * i) * 0.3f;
        warmUp(fx, 4096);
        fx.process(s.L(), s.R(), 4096);
        bool finite = true;
        for (int i = 0; i < 4096; ++i)
            if (!std::isfinite(s.l[i]) || !std::isfinite(s.r[i])) finite = false;
        CHECK(finite, "rig must stay finite after hostile params");
        printf("[5] hostile params handled: %s\n", finite ? "ok" : "FAILED");
    }

    // ── 6: latency measurement through the whole rig ────────────────────
    {
        GuitarRigEffect fx;
        if (!fx.prepare(kSr, kMaxFrames)) { CHECK(false, "prepare failed"); return 1; }
        fx.setBypassed(false);
        fx.setParameter(kParamOutput, 1.0f); // outGain=2, keep response strong
        warmUp(fx, 4096);                    // wet ramp pinned at 1
        Stereo s(4096);
        s.l[0] = 1.0f;                       // impulse at frame 0
        s.r[0] = 1.0f;
        fx.process(s.L(), s.R(), 4096);
        int firstNonZero = -1;
        int peakIdx = 0; float peak = 0.0f; double energy = 0.0;
        for (int i = 0; i < 4096; ++i) {
            const float a = std::fabs(s.l[i]);
            if (firstNonZero < 0 && a > 0.0f) firstNonZero = i;
            if (a > peak) { peak = a; peakIdx = i; }
            energy += a;
        }
        CHECK(firstNonZero >= 0, "impulse must produce a response (energy=%f)", energy);
        CHECK(firstNonZero == fx.getLatencyFrames(),
              "measured first-response frame %d != declared latency %d",
              firstNonZero, fx.getLatencyFrames());
        printf("[6] latency: first non-zero frame=%d, peak frame=%d, declared=%d: %s\n",
               firstNonZero, peakIdx, fx.getLatencyFrames(),
               firstNonZero == fx.getLatencyFrames() ? "ok" : "FAILED");
    }

    // ── 7: L-only input → R bit-identical (no bleed) ────────────────────
    {
        GuitarRigEffect fx;
        if (!fx.prepare(kSr, kMaxFrames)) { CHECK(false, "prepare failed"); return 1; }
        fx.setBypassed(false);
        fx.setParameter(kParamDriveType, 1.0f); // Jade engaged
        warmUp(fx, 4096);
        Stereo s(4096);
        for (int i = 0; i < 4096; ++i) { s.l[i] = std::sin(0.04f * i) * 0.3f; s.r[i] = 0.0f; }
        Stereo zeroCopy = s;
        fx.process(s.L(), s.R(), 4096);
        bool rIdentical = std::memcmp(s.r.data(), zeroCopy.r.data(), sizeof(float) * 4096) == 0;
        double lEnergy = 0.0;
        for (int i = 2048; i < 4096; ++i) lEnergy += std::fabs(s.l[i]);
        CHECK(rIdentical, "R channel must be bit-identical for L-only input");
        CHECK(lEnergy > 1e-6, "L channel must actually change");
        printf("[7] channel independence: %s\n", (rIdentical && lEnergy > 1e-6) ? "ok" : "FAILED");
    }

    // ── 8: prepare / re-prepare frees cleanly (ASan at exit) ────────────
    {
        for (int round = 0; round < 3; ++round) {
            GuitarRigEffect fx;
            if (!fx.prepare(kSr, kMaxFrames)) { CHECK(false, "prepare round %d failed", round); return 1; }
            if (!fx.prepare(44100.0, 4096)) { CHECK(false, "reprepare round %d failed", round); return 1; }
            Stereo s(1024);
            for (int i = 0; i < 1024; ++i) s.l[i] = s.r[i] = std::sin(0.02f * i) * 0.2f;
            fx.setBypassed(false);
            warmUp(fx, 1024);
            fx.process(s.L(), s.R(), 1024);
            bool finite = true;
            for (int i = 0; i < 1024; ++i)
                if (!std::isfinite(s.l[i]) || !std::isfinite(s.r[i])) finite = false;
            CHECK(finite, "reprepared rig must stay finite");
            // fx destructor here: engines + scratch reclaimed (ASan verifies
            // at process exit; nothing may leak).
        }
        printf("[8] prepare/reprepare/teardown: ok\n");
    }

    // ── 9: interim model/cab policy (pending flag, quiescent apply) ─────
    {
        GuitarRigEffect fx;
        if (!fx.prepare(kSr, kMaxFrames)) { CHECK(false, "prepare failed"); return 1; }
        fx.setBypassed(false);
        Stereo s(1024);
        for (int i = 0; i < 1024; ++i) s.l[i] = s.r[i] = std::sin(0.03f * i) * 0.25f;
        warmUp(fx, 1024);
        CHECK(!fx.hasPendingConfig(), "fresh prepare must leave config clean");
        fx.setParameter(kParamAmpModel, 1.0f);   // Regent 800
        fx.setParameter(kParamCabType, 0.0f);    // 1x12 open
        CHECK(fx.hasPendingConfig(), "live model/cab change must mark pending");
        fx.process(s.L(), s.R(), 1024);           // audio-rate change ignored
        CHECK(fx.hasPendingConfig(), "process must not consume the pending flag");
        CHECK(fx.applyConfigNow(), "quiescent apply must succeed");
        CHECK(!fx.hasPendingConfig(), "apply must clear the pending flag");
        fx.process(s.L(), s.R(), 1024);           // new config now audible
        bool finite = true;
        for (int i = 0; i < 1024; ++i)
            if (!std::isfinite(s.l[i]) || !std::isfinite(s.r[i])) finite = false;
        CHECK(finite, "rig finite after config swap");
        printf("[9] interim config policy: %s\n", finite ? "ok" : "FAILED");
    }

    printf("%s (failures: %d)\n", gFailures == 0 ? "ALL PASS" : "FAILURES", gFailures);
    return gFailures == 0 ? 0 : 1;
}
