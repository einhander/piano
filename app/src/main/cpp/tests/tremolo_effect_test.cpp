// Host test for the custom Tremolo DSP (instrument rack slot 8,
// effects/TremoloEffect.h). Plain g++: no Android, no LSP .so.
//
// Checks (see README.tremolo.md for the build/run command):
//   0. descriptor/id wiring: 2 params (410 rate, 411 depth), ranges,
//      defaults, display names, id->slot routing, no LADSPA binding/port
//      table for the custom slot;
//   a. depth=0 -> bit-exact passthrough;
//   b. depth=100, rate 5 Hz @48 kHz: per-sample gain within [0,1], output
//      RMS visibly below input RMS, output not constant, gain reaches both
//      extremes over a full second;
//   c. L/R symmetry: (Lout/Lin) == (Rout/Rin) per sample within 1e-6;
//   d. phase/state continuity: two consecutive process(frames) calls on the
//      SAME instance reproduce the samples of one process(2*frames) call on
//      a fresh instance (tolerance 1e-5);
//   e. rate change mid-stream causes no gain discontinuity beyond the
//      per-sample LFO slope (max |delta gain| between adjacent samples < 0.01);
//   plus: hard bypass leaves the buffers untouched, parameter clamping,
//        unknown parameter ids are ignored.

#include "effects/TremoloEffect.h"
#include "effects/lsp/InstrumentEffectRouting.h"
#include "effects/lsp/LspEffectIds.h"

#include <algorithm>
#include <cassert>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <vector>

using namespace piano;

namespace {
constexpr double kTwoPi = 6.28318530717958647692;
}

