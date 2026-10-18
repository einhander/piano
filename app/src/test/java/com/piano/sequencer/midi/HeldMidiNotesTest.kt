package com.piano.sequencer.midi

import org.junit.Assert.*
import org.junit.Test

class HeldMidiNotesTest {
    @Test fun heldKeysAreIndependentAcrossNotesChannelsAndSources() {
        val state = HeldMidiNotes()
        state.note("a", 0, 60, 100)
        state.note("a", 0, 64, 100)
        state.note("b", 0, 60, 100)
        state.note("a", 1, 60, 100)
        state.note("a", 0, 60, 0)
        assertTrue(state.snapshot()[0])
        state.clearSource("a")
        assertTrue(state.snapshot()[0])
        assertFalse(state.snapshot()[1])
        state.clearSource("b")
        assertFalse(state.snapshot().any { it })
    }

    @Test fun zeroVelocityChannelResetPanicAndRecreatedObserver() {
        val state = HeldMidiNotes()
        var changes = 0
        val listener = { changes++; Unit }
        state.addListener(listener)
        state.note("a", 2, 60, 100)
        state.note("a", 2, 60, 0)
        assertFalse(state.snapshot()[2])
        state.note("a", 2, 61, 100)
        state.clearChannel("a", 2)
        assertFalse(state.snapshot()[2])
        state.removeListener(listener)
        state.note("a", 2, 62, 100)
        assertEquals(4, changes)
        assertTrue(state.snapshot()[2])
        state.clear()
        assertFalse(state.snapshot().any { it })
    }
}
