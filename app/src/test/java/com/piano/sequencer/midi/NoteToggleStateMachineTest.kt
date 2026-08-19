package com.piano.sequencer.midi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [NoteToggleStateMachine] — the per-note toggle logic
 * that distinguishes loop (toggle) from one-shot (retrigger) behavior.
 *
 * No Android dependencies; runs on the JVM.
 */
class NoteToggleStateMachineTest {

    private fun fresh(): NoteToggleStateMachine = NoteToggleStateMachine()

    // ── loop = true (toggle mode) ──

    @Test
    fun loopPressStarts() {
        val sm = fresh()
        assertEquals(NoteToggleStateMachine.Result.TOGGLE_ON, sm.noteOn(60, loop = true))
        assertTrue(sm.isPlaying(60))
    }

    @Test
    fun loopPressAfterReleaseStops() {
        val sm = fresh()
        sm.noteOn(60, loop = true)
        sm.noteOff(60)
        assertEquals(NoteToggleStateMachine.Result.TOGGLE_OFF, sm.noteOn(60, loop = true))
        assertFalse(sm.isPlaying(60))
    }

    @Test
    fun loopPressAfterStopStartsAgain() {
        val sm = fresh()
        sm.noteOn(60, loop = true)
        sm.noteOff(60)
        sm.noteOn(60, loop = true) // TOGGLE_OFF
        sm.noteOff(60)
        assertEquals(NoteToggleStateMachine.Result.TOGGLE_ON, sm.noteOn(60, loop = true))
        assertTrue(sm.isPlaying(60))
    }

    // ── loop = false (one-shot / retrigger mode) ──

    @Test
    fun oneShotPressStarts() {
        val sm = fresh()
        assertEquals(NoteToggleStateMachine.Result.TOGGLE_ON, sm.noteOn(60, loop = false))
        assertTrue(sm.isPlaying(60))
    }

    @Test
    fun oneShotPressAfterReleaseRetriggerStarts() {
        val sm = fresh()
        sm.noteOn(60, loop = false)
        sm.noteOff(60)
        // Core bug fix: after release, fresh press re-triggers (TOGGLE_ON), not TOGGLE_OFF.
        assertEquals(NoteToggleStateMachine.Result.TOGGLE_ON, sm.noteOn(60, loop = false))
        assertTrue(sm.isPlaying(60))
    }

    @Test
    fun oneShotKeyRepeatIsIgnored() {
        val sm = fresh()
        sm.noteOn(60, loop = false)
        // Key repeat: note-on without note-off between → IGNORED.
        assertEquals(NoteToggleStateMachine.Result.IGNORED, sm.noteOn(60, loop = false))
        assertTrue(sm.isPlaying(60))
    }

    // ── stopPlaying resets state ──

    @Test
    fun stopPlayingResetsOneShot() {
        val sm = fresh()
        sm.noteOn(60, loop = false)
        sm.noteOff(60)
        // After stopPlaying, next press is TOGGLE_ON again.
        sm.stopPlaying(60)
        assertEquals(NoteToggleStateMachine.Result.TOGGLE_ON, sm.noteOn(60, loop = false))
        assertTrue(sm.isPlaying(60))
    }

    // ── Mode switch (documents controller responsibility) ──

    @Test
    fun modeSwitchRawSmBehavior() {
        // Press with loop=false → SM thinks playing.
        val sm = fresh()
        sm.noteOn(60, loop = false)
        sm.noteOff(60)
        // Switch to loop=true: raw SM sees isPlaying=true, so returns TOGGLE_OFF.
        // In practice, MidiFileTriggerController.onSettingChanged calls
        // stopPlaying(note) when loop changes, preventing this stale state.
        assertEquals(NoteToggleStateMachine.Result.TOGGLE_OFF, sm.noteOn(60, loop = true))
        assertFalse(sm.isPlaying(60))
    }

    // ── isPlaying reflects state ──

    @Test
    fun isPlayingAfterToggleOn() {
        val sm = fresh()
        sm.noteOn(60, loop = true)
        assertTrue(sm.isPlaying(60))
    }

    @Test
    fun isPlayingAfterToggleOff() {
        val sm = fresh()
        sm.noteOn(60, loop = true)
        sm.noteOff(60)
        sm.noteOn(60, loop = true) // TOGGLE_OFF
        assertFalse(sm.isPlaying(60))
    }

    @Test
    fun isPlayingAfterStopPlaying() {
        val sm = fresh()
        sm.noteOn(60, loop = false)
        sm.stopPlaying(60)
        assertFalse(sm.isPlaying(60))
    }

    // ── B4: press() for continuous triggers (CC / pitch bend) ──
    // Key space: NOTE 0-127, CC 128+cc, PITCH_BEND 256. Continuous triggers
    // have no release event, so press() treats every call as a fresh press —
    // key-repeat filtering (same-value events) happens in the controller.

    @Test
    fun pressTogglesForLoop() {
        val sm = fresh()
        assertEquals(NoteToggleStateMachine.Result.TOGGLE_ON, sm.press(135, loop = true))
        assertTrue(sm.isPlaying(135))
        assertEquals(NoteToggleStateMachine.Result.TOGGLE_OFF, sm.press(135, loop = true))
        assertFalse(sm.isPlaying(135))
        assertEquals(NoteToggleStateMachine.Result.TOGGLE_ON, sm.press(135, loop = true))
        assertTrue(sm.isPlaying(135))
    }

    @Test
    fun pressRestartsForOneShot() {
        val sm = fresh()
        assertEquals(NoteToggleStateMachine.Result.TOGGLE_ON, sm.press(135, loop = false))
        // Every press (re)starts — there is no release event for continuous triggers.
        assertEquals(NoteToggleStateMachine.Result.TOGGLE_ON, sm.press(135, loop = false))
        assertTrue(sm.isPlaying(135))
    }

    @Test
    fun pressIndependentFromNoteState() {
        val sm = fresh()
        assertEquals(NoteToggleStateMachine.Result.TOGGLE_ON, sm.noteOn(60, loop = true))
        assertEquals(NoteToggleStateMachine.Result.TOGGLE_ON, sm.press(128 + 7, loop = true))
        assertTrue(sm.isPlaying(60))
        assertTrue(sm.isPlaying(135))
        // Note key-repeat detection is untouched by press()
        assertEquals(NoteToggleStateMachine.Result.IGNORED, sm.noteOn(60, loop = true))
    }

    @Test
    fun stopPlayingResetsPressState() {
        val sm = fresh()
        assertEquals(NoteToggleStateMachine.Result.TOGGLE_ON, sm.press(256, loop = true))
        sm.stopPlaying(256)
        assertFalse(sm.isPlaying(256))
        // Next press after free/reset is TOGGLE_ON again
        assertEquals(NoteToggleStateMachine.Result.TOGGLE_ON, sm.press(256, loop = true))
        assertTrue(sm.isPlaying(256))
    }

    @Test
    fun pitchBendKeyIndependentFromCCAndNote() {
        val sm = fresh()
        assertEquals(NoteToggleStateMachine.Result.TOGGLE_ON, sm.press(256, loop = true))
        assertEquals(NoteToggleStateMachine.Result.TOGGLE_ON, sm.press(128, loop = true))
        assertEquals(NoteToggleStateMachine.Result.TOGGLE_ON, sm.noteOn(0, loop = true))
        assertTrue(sm.isPlaying(256))
        assertTrue(sm.isPlaying(128))
        assertTrue(sm.isPlaying(0))
    }
}