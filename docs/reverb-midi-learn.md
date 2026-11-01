# FluidSynth Reverb MIDI Learn

Settings exposes MIDI Learn for the shared FluidSynth reverb enable switch,
room size, damping, width, and level. These bindings are global: they affect
all instrument sets, not the currently selected channel.

Use MIDI Learn beside a control and move a MIDI CC controller within ten
seconds. Existing binding summaries offer editing and unlearning. Mapping
options include MIDI source/channel, raw range, inversion, and pass-through
or consumption; continuous controls use the existing soft-takeover mechanism.
Ranges come from native reverb descriptors. Existing CC and sequencer-trigger
conflicts require confirmation before replacement. Leaving Settings cancels
an owned pending capture.

The existing process-level effect CC controller persists and applies mappings;
no additional MIDI receiver or audio DSP stage is introduced.

Device checks: learn each of the five controls, verify correct reverb target,
restart and verify persistence, test soft takeover after manual edits,
cancel/timeout, unlearn, and reject a conflicting assignment without changing
the original mapping or sequencer cells. Physical-device Learn remains a
manual acceptance check.
