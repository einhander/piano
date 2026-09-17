package com.piano.sequencer.midi

import org.junit.Assert.assertEquals
import org.junit.Test

class MidiIngressRouterTest {
    @Test fun unconsumedNoteDispatches() {
        val events = mutableListOf<String>()
        val router = MidiIngressRouter({ _, _, _ -> false }, { _, _, _ -> false }, { s, n, v, c -> events += "$s:$n:$v:$c" })
        router.noteOn(2, 60, 99)
        assertEquals(listOf("144:60:99:2"), events)
    }

    @Test fun consumedNoteDoesNotDispatch() {
        val events = mutableListOf<Int>()
        val router = MidiIngressRouter({ _, _, _ -> true }, { _, _, _ -> true }, { _, _, _, _ -> events += 1 })
        router.noteOn(0, 60, 100)
        assertEquals(emptyList<Int>(), events)
    }

    @Test fun learnConsumptionRunsBeforeDispatch() {
        val order = mutableListOf<String>()
        val router = MidiIngressRouter({ _, _, _ -> order += "learn"; true }, { _, _, _ -> true }, { _, _, _, _ -> order += "dispatch" })
        router.noteOn(0, 60, 100)
        assertEquals(listOf("learn"), order)
    }
}
