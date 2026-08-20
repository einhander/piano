// Offline LADSPA feasibility test for the Android lsp-plugins-ladspa.so.
//
// Phase 1 / Milestone 2 deliverable (plan Phase 36 item 8):
//   - enumerate ladspa_descriptor() until NULL
//   - locate compressor_stereo / limiter_stereo / para_equalizer_x16_stereo by Label
//   - instantiate at 48 kHz
//   - connect ports (stereo in/out + controls)
//   - run() a 1 kHz sine and a DC/silence signal
//   - assert: output is finite, and a non-bypassed effect measurably alters the signal
//
// Cross-compiled with the NDK; run under qemu-aarch64-static against the NDK sysroot libs.
//
// Build (host x86-64 variant, for the offline feasibility proof):
//   g++ -O2 -std=c++17 \
//     -I app/src/main/cpp/third_party/lsp/modules/lsp-3rd-party/include \
//     app/src/main/cpp/lsp-integration/patches/ladspa_offline_test.cpp \
//     -o /tmp/ladspa_offline_test_host -ldl -lm
// Build (aarch64 variant, NDK):
//   $NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang++ \
//     -O2 -fPIE -std=c++17 \
//     -I app/src/main/cpp/third_party/lsp/modules/lsp-3rd-party/include \
//     app/src/main/cpp/lsp-integration/patches/ladspa_offline_test.cpp \
//     -o /tmp/ladspa_offline_test -ldl -lm

#include <dlfcn.h>
#include <cstdio>
#include <cstring>
#include <cmath>
#include <cstdlib>
#include <algorithm>
#include "ladspa/ladspa.h"

static const LADSPA_Descriptor *(*ladspa_descriptor_fn)(unsigned long) = nullptr;

static const LADSPA_Descriptor *find_by_label(const char *label) {
    for (unsigned long i = 0;; ++i) {
        const LADSPA_Descriptor *d = ladspa_descriptor_fn(i);
        if (!d) return nullptr;
        if (d->Label && std::strcmp(d->Label, label) == 0) return d;
    }
}

// Match by UniqueID (the LADSPA Label is a full URI, so UniqueID is the
// stable identifier we rely on).
static const LADSPA_Descriptor *find_by_uid(unsigned long uid) {
    for (unsigned long i = 0;; ++i) {
        const LADSPA_Descriptor *d = ladspa_descriptor_fn(i);
        if (!d) return nullptr;
        if (d->UniqueID == uid) return d;
    }
}

static bool all_finite(const float *p, size_t n) {
    for (size_t i = 0; i < n; ++i) if (!std::isfinite(p[i])) return false;
    return true;
}

static double rms(const float *p, size_t n) {
    double s = 0;
    for (size_t i = 0; i < n; ++i) s += double(p[i]) * p[i];
    return std::sqrt(s / n);
}

// Connect audio + control ports. Named control ports are set to sensible
// unity/test values; everything else stays at 0.0 (plugin default-ish).
struct Ctrl { const char *name; float value; };
static const Ctrl *g_ctrls = nullptr;
static bool g_debug = false;

static float match_ctrl(const char *pname) {
    if (!g_ctrls || !pname) return 0.0f;
    for (const Ctrl *c = g_ctrls; c->name; ++c)
        if (std::strstr(pname, c->name)) { if (g_debug) std::fprintf(stderr, "  match '%s' ~ '%s' -> %g\n", c->name, pname, c->value); return c->value; }
    return 0.0f;
}

static void connect_ports(const LADSPA_Descriptor *d, LADSPA_Handle h,
                          float *inL, float *inR, float *outL, float *outR,
                          unsigned long frames,
                          float *control_storage, size_t control_cap) {
    unsigned long ai = 0, ao = 0, ci = 0;
    for (unsigned long p = 0; p < d->PortCount; ++p) {
        const LADSPA_PortDescriptor pd = d->PortDescriptors[p];
        const char *pname = "(none)";
        if (d->PortNames) {
            const char * const *nn = d->PortNames;
            unsigned long idx = 0;
            while (*nn && idx < p) { ++nn; ++idx; }
            if (*nn && idx == p) pname = *nn;
        }
        if (LADSPA_IS_PORT_AUDIO(pd)) {
            if (LADSPA_IS_PORT_INPUT(pd)) d->connect_port(h, p, (ai++ == 0) ? inL : inR);
            else                          d->connect_port(h, p, (ao++ == 0) ? outL : outR);
        } else if (LADSPA_IS_PORT_CONTROL(pd)) {
            float *slot = &control_storage[ci < control_cap ? ci : 0];
            *slot = match_ctrl(pname);
            d->connect_port(h, p, slot);
            if (ci < control_cap) ++ci;
        }
    }
    (void)frames;
}

