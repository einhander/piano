# Chord Mode — Progress Tracker

Branch: `feature/chord-mode`
Design confirmed with user on 2026-08-25.

## Goal

Add a "chord" cell mode alongside the existing sequencer file mode. A chord
cell holds a single chord (a set of notes with velocities) bound to a learned
MIDI trigger. While the trigger is held, the chord's notes sound; releasing the
trigger stops them (gate semantics). The trigger's velocity scales the velocity
of all chord notes.

## Confirmed behaviour (user answers)

| Question | Answer |
|----------|--------|
| Attack / velocity | The learned button's velocity scales the velocity of all chord notes. |
| Strum timing at playback | (a) All notes play simultaneously as a block — record timings are ignored; only the note set + per-note velocity is kept. |
| Re-trigger / loop | Re-trigger (restart the chord) on a fresh press; **no** loop. |

## Architecture decision

Chord playback does **not** touch the real-time audio callback. It reuses the
existing `service.sendMidiMessage(...)` path (→ SynthCmdQueue → `processOneMidi`
in the audio callback), exactly like live unmapped notes. The chord cell stores
its notes inline in the Kotlin model (`chordNotes: List<ChordNote>`), **not** as
a `.mid` file, so no new native API (no `getRecordedEvents` JNI) is needed.

Recording collects the chord notes directly in Kotlin by intercepting live MIDI
during the chord-cell recording window (reuses `MidiRecordSession` + a
chord-specific collector), then writes them into the cell on stop.

Legend: ✅ done · 🟡 in progress · ⬜ not started

---

## Milestone 1 — Data model ✅

| Item | Status | Notes |
|------|--------|-------|
| `ChordNote(note, velocity)` serializable | ✅ | `MidiFileMapping.kt` |
| `SequencerCell.mode` (MODE_FILE / MODE_CHORD) | ✅ | default MODE_FILE (back-compat) |
| `SequencerCell.chordNotes` list | ✅ | empty for MODE_FILE |
| Serialization survives save/load | ✅ | kotlinx default values; old cells → MODE_FILE |

## Milestone 2 — Recording ✅

| Item | Status | Notes |
|------|--------|-------|
| Chord-note collector during record window | ✅ | `ChordRecorder` collects last velocity per (channel, note) from live MIDI |
| `startChordRecording` / `stopChordRecording` | ✅ | stop folds into `chordNotes` (unique notes, last velocity) |
| Plumb into `SequencerActivity` record flow | ✅ | MODE_CHORD cell → chord recorder instead of MIDI file writer |

## Milestone 3 — Playback gate ✅

| Item | Status | Notes |
|------|--------|-------|
| `onNoteOn` MODE_CHORD → `playChord` | ✅ | noteOn all chord notes; velocity scaled by trigger velocity; remember pressed set |
| `onNoteOff` MODE_CHORD → `stopChord` | ✅ | noteOff all pressed notes |
| Re-trigger on fresh press | ✅ | stop then play (no loop) |
| Channel source | ✅ | chord notes keep their recorded channel; trigger velocity scales |

## Milestone 4 — UI ✅

| Item | Status | Notes |
|------|--------|-------|
| Per-cell mode toggle (File / Chord) | ✅ | long-press cell → mode switch |
| Record into a chord cell writes a chord | ✅ | record button works for both modes |
| Cell visual shows mode | ✅ | "♪" chord marker vs file name |

## Milestone 5 — Build & verify ✅

| Item | Status | Notes |
|------|--------|-------|
| `./build.sh debug` → BUILD SUCCESSFUL | ✅ | |
| `./gradlew :app:testDebugUnitTest` | ✅ | ChordRecorder unit test added |
| Commit + push | ✅ | |

## Open / future

- Chord strum playback (option b — replay recorded rhythm) — deferred; user chose (a).
- Per-note attack shaping beyond velocity scaling — FluidSynth has no per-note attack param.
