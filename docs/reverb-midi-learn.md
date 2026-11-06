# FluidSynth Reverb MIDI Learn

Settings exposes MIDI Learn for the shared FluidSynth reverb enable switch,
room size, damping, width, and level. These bindings are global: they affect
all instrument sets, not the currently selected channel.

Use MIDI Learn beside an unassigned control and move a MIDI CC or Pitch Bend
controller within ten seconds. Assigned controls offer Edit; the editor contains
Learn for reassignment and Unlearn for removal. Mapping
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

## MIDI Learn diagnostic checklist

Report **observation → hypothesis → discriminating check → result**. Do not assume
that a pad emits notes or a knob emits CC based on its physical appearance.

- Record the current build and log timestamps; compare known-good/failing builds
  with the same mapping, parameter, controller and physical movement.
- Identify the incoming message type, source/device, physical USB port, MIDI
  channel and data number (when applicable). Preserve raw identity for matching;
  the compact UI label intentionally hides serial/opaque identifiers.
- Record observed input min/max and resolution (CC 0..127, Pitch Bend 0..16383).
  An observed minimum is not proof of the controller's physical limit.
- Check the full route: parser → source/port policy → learn/mapping selection →
  soft takeover → cached target value → native setter → UI slider/label. Separate
  consumed input, dispatched values, setter rejection and visual-only failure.
- Compare mapping range/inversion with native parameter descriptors and current
  value. Test pickup near both endpoints, crossing current value, adjacent input
  after pickup, and reset after a manual edit. Preserve full input resolution
  after pickup; an exact endpoint requirement can make valid controls unusable.
- For a parameter-specific failure, compare a working parameter and inspect
  descriptor units, linear/log/discrete conversion and hidden DSP enable gates.
  A changing slider does not prove audible DSP change; a stationary slider does
  not prove the setter was never called.
- For Learn/Edit changes, test fresh learn, same-control relearn, different-control
  relearn, conflict rejection, cancel/timeout, Unlearn, persistence and activity
  recreation. Preserve the old mapping until replacement succeeds.

Use focused automated tests for pure matching/scaling/pickup and persistence.
Treat visible control movement and audible behavior as unverified until tested on
the device. Do not change unrelated audio settings to explain a MIDI UI symptom
without evidence connecting them.
