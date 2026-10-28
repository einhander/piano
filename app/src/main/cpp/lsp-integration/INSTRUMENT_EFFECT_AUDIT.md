# Native LSP instrument effects

New instrument rack entries retain existing slot identities: Chorus 3 and
Clipper 4; Filter 5 (`lsp.filter`), Phaser 6 (`lsp.phaser`), Flanger 7
(`lsp.flanger`). Master slots 0–2 remain unchanged. Catalog order matches
`instrumentBindingForSlot`.

Descriptor evidence: compiled host LADSPA bundle at LSP 1.2.34,
`app/build/lsp-extra-audit/{5002255,5002325,5002235}.txt`; full port dumps
are build outputs, not committed. Labels/UID/ports: Filter 5002255 / 29 ports /
latency port 28; Phaser 5002325 / 96 / latency 95; Flanger 5002235 / 45 /
latency 44. Native bindings verify stereo ports 0–3 and bypass 4. New entries
are rejected unless descriptor port count and port roles match. Preparation
runs each plugin on zeroed preallocated worker buffers, requiring reported
latency exactly zero before publication. Do not remove this gate or assume
zero latency from LADSPA hints alone.

Ranges, port IDs, and names come from the generated descriptor dumps and
upstream metadata at pinned LSP 1.2.34 submodule HEAD `92e81191771d23c87f952a2ad21d2a8b8cd1cf45`.
The relevant child module revisions are Filter `edf1c655c13d3738b60d7c58ac51d952ab227672`, Phaser `0e0a7ff4b37088b181c9400022505b7c73d71138`, Flanger `b6e3aca12cf3c6df607a2d600a3b84b7163aae5d`. Phaser filter-count metadata default is 4 filters and LADSPA
uses zero-based value 3; type enum enumerates Lo-pass, Hi-pass, Lo-shelf,
Hi-shelf, Bell, Bandpass, Notch, Resonance, Ladder-pass, Ladder-rej, Allpass.

Only safe controls are mapped. Filter keeps IIR mode and decramping off;
Phaser pins HPF/LPF modes off and selects 4 filters; Flanger pins oversampling
off. Phaser/filter graph controls and flanger oversampling are not exposed.
Filter's cutoff intentionally starts at 1 kHz rather than generated LADSPA
default 3428.93 Hz. Q remains at upstream default 0. Filter type starts at
enum 0 (Lo-pass); safe mode
defaults are explicit in adapter so LADSPA's generated defaults cannot enable
FFT/FIR/decramping. Phaser and Flanger feedback controls are coupled to hidden
feedback-enable switches (ports 34 and 26 respectively); zero amount forces
switch off, positive amount enables feedback. Their descriptor feedback amount
defaults (0.445625) are overridden to zero.

Filter control contract was checked against the pinned module sources
`modules/lsp-plugins-filter/src/main/meta/filter.cpp` (`EQ_FILTER`) and
`modules/lsp-plugins-filter/src/main/plug/filter.cpp` (`decode_filter`), plus
compiled LADSPA descriptor `app/build/lsp-extra-audit/5002255.txt`. Existing
type port 20 values 0..10 already match upstream enum order: Lo-pass,
Hi-pass, Lo-shelf, Hi-shelf, Bell, Bandpass, Notch, Resonance, Ladder-pass,
Ladder-rej, Allpass. Frequency is port 23 (10..24000 Hz), width port 24
(0..12 octaves), gain port 25 (0.01585..63.0957 linear amplitude, nominal 1
= 0 dB), and Q port 26 (0..100, nominal 0). Native catalog exposes gain and
width as new stable IDs 363 and 364; existing cutoff/resonance/type IDs and
enum values remain unchanged. Gain is meaningful for shelves and Bell, while
cut/pass/reject and Allpass do not become gain controls merely because port is
available. Width pertains to band/ladder types; UI should conditionally enable
controls by type rather than represent them as universal parameters.

