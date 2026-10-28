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
Filter's user controls intentionally start at 1 kHz/Q=1 rather than generated
LADSPA filter defaults (3428.93 Hz/Q=0): bounded, conventional starting values
for a live instrument rack. Filter type starts at enum 0 (Lo-pass); safe mode
defaults are explicit in adapter so LADSPA's generated defaults cannot enable
FFT/FIR/decramping. Phaser and Flanger feedback controls are coupled to hidden
feedback-enable switches (ports 34 and 26 respectively); zero amount forces
switch off, positive amount enables feedback. Their descriptor feedback amount
defaults (0.445625) are overridden to zero.
Current host tests exercise all catalog instrument slots at 44.1 and 48 kHz,
block sizes 16/64/128/256, finite audio, bypass, and audible effect changes;
Clipper retains its latency-alignment regression coverage.

RT audit boundary: `LadspaEffect::prepare` owns all allocation and warmup.
Callback parameter application only writes mapped control floats and calls
`run`; supported mapped parameters avoid FFT/FIR and oversampled modes and do
not change scratch sizes. Plugin control updates are still executed by LSP
`run()` and must remain within the pinned realtime-safe operating modes.
