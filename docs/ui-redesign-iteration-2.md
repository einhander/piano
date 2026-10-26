# UI redesign — iteration 2

Baseline: `dd7644eee8684826de2126f2ae7e5013e01bd11a`, branch
`feature/ui-redesign`. Root iteration instruction, AGENTS, architecture and
real-time rules reread before changes. Baseline build/tests owned by parent;
no concurrent Gradle runs. No push from this lane.

## Function → existing View → handler → intended placement (before edits)

| Function | Existing View | Existing handler/state | New placement |
|---|---|---|---|
| Engine/underruns | `statusText` | existing worker updates, polite live region | compact status card, unchanged text source |
| MIDI selection/disconnect | `midiDeviceButton` | existing device dialog/connect/disconnect | sole visible device row, outlined |
| MIDI status mirror | `midiStatusText` | `refreshMidiStatus`, device callbacks | retained field, GONE to avoid duplicate name |
| Instrument/effect/sequencer navigation | `instrumentsButton`, `effectsButton`, `sequencerButton` | existing Activity intents | tonal two-column navigation |
| Multi | `multiChannelButton` | existing dialog, immediate mask/volume callbacks | fourth tonal navigation tile |
| Diagnostic notes | `c4Button`, `d4Button`, `e4Button` | exact DOWN/UP/CANCEL and click listeners | three outlined keys |
| Projects/Settings/About-log/Exit | existing four buttons | existing dialogs/intent/`beginExplicitExit` | compact outlined utility grid, no menu indirection |
| MP3 Record/Panic | `recordButton`, `panicButton` | existing start/stop/busy/selected/exit logic; `dispatchMidi` | same fixed footer |
| Slot filename/chord | `nameText` | existing full-name dialog | single-line name header |
| Mode/trigger | `modeBtn`, `keyLabel` | mode callback + refresh; read-only trigger | mode and explicitly labeled trigger row |
| Channel/Tracks | `channelSpinner`, `tracksBtn` | same spinner mapping; async ≥2 track names, current store lookup | channel row; original Tracks button in compact secondary group |
| Loop/Tempo | `loopCheck`, `tempoEdit` | same check callback, focus-loss save, 20–300 parser | separate Loop and labeled Tempo/value/BPM in same responsive row |
| Test/Record/Learn | `testBtn`, `recordBtn`, `learnBtn` | same callbacks/maps/timeout | visible outlined/tonal primary row |
| Import/Export/Delete | original buttons | same callbacks/recording guards | compact secondary actions, original instances retained |
| Add slot | `addBtn` | same store insertion/refresh | outlined button after list |
| Recording folder | `tvRecordingFolder`, `btnRecordingFolder` | SAF + `withRecordingIdle` | compact group |
| SF2 | Browse/list/header/empty/count/unload Views | `updateSf2List`, per-`sf` unload, unload all | title + Browse row; name + Unload rows; count + Unload all row |
| Polyphony/Gain | existing value labels/SeekBars | same ranges and stop-tracking application | label immediately above full-width 48dp slider |
| Reverb/Chorus | existing state TextViews + switches | unchanged checked listeners | state + switch in one row |
| Interpolation/Buffer/MIDI channels | existing labels/spinner/slider/switch/button | unchanged selection/application callbacks | tighter information spacing; all IDs retained |
| Effects/Instruments/Multi polish | existing Configure/tabs/channel Effects/Help | existing navigation and state callbacks | local outlined affordance only where needed |

## Guardrails

No music, MIDI, JNI, service, model, persistence, polling, threading, dependency
or release changes. Main code after `setContentView(root)` must remain identical.
Existing global colors, `card_frame` and `ui_action_frame` remain untouched:
device contrast verification unavailable. Local navigation drawables reuse tokens.
Commit skill requested before commits; access denied. Hooks remain enabled.

## Verification / limitations

Focused static checks and sequential compile checks will be recorded per group.
Live implemented: tonal outlined primary navigation, outlined secondary controls
and diagnostic keys, compact 48dp app bar, fewer section headings, single visible
MIDI selector. `midiStatusText` remains constructed/updated but GONE. Original
Record/PANIC footer and states retained. Static checks passed: byte-identical
Main tail after `setContentView(root)`, all 12 diagnostic touch/click phases,
hidden duplicate View, all resource XML parses, `git diff --check`. Local styles
provide bounded ripple plus explicit disabled background/text; no global token
or original shared drawable edits. Compile pending parent's baseline completion.

Sequencer implemented: removed duplicate page heading; 8dp card insets and gaps;
original secondary action Views moved into a More/Less disclosure (not a menu).
Test/Record/Learn stay immediately visible. Trigger text is explicitly read-only
and labeled. Loop remains independent of a labeled Tempo/value/BPM group.
Original tempo parser/focus-loss listener retained; action handlers first clear
edit focus, invoking that same listener before their original action. Done key
also finishes focus. Channel label hides with spinner under original multitrack
condition; original async Tracks button and current-store guards remain intact.
All original state maps and restoration methods retained. Compile deferred until
baseline completion; focused preservation checks recorded below.

