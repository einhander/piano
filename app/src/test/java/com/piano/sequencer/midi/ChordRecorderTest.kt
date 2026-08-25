package com.piano.sequencer.midi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [ChordRecorder] — the process-level collector that folds a
 * chord-cell recording window into the final note set. Pure Kotlin (no Android),
 * runs on the JVM unit-test runtime.
 */
class ChordRecorderTest {

    @Test
    fun start_clearsPreviousNotes() {
        ChordRecorder.start()
        ChordRecorder.onNoteOn(0, 60, 100)
        assertEquals(1, ChordRecorder.stop().size)

        // Second window must not carry over the first chord's notes.
        ChordRecorder.start()
        val result = ChordRecorder.stop()
        assertTrue(result.isEmpty())
    }

    @Test
    fun lastVelocityWins_onRepeatedPress() {
        ChordRecorder.start()
        ChordRecorder.onNoteOn(0, 60, 50)
        ChordRecorder.onNoteOn(0, 60, 120)
        val chord = ChordRecorder.stop()
        assertEquals(1, chord.size)
        assertEquals(60, chord[0].note)
        assertEquals(120, chord[0].velocity)
    }

    @Test
    fun strumAndSimultaneous_bothProduceSameSet() {
        // Sequential press (strum) and simultaneous press yield the same final
        // set — timing is intentionally discarded.
        ChordRecorder.start()
        ChordRecorder.onNoteOn(0, 60, 90)
        ChordRecorder.onNoteOn(0, 64, 90)
        ChordRecorder.onNoteOn(0, 67, 90)
        val strummed = ChordRecorder.stop().map { it.note }

        ChordRecorder.start()
        listOf(60, 64, 67).forEach { ChordRecorder.onNoteOn(0, it, 90) }
        val simultaneous = ChordRecorder.stop().map { it.note }

        assertEquals(strummed, simultaneous)
        assertEquals(listOf(60, 64, 67), simultaneous)
    }

    @Test
    fun stop_isSortedByNote() {
        ChordRecorder.start()
        ChordRecorder.onNoteOn(0, 72, 90)
        ChordRecorder.onNoteOn(0, 60, 90)
        ChordRecorder.onNoteOn(0, 67, 90)
        val notes = ChordRecorder.stop().map { it.note }
        assertEquals(listOf(60, 67, 72), notes)
    }

    @Test
    fun stop_deactivatesCollector() {
        ChordRecorder.start()
        ChordRecorder.onNoteOn(0, 60, 90)
        ChordRecorder.stop()
        // After stop, further note-ons are ignored.
        ChordRecorder.onNoteOn(0, 64, 90)
        assertTrue(!ChordRecorder.isActive())
    }

    @Test
    fun ignoresNotesOutsidePianoRange() {
        ChordRecorder.start()
        ChordRecorder.onNoteOn(0, -1, 90)
        ChordRecorder.onNoteOn(0, 128, 90)
        ChordRecorder.onNoteOn(0, 60, 90)
        val chord = ChordRecorder.stop()
        assertEquals(1, chord.size)
        assertEquals(60, chord[0].note)
    }

    @Test
    fun cancel_producesNothing() {
        ChordRecorder.start()
        ChordRecorder.onNoteOn(0, 60, 90)
        ChordRecorder.cancel()
        assertTrue(!ChordRecorder.isActive())
    }

    @Test
    fun perNoteChannel_isKept() {
        ChordRecorder.start()
        ChordRecorder.onNoteOn(0, 60, 90)
        ChordRecorder.onNoteOn(2, 64, 90)
        val chord = ChordRecorder.stop()
        assertEquals(2, chord.size)
        assertEquals(0, chord[0].channel)
        assertEquals(2, chord[1].channel)
    }

    companion object {
        @JvmStatic
        @org.junit.AfterClass
        fun tearDown() {
            // Ensure the singleton is left inactive for any other test class.
            ChordRecorder.cancel()
        }
    }
}