int main() {
    // ── 0. descriptor / id wiring ─────────────────────────────────────────
    {
        int n = -1;
        const auto* d = lsp::paramDescriptors(lsp::kSlotTremolo, n);
        assert(d && n == 2);
        assert(d[0].id == lsp::kParamTremoloRate);
        assert(d[0].minValue == 0.1f && d[0].maxValue == 20.0f && d[0].defaultValue == 5.0f);
        assert(d[0].logarithmic && !d[0].integer && !d[0].toggled);
        assert(d[1].id == lsp::kParamTremoloDepth);
        assert(d[1].minValue == 0.0f && d[1].maxValue == 100.0f && d[1].defaultValue == 50.0f);
        assert(!d[1].logarithmic && !d[1].integer && !d[1].toggled);
        assert(std::strcmp(lsp::paramDisplayName(lsp::kParamTremoloRate), "Rate (Hz)") == 0);
        assert(std::strcmp(lsp::paramDisplayName(lsp::kParamTremoloDepth), "Depth (%)") == 0);
        assert(lsp::effectSlotForId(lsp::kIdTremolo) == lsp::kSlotTremolo);
        assert(lsp::instrumentEffectSlotForId(lsp::kIdTremolo) == lsp::kSlotTremolo);
        // Custom slot: no LADSPA binding, no LADSPA port table.
        assert(!lsp::instrumentBindingForSlot(lsp::kSlotTremolo));
        int pc = 123;
        assert(!lsp::paramTable(lsp::kSlotTremolo, pc) && pc == 0);
        // Existing LADSPA slots are unaffected by the new slot.
        assert(lsp::instrumentEffectSlotForId(lsp::kIdFlanger) == lsp::kSlotFlanger);
        assert(lsp::instrumentBindingForSlot(lsp::kSlotFlanger));
    }

    // ── a. depth=0 -> bit-exact passthrough ───────────────────────────────
    {
        TremoloEffect fx;
        assert(fx.prepare(48000, 4096));
        assert(fx.isAvailable());
        assert(fx.getLatencyFrames() == 0);
        assert(std::strcmp(fx.stableId(), "piano.tremolo") == 0);
        assert(fx.isBypassed()); // fresh instance is bypassed (safe default)
        fx.setBypassed(false);
        fx.setParameter(lsp::kParamTremoloDepth, 0.0f);
        assert(fx.getParameter(lsp::kParamTremoloDepth) == 0.0f);
        constexpr int n = 4801; // odd length, spans > 1 period
        std::vector<float> inL(n), inR(n), l(n), r(n);
        for (int i = 0; i < n; ++i) {
            inL[i] = 0.87f * sinf(i * 0.31f) + 0.13f * sinf(i * 7.7f);
            inR[i] = -0.53f * cosf(i * 0.11f) + 0.41f * sinf(i * 3.3f);
            if (i % 1000 == 500) { inL[i] = -1.0f; inR[i] = 1.0f; } // rail extremes
            if (i % 997 == 100) { inL[i] = 0.0f; inR[i] = 0.0f; }    // digital silence
        }
        l = inL; r = inR;
        fx.process(l.data(), r.data(), n);
        for (int i = 0; i < n; ++i) {
            if (l[i] != inL[i] || r[i] != inR[i]) {
                std::fprintf(stderr, "FAIL: depth=0 not bit-exact at %d: L %g->%g R %g->%g\n",
                             i, double(inL[i]), double(l[i]), double(inR[i]), double(r[i]));
                return 1;
            }
        }
    }

    // ── b. depth=100 @ 5 Hz: bounded gain, attenuation, movement ──────────
    {
        TremoloEffect fx;
        assert(fx.prepare(48000, 4096));
        fx.setBypassed(false);
        fx.setParameter(lsp::kParamTremoloRate, 5.0f);
        fx.setParameter(lsp::kParamTremoloDepth, 100.0f);
        constexpr int n = 48000; // 1 s = 5 full LFO periods
        std::vector<float> in(n), out(n);
        for (int i = 0; i < n; ++i)
            in[i] = 0.5f * sinf(i * float(kTwoPi) * 440.0f / 48000.0f);
        out = in;
        fx.process(out.data(), out.data(), n); // L==R: mono LFO, identical input
        double rmsIn = 0.0, rmsOut = 0.0, minGain = 1.0, maxGain = 0.0;
        bool varied = false;
        for (int i = 0; i < n; ++i) {
            rmsIn += double(in[i]) * in[i];
            rmsOut += double(out[i]) * out[i];
            if (out[i] != out[i == 0 ? 0 : i - 1]) varied = true;
            if (std::fabs(in[i]) > 0.1f) {
                const double g = double(out[i]) / in[i];
                minGain = std::min(minGain, g);
                maxGain = std::max(maxGain, g);
                if (g < -1e-6 || g > 1.0 + 1e-6) {
                    std::fprintf(stderr, "FAIL: gain %g out of [0,1] at %d\n", g, i);
                    return 1;
                }
            }
        }
        rmsIn = std::sqrt(rmsIn / n);
        rmsOut = std::sqrt(rmsOut / n);
        if (!varied) { std::fprintf(stderr, "FAIL: output constant\n"); return 1; }
        // 440 Hz carrier x 5 Hz gain: uncorrelated, so ratio ~= sqrt(E[g^2])
        // = sqrt(0.375) ~= 0.612 for d=1. Allow a wide sane band: visible
        // attenuation, not silence, no boost.
        if (!(rmsOut < rmsIn * 0.95 && rmsOut > rmsIn * 0.05)) {
            std::fprintf(stderr, "FAIL: rms ratio %g/%g implausible\n", rmsOut, rmsIn);
            return 1;
        }
        // Over full periods the tabled sine reaches +/-1 within interpolation
        // error (~1e-6): both gain extremes must be approached.
        if (!(maxGain > 0.9f && minGain < 0.1f)) {
            std::fprintf(stderr, "FAIL: gain range [%g, %g] misses the extremes\n", minGain, maxGain);
            return 1;
        }
        std::printf("tremolo b: rmsIn=%.5f rmsOut=%.5f minGain=%.5f maxGain=%.5f\n",
                    rmsIn, rmsOut, minGain, maxGain);
    }

    // ── c. L/R symmetry: identical per-sample gain on both channels ───────
    {
        TremoloEffect fx;
        assert(fx.prepare(48000, 8192));
        fx.setBypassed(false);
        fx.setParameter(lsp::kParamTremoloRate, 5.0f);
        fx.setParameter(lsp::kParamTremoloDepth, 63.0f);
        constexpr int n = 48000;
        std::vector<float> lin(n), rin(n), lout(n), rout(n);
        // Slow incommensurate carriers: no integer sample in [0, n) is
        // exactly zero (zeros would sit at non-integer sample positions).
        for (int i = 0; i < n; ++i) {
            const double t = double(i) / 48000.0;
            lin[i] = 0.5f * float(std::cos(kTwoPi * 0.31 * t));
            rin[i] = -0.3f * float(std::sin(kTwoPi * 0.17 * t));
        }
        lout = lin; rout = rin;
        fx.process(lout.data(), rout.data(), n);
        int checked = 0;
        for (int i = 0; i < n; ++i) {
            if (std::fabs(lin[i]) < 1e-4f || std::fabs(rin[i]) < 1e-4f) continue;
            const double gl = double(lout[i]) / lin[i];
            const double gr = double(rout[i]) / rin[i];
            if (std::fabs(gl - gr) > 1e-6) {
                std::fprintf(stderr, "FAIL: L/R gain mismatch at %d: L %.9f R %.9f\n", i, gl, gr);
                return 1;
            }
            ++checked;
        }
        if (checked < n / 2) { std::fprintf(stderr, "FAIL: too few comparable samples\n"); return 1; }
    }

    // ── d. segmentation: split run == one run on a fresh instance ─────────
    {
        constexpr int n = 2000;
        TremoloEffect a, b;
        assert(a.prepare(48000, 8192));
        assert(b.prepare(48000, 8192));
        auto cfg = [](TremoloEffect& fx) {
            fx.setBypassed(false);
            fx.setParameter(lsp::kParamTremoloRate, 5.0f);
            fx.setParameter(lsp::kParamTremoloDepth, 50.0f);
        };
        cfg(a);
        cfg(b);
        std::vector<float> in(2 * n);
        for (int i = 0; i < 2 * n; ++i) in[i] = 0.7f * sinf(i * 0.23f);
        std::vector<float> aL(in), aR(in), bL(in), bR(in);
        a.process(aL.data(), aR.data(), n);          // ...state persists...
        a.process(aL.data() + n, aR.data() + n, n);  // ...across process() calls
        b.process(bL.data(), bR.data(), 2 * n);      // fresh instance, one call
        double maxDiff = 0.0;
        for (int i = 0; i < 2 * n; ++i) {
            maxDiff = std::max(maxDiff, std::fabs(double(aL[i]) - bL[i]));
            maxDiff = std::max(maxDiff, std::fabs(double(aR[i]) - bR[i]));
        }
        if (maxDiff > 1e-5) {
            std::fprintf(stderr, "FAIL: segmented vs single run differ by %g\n", maxDiff);
            return 1;
        }
        std::printf("tremolo d: split/single max diff = %.3e\n", maxDiff);
    }

    // ── e. rate change mid-stream: no gain discontinuity ──────────────────
    {
        TremoloEffect fx;
        assert(fx.prepare(48000, 8192));
        fx.setBypassed(false);
        fx.setParameter(lsp::kParamTremoloRate, 5.0f);
        fx.setParameter(lsp::kParamTremoloDepth, 100.0f);
        // DC input: output[i] == gain[i] bit-exactly, so the per-sample gain
        // step is directly measurable at EVERY sample (no ratio conditioning).
        constexpr int seg = 4000; // depth ramp settles after 240 samples (5 ms)
        std::vector<float> in(2 * seg), out(2 * seg);
        std::fill(in.begin(), in.end(), 1.0f);
        out = in;
        fx.process(out.data(), out.data(), seg);
        fx.setParameter(lsp::kParamTremoloRate, 12.0f); // jump mid-stream
        fx.process(out.data() + seg, out.data() + seg, seg);
        double maxStep = 0.0;
        int atMax = -1;
        for (int i = 1; i < 2 * seg; ++i) {
            const double step = std::fabs(double(out[i]) - out[i - 1]);
            if (step > maxStep) { maxStep = step; atMax = i; }
        }
        // Per-sample bound: 0.5*d*(|delta sin| + |delta depth|) with
        // |delta phase| <= 2*pi*12/48000 ~= 1.57e-3 rad and the 5 ms depth
        // ramp step 1/240 ~= 4.2e-3 => |delta gain| <= ~3e-3; 0.01 keeps
        // float slack. A phase RESET on the rate change would show ~0.5 here.
        if (maxStep > 0.01) {
            std::fprintf(stderr, "FAIL: rate change caused a gain step of %g at sample %d\n",
                         maxStep, atMax);
            return 1;
        }
        // The boundary sample itself must be within the bound too.
        if (std::fabs(double(out[seg]) - out[seg - 1]) > 0.01) {
            std::fprintf(stderr, "FAIL: gain step exactly at the rate-change boundary\n");
            return 1;
        }
        std::printf("tremolo e: max adjacent gain step across rate change = %.3e (at %d)\n",
                    maxStep, atMax);
    }

    // ── extras: hard bypass, clamping, unknown ids ────────────────────────
    {
        TremoloEffect fx;
        assert(fx.prepare(48000, 4096));
        fx.setBypassed(false);
        fx.setParameter(lsp::kParamTremoloRate, 999.0f);
        fx.setParameter(lsp::kParamTremoloDepth, -50.0f);
        fx.setParameter(77, 3.0f); // unknown id: ignored
        assert(fx.getParameter(lsp::kParamTremoloRate) == 20.0f);  // clamped
        assert(fx.getParameter(lsp::kParamTremoloDepth) == 0.0f);  // clamped
        assert(fx.getParameter(77) == 0.0f);
        fx.setParameter(lsp::kParamTremoloDepth, 100.0f);
        std::vector<float> a(512), b(512);
        for (int i = 0; i < 512; ++i) a[i] = b[i] = 0.3f;
        fx.process(a.data(), b.data(), 128); // warm the LFO at full depth
        for (int i = 0; i < 512; ++i) a[i] = b[i] = 0.3f;
        fx.setBypassed(true);
        fx.process(a.data(), b.data(), 512);
        for (int i = 0; i < 512; ++i) {
            if (a[i] != 0.3f || b[i] != 0.3f) {
                std::fprintf(stderr, "FAIL: bypassed process touched the buffers at %d\n", i);
                return 1;
            }
        }
    }

    std::printf("tremolo effect test: PASS\n");
    return 0;
}
