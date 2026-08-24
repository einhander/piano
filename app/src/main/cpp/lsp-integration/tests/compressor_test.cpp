// Compressor gain-reduction test.
//
// Enables ONLY the compressor (slot 1) with a low threshold + high ratio and
// verifies the output RMS is materially below the input RMS (i.e. real gain
// reduction). Also dumps every compressor control port's LADSPA default vs
// the value our ParamPort table applies, to surface any unmapped port that
// defaults to a value that prevents compression (e.g. Sidechain source = 0).
//
// Build (host x86-64):
//   g++ -O2 -std=c++17 \
//     -I app/src/main/cpp \
//     -I app/src/main/cpp/effects \
//     -I app/src/main/cpp/effects/ladspa \
//     -I app/src/main/cpp/third_party/lsp/modules/lsp-3rd-party/include \
//     app/src/main/cpp/lsp-integration/tests/compressor_test.cpp \
//     app/src/main/cpp/effects/EffectChain.cpp \
//     app/src/main/cpp/effects/ladspa/LadspaEffect.cpp \
//     app/src/main/cpp/effects/ladspa/LadspaRegistry.cpp \
//     app/src/main/cpp/effects/lsp/LspEffectFactory.cpp \
//     app/src/main/cpp/effects/lsp/LspEffectIds.cpp \
//     -o /tmp/compressor_test -ldl -lm -lpthread
// Run:
//   /tmp/compressor_test app/src/main/cpp/third_party/lsp/.build/target/lsp-plugin-fw/lsp-plugins-ladspa.so

#include "effects/EffectChain.h"
#include "effects/lsp/LspEffectIds.h"
#include "ladspa/ladspa.h"

#include <dlfcn.h>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <algorithm>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

static double rms(const float* p, int n) {
    double s = 0;
    for (int i = 0; i < n; ++i) s += double(p[i]) * double(p[i]);
    return std::sqrt(s / n);
}

static void fill_sine(float* buf, int frames, double sr, double freq, float amp) {
    for (int i = 0; i < frames; ++i) {
        float s = amp * static_cast<float>(std::sin(2.0 * M_PI * freq * i / sr));
        buf[i * 2]     = s;
        buf[i * 2 + 1] = s;
    }
}

// Decode the LADSPA default value from the PortRangeHint (returns the literal
// default the plugin expects at init). Returns 0.0 when no default hint.
static float decode_default(const LADSPA_PortRangeHint* h) {
    unsigned int hd = h->HintDescriptor;
    float lo = h->LowerBound, hi = h->UpperBound;
    switch (hd & LADSPA_HINT_DEFAULT_MASK) {
        case LADSPA_HINT_DEFAULT_MINIMUM:  return lo;
        case LADSPA_HINT_DEFAULT_LOW:
            return LADSPA_IS_HINT_LOGARITHMIC(hd)
                ? float(std::exp(std::log(std::max(lo,1e-12f))*0.75 + std::log(std::max(hi,1e-12f))*0.25))
                : lo + 0.25f*(hi-lo);
        case LADSPA_HINT_DEFAULT_MIDDLE:
            return LADSPA_IS_HINT_LOGARITHMIC(hd)
                ? float(std::sqrt(std::max(lo,1e-12f) * std::max(hi,1e-12f)))
                : lo + 0.5f*(hi-lo);
        case LADSPA_HINT_DEFAULT_HIGH:
            return LADSPA_IS_HINT_LOGARITHMIC(hd)
                ? float(std::exp(std::log(std::max(lo,1e-12f))*0.25 + std::log(std::max(hi,1e-12f))*0.75))
                : lo + 0.75f*(hi-lo);
        case LADSPA_HINT_DEFAULT_MAXIMUM:  return hi;
        case LADSPA_HINT_DEFAULT_0:        return 0.0f;
        case LADSPA_HINT_DEFAULT_1:        return 1.0f;
        case LADSPA_HINT_DEFAULT_100:      return 100.0f;
        case LADSPA_HINT_DEFAULT_440:      return 440.0f;
        default:                           return 0.0f;  // NONE
    }
}

