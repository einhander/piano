# UI redesign

Baseline: `e1325cd644119381a462cc209bb88ea9f5bfe9ff`; branch `feature/ui-redesign`.

## Baseline control inventory

- Main (programmatic): running/underruns, MIDI status and device picker/disconnect;
  C4/D4/E4 touch-down/up/cancel; Panic; Projects; About (App Log copy/save/clear,
  log folder); Settings; Instruments; Master Effects; Sequencer; Exit; Multi;
  MP3 Record/Stop and busy/exit states.
- Settings: recording folder SAF picker and recording lock; multiple SF2 browse,
  individual unload, unload all, count; polyphony, gain, reverb, chorus,
  interpolation, auto buffer and buffer size; pitch-bend channel dialog.
- Master Effects: ordered EQ/compressor/limiter, enabled/bypassed/unavailable,
  editor navigation/tabs, every writable parameter, EQ analyzer toggle/plot,
  compressor/limiter meters. Instrument Effects shares XML: channel chooser,
  rack switch, effect switches, expand/configure, every parameter, MIDI Learn,
  CC mappings and mapping editing/unlearn.
- Instruments: all 16 channel rows, channel 10 drums, selection/preset search,
  held-note indicators, effects buttons, instrument-set selector, file sets and
  Reset from MIDI.
- Multi: fixed Enable Multi and Close; 16 mask checkboxes; independent 0–127
  channel volumes, available even with Multi off. Immediate callbacks, no Apply.
- Sequencer: Add/Delete, complete filename, File/Chord, chord notes/capture,
  trigger and Learn/Unlearn (Note/CC/PB/PC/SysEx, ten-second timeout), Test/Stop,
  Record/Stop, Import/Export, channel or Tracks (multitrack only), From file /
  As recorded, Loop, tempo commit on focus loss; refresh restores live states.

## Scope and preservation

Light theme; shared purple/teal/neutral tokens, outlined white cards, quiet text
buttons and white toolbar overlay. No dependencies, native, JNI, service, MIDI,
project/model, DSP, persistence, scheduling or polling changes intended.

Validation is limited to focused static checks in this lane. Parent owns final
debug build and JVM unit tests. No device/emulator available: no visual or
on-device behavior verification claimed. `caveman-commit` skill requested before
committing; tool access denied. Git hooks remain enabled.

## Device checklist (pending)

## Implemented groups and static review

- Settings: 12dp card insets, tighter spacing, single existing dynamic polyphony
  and gain labels (no duplicated static label), 48dp sliders/switches/spinner,
  selectable untruncated SF2 names and explicit destructive unload. SAF and all
  settings callbacks/conversions untouched. XML parse and baseline-ID subset
  checks passed for settings/SF2/effects layouts.
- Effects/shared Instrument Effects: white toolbar title/navigation overlay,
  compact cards with Configure beside switch, truthful state below; all writable
  parameters retained, 48dp controls, smaller editor heading and resource-based
  analyzer text. Shared container/status IDs retained for both activities.
  Static review: editor extras, enabled conditions, parameter conversion,
  persistence, expansion, MIDI mapping callbacks, meter/analyzer lifecycle and
  polling unchanged.
- Multi: one compact row per channel (mask, slider, value); Enable and Close
  fixed, full existing explanation under Help. Mask checked state, disabled
  conditions and `fromUser` immediate volume callbacks retained verbatim.
  No instrument query added. Instruments: two-line channel/program groups with
  fully wrapping names, adaptive row height, existing indicator and 48dp Effects.
  Set/reset controls retained. XML/ID check passed; selection, preset callbacks,
  channel indices, rack states and polling reviewed unchanged.

Use 360dp portrait, landscape and font scale 1.3; check wrapping, scroll reach,
Back/Close, white toolbar icons, TalkBack labels/status, touch targets. Exercise
every control listed above. Specifically verify note-off on touch cancellation,
MIDI disconnect/reconnect, MP3 recording busy/exit states, recording-folder lock,
2+ SF2 load/unload and restart restoration, all parameter ranges/persistence,
effect meters/analyzer lifecycle, all 16 instrument channels and file sets,
MIDI Learn/CC mapping, Multi-off volume and preserved masks, multitrack channel
visibility, Chord/file actions, ten-second learn timeout, tempo focus loss and
active Test/Record state after refresh. Listen for stuck notes, audio interruption,
new underruns or MIDI delay during navigation.
