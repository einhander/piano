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

## Control-port maps (runtime-verified)

Audio ports are identical across the three plugins: `0`=in L, `1`=in R,
`2`=out L, `3`=out R. The control ports below are the ones the PianoAPP
adapter binds; `Bypass` is wired to the plugin's own bypass port (LADSPA hard
bypass in `run()`), and `latency` (CTL_OUT) is read after `activate()` for
chain delay compensation. Hint bits decoded: `0x4`=logarithmic, `0x20`=toggled,
`0x40`=integer, `0x200`=sample-rate dependent, `0x1000`=SR-dependent-max.

### `lsp.compressor` — `compressor_stereo` (uid 5002091, 66 ports)

| Piano stable name | Port | Range        | Hint      | Notes |
|-------------------|------|--------------|-----------|-------|
| `bypass`          | 8    | 0..1         | tog       | plugin bypass |
| `input_gain`      | 9    | 0..1000      | log,sr    | input trim (G); app clamps to 0..10 |
| `output_gain`     | 10   | 0..1000      | log,sr    | output trim (G); app clamps to 0..10 |
| `compression_mode`| 28   | 0..2         | int       | 0=classic; set ≠0 for active comp |
| `threshold`       | 29   | 0.001..1     | sr        | attack threshold (G) |
| `attack_ms`       | 30   | 0..2000      | sr        | attack time |
| `release_ms`      | 32   | 0..5000      | log,sr    | release time |
| `ratio`           | 34   | 1..100       |           | compression ratio |
| `knee`            | 35   | 0.0631..1    | sr        | knee (G) |
| `makeup`          | 38   | 0.001..1000  | log,sr    | makeup gain (G) |
| `wet`             | 40   | 0..10        | log,sr    | wet gain (default 1.0) |
| `latency`         | 65   | —            |           | CTL_OUT, read for delay comp |

### `lsp.limiter` — `limiter_stereo` (uid 5002123, 46 ports)

| Piano stable name | Port | Range          | Hint   | Notes |
|-------------------|------|----------------|--------|-------|
| `bypass`          | 8    | 0..1           | tog    | plugin bypass |
| `input_gain`      | 9    | 0..1000        | log,sr | input trim (G); app clamps to 0..10 |
| `output_gain`     | 10   | 0..1000        | log,sr | output trim (G); app clamps to 0..10 |
| `threshold`       | 16   | 0.00398..1     | log,sr | ceiling threshold (G) |
| `lookahead_ms`    | 19   | 0.1..20        | sr     | lookahead |
| `attack_ms`       | 20   | 0.25..20       | sr     | attack time |
| `release_ms`      | 21   | 0.25..20       | sr     | release time |
| `knee_db`         | 44   | -48..0         |        | knee smooth (dB) |
| `latency`         | 45   | —              |        | CTL_OUT, read for delay comp |

### `lsp.parametric_eq` — `para_equalizer_x16_stereo` (uid 5002076, 206 ports)

| Piano stable name | Port | Range             | Hint   | Notes |
|-------------------|------|-------------------|--------|-------|
| `bypass`          | 4    | 0..1              | tog    | plugin bypass |
| `input_gain`      | 5    | 0..10             | log,sr | input trim (G) |
| `output_gain`     | 6    | 0..10             | log,sr | output trim (G) |
| `band0_type`      | 28   | 0..11             | int    | filter type (0=off) |
| `band0_mode`      | 29   | 0..6              | int    | filter mode (RLC, etc.) |
| `band0_mute`      | 32   | 0..1              | tog    | band mute |
| `band0_freq`      | 33   | 10..24000         | log    | center frequency (Hz) |
| `band0_gain`      | 35   | 0.01585..63.0957  | log,sr | band gain (G) |
| `band0_q`         | 36   | 0..100            |        | quality factor |
| `latency`         | 205  | —                 |        | CTL_OUT, read for delay comp |

Band N (1..15) repeats with stride 11 from band 0: `type=28+N*11`,
`mode=29+N*11`, `mute=32+N*11`, `freq=33+N*11`, `gain=35+N*11`,
`q=36+N*11`. Band 0 is the only one wired in the first UI iteration; the
remaining bands default to `type=0` (off) so the EQ is flat by default.

## Runtime enumeration ✅

The port tables above are no longer taken from metadata sources alone — they
were validated by running `patches/ladspa_dump.cpp` against the host x86-64
build of the same patched sources (the Android aarch64 `.so` cannot be dlopen'd
on x86-64 without the Bionic linker; see PROGRESS.md open question #1). The
dump confirms 168 LADSPA descriptors and the exact port indexes/ranges/hint
bits recorded in the per-plugin tables above. Audio ports are `0`=in L,
`1`=in R, `2`=out L, `3`=out R for all three plugins.