Settings implemented XML-only: recording-folder heading/action share a row;
SoundFont heading/Browse and count/Unload all each share rows; compact loaded
list and explicit outlined per-SF2 Unload. Reverb/Chorus use existing formatted
state labels beside original switches. Removed redundant static interpolation/
buffer labels (existing formatted values already include names); value labels
sit immediately above full-width 48dp sliders. All Settings Kotlin, including
SAF locking and specific-SF2 unload binding, unchanged. Static XML checks passed
for exact ID sets, View types and min/max/progress/checked defaults across all
three edited layouts; all XML parses and diff whitespace checks passed.

Consistency polish: original effect tabs/Configure and instrument-channel Effects
buttons receive local outlines; selected master tab is tonal. Instrument Effects
channel/mapping/configure/Learn buttons receive the same affordance without
callback or parameter changes. Multi Help gains bounded ripple/outline; compact
Ch N labels fit original 94dp checkbox column (full accessibility descriptions
retained). Volume, mask, enable/disable, polling and rack-state logic unchanged.

Focus follow-up: slot actions and Add cell finish whichever tempo edit currently
has panel focus, including a different cell, through its existing focus-loss
listener. Numeric field uses 8dp horizontal padding to leave digit space at large
font scale. Exit retains local disabled text selector rather than overwriting it
with a flat color.

## Concrete static evidence

- All resource XML parsed; duplicate default-resource names checked; new Kotlin
  string/color/drawable references resolve; `git diff --check` passes.
- Exact baseline ID sets, View types and min/max/progress/checked defaults retained
  in Settings, SF2 row and Sequencer XML.
- Main tail after `setContentView(root)` byte-identical; diagnostic DOWN/UP/CANCEL
  and click paths each retained for notes 60/62/64; duplicate MIDI field GONE.
- Sequencer refresh/backfill, value readers, setting/focus-save handlers,
  record/test-state methods byte-identical. Nine original button callback bodies
  unchanged after excluding added UI-only focus completion. Original parameter
  ranges, track lookup/guards, trigger handling and ten-second timeout retained.
- SettingsActivity and SequencerActivity entirely unchanged. EffectsActivity,
  InstrumentActivity and InstrumentEffectsActivity differ only by style calls.
  Multi routing/volume/enable blocks identical. Explicit native/service/repository
  call sequences identical in all modified existing Kotlin files.
- Original global token/theme files and `card_frame`/`ui_action_frame` unchanged.
  User-owned untracked instruction/test_files untouched and never staged.

## Changed files

- Live: `MainActivity.kt`; local `ui/UiControlStyle.kt`,
  `drawable/ui_control_outline.xml`, `drawable/ui_control_tonal.xml`,
  `color/ui_control_text.xml`, `color/ui_control_danger_text.xml`.
- Sequencer: `ui/MidiFilesPanel.kt`, `layout/activity_sequencer.xml`.
- Settings: `layout/activity_settings.xml`, `layout/sf2_list_item.xml`,
  `values/ui_control_styles.xml`.
- Minimal polish: `EffectsActivity.kt`, `InstrumentEffectsActivity.kt`,
  `InstrumentActivity.kt`, `ui/MultiChannelDialog.kt`.
- Labels: `values/strings.xml`. Documentation: this file only.

## Pending device regression (not executed)

| Area | Required device checks |
|---|---|
| Live | MIDI reconnect/disconnect/name, status/underruns, held notes and cancellation, all navigation/log/projects/Exit, fixed Panic, MP3 start/stop/cancel/error/busy states |
| Sequencer | File/Chord, every trigger type and learn/unlearn/timeout, independent Loop/BPM focus loss/Done/action transition, Test/Record refresh/rotation, More/Less, import/export/delete guard, multitrack Tracks/channel visibility and current mapping |
| Settings | Folder SAF lock while recording/saving, 2+ SF2 load, specific/all unload, restart restoration, every synth setting and original application moment |
| Other screens | All 16 instrument channels/drums/sets/presets/indicators/channel Effects; complete effect parameters/tabs/bypass/meters/analyzer/CC mapping; Multi-off volume and preserved mask |
| Visual/accessibility | 360–430dp, landscape, font 1.3–1.5, numeric keyboard, long MIDI/SF2 names, system dark theme, ripple/disabled contrast, TalkBack and 48dp targets |
| Audio | No interruption, lost SoundFonts, stuck notes, new underruns or MIDI delay while navigating |

No screenshots captured: no device/emulator available. Static checks do not prove
rendered geometry, input focus traversal or live performance. Compile checks have
not been run in this lane while awaiting parent's baseline-complete signal;
parent must perform final Gradle validation before push.

Parent owns final full debug build/unit tests. No device/emulator known: no
screenshots or visual verification claimed. Pending device matrix: 360–430dp,
font scale 1.3–1.5, landscape, keyboard open, long filenames, system dark theme,
TalkBack, touch targets and live audio/MIDI/recording regression.
