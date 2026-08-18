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
}