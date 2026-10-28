# LADSPA descriptor enumeration (Stage A audit)

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
`q=36+N*11`. All bands are exposed as parameters; each defaults to
`type=0` (off), so each remains independently disabled and the EQ is flat by default.

## Runtime enumeration ✅

The port tables above are no longer taken from metadata sources alone — they
were validated by running `patches/ladspa_dump.cpp` against the host x86-64
build of the same patched sources (the Android aarch64 `.so` cannot be dlopen'd
on x86-64 without the Bionic linker; see PROGRESS.md open question #1). The
dump confirms 168 LADSPA descriptors and the exact port indexes/ranges/hint
bits recorded in the per-plugin tables above. Audio ports are `0`=in L,
`1`=in R, `2`=out L, `3`=out R for all three plugins.

## Stage A additions: compiled host-bundle verification

The current checkout pins LSP meta tag `1.2.34` at
`92e81191771d23c87f952a2ad21d2a8b8cd1cf45`. Its `modules.mk` pins
`lsp-plugins-art-delay` `1.0.35`, `lsp-plugins-chorus` `1.0.16`, and
`lsp-plugins-clipper` `1.0.18`; fetched module commits are recorded in
`STAGE_A_AUDIT.md`. Existing EQ/compressor/limiter mappings above are unchanged.

Compiled host x86-64 bundle dump confirms these descriptors (all full URI
labels, 4 audio ports 0..3, counts shown):

| Effect | Label | UID | Ports |
|---|---|---:|---:|
| Chorus Stereo | `chorus_stereo` | 5002315 | 182 |
| Artistic Delay Stereo | `art_delay_stereo` | 5002171 | 741 |
| Clipper Stereo | `clipper_stereo` | 5002285 | 58 |

### Chorus Stereo controls

Ranges/defaults are the dumper's 48 kHz interpretation of compiled LADSPA
hints. `DEFAULT_NONE` initializes to zero (explicitly shown). Omitted ports are
additional mode/LFO/graph controls; exact complete enumeration comes from
`ladspa_dump.cpp` against the host bundle.

| Port | Control | Range | Default |
|---:|---|---:|---:|
| 4 | Bypass (toggle) | 0..1 | 0 |
| 13 | Rate (Hz, log) | 0.01..20 | 0.447214 |
| 20 | Number of voices (integer) | 0..14 | unspecified → 0 |
| 21 | Depth (ms) | 0.1..20 | 5.075 |
| 22 | Crossfade (%) | 0..50 | 0 |
| 39 | Feedback on (toggle) | 0..1 | 0 |
| 40 | Feedback gain (G) | 0..0.891251 | 0.445625 |
| 44 | Input gain (G) | 0..1000 | 1 |
| 45 | Dry amount (G) | 0..10 | 1 |
| 46 | Wet amount (G) | 0..10 | 7.5 |
| 47 | Dry/Wet balance (%) | 0..100 | 100 |
| 48 | Output gain (G) | 0..1000 | 1 |
| 181 | latency (output) | — | unspecified |

### Artistic Delay Stereo controls

Delay exposes 16 lines; each line repeats line-1 layout at stride 43 ports.
Port map below records global controls and line 1 only; all 741 ports were
enumerated and are present in the captured host dump.

| Port | Control | Range | Default |
|---:|---|---:|---:|
| 4 | Bypass (toggle) | 0..1 | 0 |
| 5 | Delay line selector (integer) | 0..15 | 0 |
| 6 | Maximum possible delay selector (integer) | 0..16 | unspecified → 0 |
| 9 | Dry amount (G) | 0..10 | 1 |
| 10 | Wet amount (G) | 0..10 | 1 |
| 11 | Dry/Wet balance (%) | 0..100 | 100 |
| 12 | Dry enable (toggle) | 0..1 | 1 |
| 13 | Wet enable (toggle) | 0..1 | 1 |
| 15 | Feedback (toggle) | 0..1 | 1 |
| 16 | Feedback gain (G) | 0..1 | 1 |
| 17 | Output gain (G) | 0..1000 | 1 |
| 52 | Delay 1 on (toggle) | 0..1 | 0 |
| 55 | Delay 1 reference (integer) | 0..16 | 0 |
| 56 | Delay 1 reference multiplier (integer) | 0..1000 | 1 |
| 58 | Delay 1 bar fraction (integer) | 0..2 | 1 |
| 59 | Delay 1 bar denominator (integer) | 1..64 | 16.75 → rounded 17 |
| 60 | Delay 1 bar multiplier (integer) | 0..1000 | 1 |
| 63 | Delay 1 time addition (s) | 0..256 | 0 |
| 76 | Delay 1 gain (G) | 0..10 | 1 |
| 78 | Delay 1 feedback enable (toggle) | 0..1 | 0 |
| 79 | Delay 1 feedback gain (G) | 0..1 | 0 |
| 740 | latency (output) | — | unspecified |

### Clipper Stereo controls

| Port | Control | Range | Default |
|---:|---|---:|---:|
| 4 | Bypass (toggle) | 0..1 | 0 |
| 5 | Input gain (G) | 0..1000 | 1 |
| 6 | Output gain (G) | 0..1000 | 1 |
| 7 | Enable input LUFS limitation (toggle) | 0..1 | 1 |
| 8 | Input LUFS limiter threshold | -36..0 | -9 |
| 13 | Clipping threshold (dB) | -48..0 | 0 |
| 14 | Boosting mode (toggle) | 0..1 | 1 |
| 15 | Dithering mode (integer) | 0..8 | 0 (None) |
| 17 | Overdrive protection (toggle) | 0..1 | 1 |
| 18 | ODP threshold (dB) | -12..0 | -3 |
| 19 | ODP knee (dB) | 0..6 | 1.5 |
| 20 | ODP reactivity (ms, log) | 1..200 | 53.183 |
| 21 | Clipper enable (toggle) | 0..1 | 1 |
| 22 | Clipper sigmoid function (integer) | 0..10 | unspecified → 0 |
| 23 | Clipper sigmoid threshold (G, log) | 0..1 | 0.75 |
| 24 | Clipper DC offset (%) | -100..100 | 0 |
| 25 | Clipper DC compensate (toggle) | 0..1 | 1 |
| 26 | Clipper sigmoid pumping (dB) | -12..12 | 0 |
| 28 | Stereo link (%) | 0..100 | 50 |
| 57 | latency (output) | — | unspecified |

Clipper defaults LUFS limitation and ODP **on**; disable those explicitly for
any intended musical-drive configuration. Default dither is None. Do not infer
that a sigmoid default of 0 means bypass; port 21 is separately enabled.

`ladspa_dump.cpp` emits LADSPA-hint-derived defaults (sample-rate scaling,
logarithmic interpolation, integer rounding and toggle normalization) at
48 kHz. It decodes masked `DEFAULT_*` enum values, not individual bits;
`DEFAULT_NONE` is marked unspecified. Focused formula checks live in
`patches/ladspa_hint_defaults_test.cpp`.

## Stage B/C native instrument inserts

Master `kBindings[]` remains exactly three entries. Instrument indices 0–2 and master catalog remain EQ/Compressor/Limiter;
instrument-only indices 3/4 are `lsp.chorus` (`chorus_stereo`) and
`lsp.clipper` (`clipper_stereo`). Compiled-host-verified controls exposed:
The instrument pending parameter banks grow by two slots × 16 channels × 512
entries × (`atomic<float>` + `atomic<bool>`), approximately 80 KiB on Android.

| ID / parameter | Port | Range / default | Meaning |
|---|---:|---|---|
| Chorus bypass | 4 | 0..1 / 0 | toggle |
| Rate | 13 | .01..20 Hz / .447214 | log |
| Voices | 20 | 0..14 / 0 | integer |
| Depth | 21 | .1..20 ms / 5.075 | |
| Feedback | 40 | 0..0.891251 / 0 | linear gain; feedback enable port 39 forced OFF |
| Mix | 47 | 0..100% / 100 | balance; dry/wet and gains retain compiled defaults |
| Clipper bypass | 4 | 0..1 / 0 | toggle |
| Input drive | 5 | 1..10 / 1 | linear gain; changes slew ≤0.05 gain per callback block |
| Curve | 22 | 0..10 / 0 | integer enum (UI labels require LSP enum mapping) |
| Threshold | 13 | -48..0 dB / 0 | |
| Output gain | 6 | 0.01..1 / 0.5 | conservative linear gain |

Clipper LUFS(7), boosting(14), dither(15), and ODP(17) are explicitly set OFF;
clipper enable(21) remains ON. Clipper ODP reactivity (port 20) is hidden and
set to supported minimum 1 ms before warm-up; pinned DSP reports latency
unconditionally from this control even when ODP is disabled. Chorus oversampling (compiled port 8,
integer/toggled range 0..10) is explicitly set to zero. Chorus feedback is
disabled while compiled dry/wet amount defaults are preserved. New plugins
report no meter snapshots. Scratch output buffers are preallocated when
descriptor advertises `INPLACE_BROKEN`; new bindings validate audio port
directions/count. Missing descriptors remain unavailable. Clipper worker
prewarm validates finite integer latency in 0..128 before publication. Manager
keeps per-channel 128-frame dry rings warm while preserving legacy output before
first effective Clipper enable; merely instantiating/restoring a disabled insert
does not shift timing. The published Clipper runs continuously so bypass does
not stale its DSP state. Once enabled, delayed dry path persists through bypass
with a bounded 5 ms alignment transition. `getInstrumentEffectLatencyFrames`
reports measured frames (expected 22/24 for supported rates).
host runner is `tests/run_ladspa_instrument_dsp_test.sh` (override via
`LSP_LADSPA_SO`). Pinned compiled host reports 22/24 frames at 44.1/48 kHz with
port 20 at supported minimum. Adapter accepts and retains measured latency;
availability stays immutable after publication. Raw DSP test proves
nonlinear residual 0.04743, DC 0.00189, peak 0.12559 at 44.1 kHz with the
audited drive settings; at 48 kHz residual 0.05233, DC 0.01705, peak 0.12559.
Impulse onset matches reported 22/24-frame latency. Manager synthetic fixture
tests dry/wet alignment, warmed activation, bypass transitions and >128-frame
rejection. Android diagnostic artifact must not be shipped.