int main(int argc, char** argv) {
    const char* soPath = (argc > 1) ? argv[1] : "lsp-plugins-ladspa.so";
    const double sr = 48000.0;
    const int frames = 8192;   // enough for the envelope to settle + attack
    const double freq = 1000.0;
    const float amp = 0.8f;

    piano::EffectChain chain;
    int available = chain.loadBundle(soPath, sr, frames);
    if (available <= 0) {
        std::fprintf(stderr, "FAIL: loadBundle returned %d\n", available);
        return 1;
    }

    // ── Dump compressor port defaults vs our applied values ──
    // Re-open the .so directly to read the descriptor's PortRangeHints.
    void* dlh = dlopen(soPath, RTLD_NOW | RTLD_LOCAL);
    const LADSPA_Descriptor* compDesc = nullptr;
    if (dlh) {
        auto fn = (const LADSPA_Descriptor *(*)(unsigned long)) dlsym(dlh, "ladspa_descriptor");
        for (unsigned long i = 0;; ++i) {
            const LADSPA_Descriptor* d = fn(i);
            if (!d) break;
            if (d->UniqueID == 5002091u) { compDesc = d; break; }
        }
    }
    if (compDesc) {
        std::printf("=== compressor_stereo ports (default from LADSPA hint vs our applied value) ===\n");
        // Our applied values: whatever the chain currently has (defaults).
        int pc = 0;
        const piano::lsp::ParamPort* ourTable = piano::lsp::paramTable(piano::lsp::kSlotCompressor, pc);
        for (unsigned long p = 0; p < compDesc->PortCount; ++p) {
            const LADSPA_PortDescriptor pd = compDesc->PortDescriptors[p];
            if (LADSPA_IS_PORT_AUDIO(pd)) continue;  // skip audio ports
            const char* pname = "?";
            if (compDesc->PortNames) {
                const char* const* nn = compDesc->PortNames;
                unsigned long idx = 0;
                while (*nn && idx < p) { ++nn; ++idx; }
                if (*nn && idx == p) pname = *nn;
            }
            float ladspaDef = decode_default(&compDesc->PortRangeHints[p]);
            float ourVal = 0.0f; bool mapped = false;
            for (int i = 0; i < pc; ++i) {
                if (ourTable[i].port == int(p)) { ourVal = ourTable[i].def; mapped = true; break; }
            }
            const char* io = LADSPA_IS_PORT_INPUT(pd) ? "IN " : "OUT";
            std::printf("  [%2lu] %s %-30s LADSPA_def=%.6g  our_%s=%.6g\n",
                        p, io, pname, ladspaDef,
                        mapped ? "def" : "UNMAPPED(0)", mapped ? ourVal : 0.0f);
        }
    }

    // ── Gain-reduction test ──
    auto* comp = chain.effect(piano::lsp::kSlotCompressor);
    // Bypass the other two slots; enable the compressor.
    chain.effect(piano::lsp::kSlotEq)->setBypassed(true);
    chain.effect(piano::lsp::kSlotLimiter)->setBypassed(true);
    comp->setBypassed(false);

    using P = piano::lsp::ParamId;
    comp->setParameter(P::kParamInputGain, 1.0f);
    comp->setParameter(P::kParamOutputGain, 1.0f);
    comp->setParameter(P::kParamCompMode, 0.0f);        // Peak
    comp->setParameter(P::kParamCompThreshold, 0.05f);  // well below 0.8
    comp->setParameter(P::kParamCompAttackMs, 1.0f);
    comp->setParameter(P::kParamCompReleaseMs, 100.0f);
    comp->setParameter(P::kParamCompRatio, 10.0f);      // strong ratio
    comp->setParameter(P::kParamCompKnee, 0.0f);        // hard knee
    comp->setParameter(P::kParamCompMakeup, 1.0f);      // no makeup
    comp->setParameter(P::kParamCompWet, 1.0f);

    static float buf[16384];
    static float inCopy[16384];
    fill_sine(buf, frames, sr, freq, amp);
    std::memcpy(inCopy, buf, sizeof(float) * frames * 2);

    // Process several blocks so the attack/release envelope fully engages
    // (the first block only partially reduces).
    for (int b = 0; b < 8; ++b) {
        fill_sine(buf, frames, sr, freq, amp);
        chain.process(buf, frames);
    }
    // Final measurement block.
    fill_sine(buf, frames, sr, freq, amp);
    std::memcpy(inCopy, buf, sizeof(float) * frames * 2);
    chain.process(buf, frames);

    double inRms = rms(inCopy, frames * 2);
    double outRms = rms(buf, frames * 2);
    double ratio = (inRms > 0) ? (outRms / inRms) : 0.0;
    std::printf("\ncompressor: amp=%.3f in_rms=%.6f out_rms=%.6f ratio=%.4f\n",
                amp, inRms, outRms, ratio);

    int rc = 0;
    // With threshold 0.05 << signal 0.8 and ratio 10:1, the output RMS must
    // be materially below the input RMS. A ratio >= 0.95 means essentially no
    // compression is happening (control ports not wired / detector idle).
    if (ratio >= 0.95) {
        std::fprintf(stderr, "FAIL: compressor shows no gain reduction (ratio=%.4f >= 0.95)\n", ratio);
        rc = 1;
    } else {
        std::printf("PASS: compressor reduces gain (ratio=%.4f < 0.95)\n", ratio);
    }
    if (dlh) dlclose(dlh);
    return rc;
}
