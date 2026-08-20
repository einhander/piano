# LADSPA descriptor enumeration (Phase 2 / Milestone 2)

This document records the LADSPA descriptors exported by the Android
`lsp-plugins-ladspa.so` (Phase 1 artifact) and the descriptors selected for
PianoAPP's first master effect chain.

The artifact exports a single entry point:

```c
const LADSPA_Descriptor *ladspa_descriptor(unsigned long index);
```

The host validator built during the LSP build reports
`plugins=198, warnings=0, errors=0`, i.e. 198 LADSPA descriptors are exposed.

## Descriptor IDs

`LSP_LADSPA_BASE = 0x4C5350` (= 5002064), defined in
`lsp-plugins-shared/include/lsp-plug.in/shared/meta/developers.h`.
Per-plugin LADSPA UniqueIDs are `BASE + offset`. The LADSPA Label is the
upstream string used in `LSP_LADSPA_URI(...)`.

## Selected descriptors for the master chain

PianoAPP owns **stable IDs** that are independent of the LADSPA numeric
UniqueID. The mapping to upstream LADSPA lives only in the adapter layer
(`effects/lsp/LspEffectIds.h`, future).

| Piano stable ID | LADSPA Label                   | LADSPA UniqueID | Name                              | Module (meta source) |
|-----------------|--------------------------------|-----------------|-----------------------------------|----------------------|
| `lsp.parametric_eq` | `para_equalizer_x16_stereo` | 5002076         | Parametric Equalizer x16 Stereo   | lsp-plugins-para-equalizer `meta/para_equalizer.cpp` |
| `lsp.compressor`    | `compressor_stereo`         | 5002091         | Compressor Stereo                 | lsp-plugins-compressor `meta/compressor.cpp` |
| `lsp.limiter`       | `limiter_stereo`            | 5002123         | Limiter Stereo                    | lsp-plugins-limiter `meta/limiter.cpp` |

UniqueID arithmetic:
- `para_equalizer_x16_stereo` = `LSP_LADSPA_PARA_EQUALIZER_BASE + 2` =
  `(BASE+10)+2` = 5002076
- `compressor_stereo` = `LSP_LADSPA_COMPRESSOR_BASE + 1` = `(BASE+26)+1` =
  5002091
- `limiter_stereo` = `LSP_LADSPA_LIMITER_BASE + 1` = `(BASE+58)+1` = 5002123

### Why `para_equalizer_x16_stereo`

The Parametric Equalizer module ships several band-count variants
(`x8`, `x16`, `x32`) for both mono and stereo. `x16_stereo` is selected as a
reasonable balance of flexibility vs. CPU cost for a live-performance master
EQ. This can be revisited after the performance milestones (Phase 22).

## Stereo audio port layout (LADSPA)

All three selected plugins are stereo, exposing 4 audio ports:

- audio in L
- audio in R
- audio out L
- audio out R

This matches PianoAPP's planar internal effect API
(`AudioEffect::process(float* left, float* right, int frames)`).

## Key control ports (Phase 2 preview)

Port IDs below come from the upstream plugin metadata (the `port_t` arrays in
the per-plugin `meta/*.cpp`). PianoAPP will map these to its own stable
parameter names (Phase 14). Exact port indexes and range hints will be
confirmed by the offline descriptor-dump test (Milestone 2 runtime step).

### Compressor Stereo (`compressor_stereo`)
| Upstream port id | Display name      | Piano stable name (proposed) |
|------------------|-------------------|------------------------------|
| `al`             | Attack threshold  | `threshold`                  |
| `cr`             | Ratio             | `ratio`                      |
| `at`             | Attack time       | `attack_ms`                  |
| `rt`             | Release time      | `release_ms`                 |
| `mk`             | Makeup gain       | `makeup_db`                  |

### Limiter Stereo (`limiter_stereo`)
Threshold / release ports will be enumerated in the runtime dump.

### Parametric EQ x16 Stereo (`para_equalizer_x16_stereo`)
Per-band frequency / gain / Q ports will be enumerated in the runtime dump;
only a few bands will be exposed to the UI initially.

## Runtime enumeration (TODO)

A small host-side / on-device diagnostic that calls `ladspa_descriptor(i)` for
increasing `i` until it returns `NULL`, printing UniqueID/Label/Name/
PortCount/port classification, will be added to fully validate the table
above against the actual Android ELF. Until qemu-aarch64 is available or the
descriptor dump runs on-device, the values above are taken from the upstream
metadata sources that were compiled into the artifact.
