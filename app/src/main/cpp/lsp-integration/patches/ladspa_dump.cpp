// Dump every LADSPA descriptor label/UniqueID/Name from the .so.
#include <dlfcn.h>
#include <cstdio>
#include <cstring>
#include <cstdlib>
#include "ladspa/ladspa.h"
#include "ladspa_hint_defaults.h"

int main(int argc, char **argv) {
    const char *so_path = (argc > 1) ? argv[1] : "lsp-plugins-ladspa.so";
    unsigned long dump_uid = (argc > 2) ? std::strtoul(argv[2], nullptr, 10) : 0;
    void *h = dlopen(so_path, RTLD_NOW | RTLD_LOCAL);
    if (!h) { std::fprintf(stderr, "dlopen: %s\n", dlerror()); return 2; }
    auto fn = (const LADSPA_Descriptor *(*)(unsigned long)) dlsym(h, "ladspa_descriptor");
    if (!fn) { std::fprintf(stderr, "no ladspa_descriptor\n"); return 3; }
    unsigned long n = 0;
    for (unsigned long i = 0;; ++i) {
        const LADSPA_Descriptor *d = fn(i);
        if (!d) break;
        if (dump_uid && d->UniqueID == dump_uid) {
            std::printf("Plugin uid=%lu label=%s ports=%lu\n", d->UniqueID, d->Label, d->PortCount);
            for (unsigned long p = 0; p < d->PortCount; ++p) {
                const LADSPA_PortDescriptor pd = d->PortDescriptors[p];
                const char *kind = LADSPA_IS_PORT_AUDIO(pd) ? (LADSPA_IS_PORT_INPUT(pd)?"AIN":"AOUT")
                             : (LADSPA_IS_PORT_INPUT(pd)?"CTL_IN":"CTL_OUT");
                const LADSPA_PortRangeHint *hh = &d->PortRangeHints[p];
                const char *pname = "(none)";
                if (d->PortNames) {
                    const char * const *nn = d->PortNames;
                    unsigned long idx = 0;
                    while (*nn && idx < p) { ++nn; ++idx; }
                    if (*nn && idx == p) pname = *nn;
                }
                const LADSPA_PortRangeHintDescriptor hints = hh->HintDescriptor;
                const bool bounded_below = (hints & LADSPA_HINT_BOUNDED_BELOW) != 0;
                const bool bounded_above = (hints & LADSPA_HINT_BOUNDED_ABOVE) != 0;
                const float lo = bounded_below ? hh->LowerBound : 0.0f;
                const float hi = bounded_above ? hh->UpperBound : 0.0f;
                const float scale = (hints & LADSPA_HINT_SAMPLE_RATE) ? 48000.0f : 1.0f;
                const float dfl = ladspa_hint_default(hints, lo, hi, 48000.0f);
                std::printf("  [%3lu] %-6s %-30s hintdesc=0x%lx lo=%g hi=%g default=%s%g%s\n",
                    p, kind, pname, (unsigned long)hh->HintDescriptor,
                    bounded_below ? hh->LowerBound * scale : 0.0f,
                    bounded_above ? hh->UpperBound * scale : 0.0f,
                    (hints & LADSPA_HINT_DEFAULT_MASK) == LADSPA_HINT_DEFAULT_NONE ? "unspecified(" : "",
                    dfl,
                    (hints & LADSPA_HINT_DEFAULT_MASK) == LADSPA_HINT_DEFAULT_NONE ? ")" : "");
            }
        }
        ++n;
    }
    if (!dump_uid) std::printf("TOTAL=%lu\n", n);
    dlclose(h);
    return 0;
}