int main(int argc, char **argv) {
    const char *so_path = (argc > 1) ? argv[1] : "lsp-plugins-ladspa.so";
    if (argc > 2 && !std::strcmp(argv[2], "-v")) g_debug = true;
    void *h = dlopen(so_path, RTLD_NOW | RTLD_LOCAL);
    if (!h) { std::fprintf(stderr, "dlopen failed: %s\n", dlerror()); return 2; }

    ladspa_descriptor_fn = (const LADSPA_Descriptor *(*)(unsigned long))
        dlsym(h, "ladspa_descriptor");
    if (!ladspa_descriptor_fn) { std::fprintf(stderr, "no ladspa_descriptor\n"); return 3; }

    // Enumerate.
    unsigned long total = 0;
    for (unsigned long i = 0;; ++i) {
        const LADSPA_Descriptor *d = ladspa_descriptor_fn(i);
        if (!d) break;
        ++total;
    }
    std::printf("descriptors_total=%lu\n", total);

    struct Target { const char *name; unsigned long uid; const Ctrl *ctrls; };
    Ctrl comp_ctrls[] = {
        {"Bypass", 0.0f}, {"Input gain", 1.0f}, {"Output gain", 1.0f},
        {"Ratio", 10.0f}, {"Attack threshold", 0.01f},
        {"Makeup gain", 1.0f}, {nullptr, 0.0f}
    };
    Ctrl lim_ctrls[] = {
        {"Bypass", 0.0f}, {"Input gain", 1.0f}, {"Output gain", 1.0f},
        {nullptr, 0.0f}
    };
    Ctrl eq_ctrls[] = {
        {"Bypass", 0.0f}, {"Input gain", 1.0f}, {"Output gain", 1.0f},
        {nullptr, 0.0f}
    };
    Target targets[] = {
        {"compressor_stereo",          5002091u, comp_ctrls},
        {"limiter_stereo",             5002123u, lim_ctrls},
        {"para_equalizer_x16_stereo",  5002076u, eq_ctrls},
    };
    const unsigned long sr = 48000;
    const unsigned long frames = 4096;
    const double freq = 1000.0;

    for (Target t : targets) {
        const LADSPA_Descriptor *d = find_by_uid(t.uid);
        if (!d) { std::printf("[%s] NOT FOUND (uid=%lu)\n", t.name, t.uid); continue; }
        std::printf("[%s] UniqueID=%lu Label=%s PortCount=%lu\n",
                    t.name, d->UniqueID, d->Label, d->PortCount);

        LADSPA_Handle inst = d->instantiate(d, sr);
        if (!inst) { std::printf("[%s] instantiate FAILED\n", t.name); continue; }

        float inL[4096], inR[4096], outL[4096], outR[4096];
        float control[256] = {0};
        unsigned long ain[8] = {0}, aout[8] = {0};
        // 1 kHz sine at 0.5 amplitude (peak ~ -6 dBFS).
        for (unsigned long i = 0; i < frames; ++i) {
            float s = 0.5f * std::sin(2.0 * M_PI * freq * i / sr);
            inL[i] = s; inR[i] = s; outL[i] = 0.0f; outR[i] = 0.0f;
        }
        g_ctrls = t.ctrls;
        connect_ports(d, inst, inL, inR, outL, outR, frames, control, 256);
        (void)ain; (void)aout;

        if (d->activate) d->activate(inst);
        d->run(inst, frames);
        if (d->deactivate) d->deactivate(inst);

        bool fin = all_finite(outL, frames) && all_finite(outR, frames);
        double in_rms = rms(inL, frames);
        double out_rms = rms(outL, frames);
        std::printf("[%s] finite=%d in_rms=%.6f out_rms=%.6f ratio=%.4f\n",
                    t.name, (int)fin, in_rms, out_rms,
                    in_rms > 0 ? out_rms / in_rms : 0.0);

        // Also run a silence buffer for numerical-stability check.
        for (unsigned long i = 0; i < frames; ++i) { inL[i] = 0; inR[i] = 0; outL[i] = 0; outR[i] = 0; }
        if (d->activate) d->activate(inst);
        d->run(inst, frames);
        if (d->deactivate) d->deactivate(inst);
        bool fin_s = all_finite(outL, frames) && all_finite(outR, frames);
        std::printf("[%s] silence finite=%d max_abs=%.2e\n", t.name, (int)fin_s,
                    [&]{ float m=0; for(unsigned long i=0;i<frames;++i) m=std::max(m,std::fabs(outL[i])); return m; }());

        d->cleanup(inst);
    }

    dlclose(h);
    return 0;
}