Compiled DSP probe covers all 11 types at 44.1/48 kHz. At 44.1 kHz with
1 kHz cutoff and gain=2: Lo-pass RMS at 100/1000/8000 Hz =
0.141184/0.070714/0.001732; Hi-pass = 0.001407/0.070714/0.139685;
Lo-shelf = 0.283083/0.199918/0.142647; Hi-shelf =
0.143386/0.200067/0.280398; Bell = 0.152150/0.282829/0.153575;
Bandpass = 0.054881/0.137545/0.070699; Notch center = 0; Allpass
preserves these tone RMS values to measurement precision. Other enum types
also produce finite nonzero output. The complete probe prints each reading;
these are property checks, not promised loudness levels.

Mix audit: pinned module metadata (`modules/lsp-plugins-{chorus,phaser,flanger}/src/main/meta/*.cpp`)
declares Chorus dry=1/wet=-6 dB (0.501187), Phaser dry=0/wet=1, and Flanger
dry=1/wet=-6 dB (0.501187); each declares Dry/Wet=100%. Source DSP in each
`src/main/plug/*.cpp` computes
`dryGain=(dryAmount*mix + 1-mix)*outputGain`,
`wetGain=wetAmount*mix*outputGain`, then sums processed/wet and dry paths.
The dry/wet catalog IDs correctly map directly to Chorus port 47, Phaser 41,
Flanger 34. Compiled LADSPA hints incorrectly synthesize Chorus/Flanger wet
port defaults as 7.5 despite metadata -6 dB; the former had been exposed as a
Chorus wet gain default too. Chorus catalog default now matches 0.501187 and
adapter pins hidden Flanger port 33 to same value. Phaser hidden amount hints
already match metadata. Existing persisted parameter IDs and values remain
untouched; no custom equal-power law or loudness compensation is applied.

Actual compiled-plugin adapter probe at 44.1/48 kHz feeds the same .1-amplitude
440 Hz signal, settles for 2 seconds, then measures 0/50/100% mix. Mix zero is
bit-exact dry and unity (RMS 0.070712 at 44.1 kHz, 0.070699 at 48 kHz) for all
three effects. RMS at 50/100%: Chorus .061020/.063553 and .064064/.069297;
Phaser .071972/.088527 and .085426/.109884; Flanger .073536/.080266 and
.066237/.066363, respectively. Differences are natural phase/delay correlation
and plugin modulation, not a constant-loudness promise. All wet endpoints are
modified signal without accidental 7.5x gain. Probe preserves zero-feedback
settings and existing feedback regressions.
Current host tests exercise all catalog instrument slots at 44.1 and 48 kHz,
block sizes 16/64/128/256, finite audio, bypass, and audible effect changes;
Clipper retains its latency-alignment regression coverage.

RT audit boundary: `LadspaEffect::prepare` owns all allocation and warmup.
Callback parameter application only writes mapped control floats and calls
`run`; supported mapped parameters avoid FFT/FIR and oversampled modes and do
not change scratch sizes. Plugin control updates are still executed by LSP
`run()` and must remain within the pinned realtime-safe operating modes.

## Instrument controls

Filter uses Frequency (not universally a cutoff), Q, and the unchanged 11 Type
codes. Gain is displayed in dB from native linear amplitude; Width is in octaves.
Pinned `filter.cpp::adjust_gain` excludes pass, notch, bandpass, and allpass;
the exposed RLC mapping therefore shows Gain for shelves, Bell, Resonance,
Ladder-pass, and Ladder-rej (codes 2, 3, 4, 7, 8, 9).
`filter_has_width` shows Width for Bandpass and both ladder types (5, 8, 9).
Type selection writes on the worker and rebuilds controls from a fresh native
snapshot. Hidden controls retain their values and MIDI mappings. Bell/shelves
are neutral at 0 dB; Allpass changes phase without changing amplitude.

Chorus, Phaser, and Flanger label the native balance as Dry/Wet: 0% is dry
input, 100% is the plugin's configured effect balance, not necessarily wet-only.
Phase and modulation can change level; no constant-loudness promise is made.
Chorus additionally exposes existing IDs 341/342 as Dry gain/Wet gain in dB
(native linear values unchanged; nominal defaults 0/-6 dB).
