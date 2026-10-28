package com.piano.sequencer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class InstrumentFxPresentationTest {
    @Test fun delaySendAppendedWithoutUnsupportedNativeQuery() {
        val queried = mutableListOf<Int>()
        val controls = sharedInstrumentControls("native.delay", { id ->
            queried.add(id)
            floatArrayOf(id.toFloat(), 0f, 1f, 0f, 0f, 0f, 0f)
        }, { .25f }, { .65f })
        assertEquals((0..5).toList(), queried)
        assertEquals((0..6).map(Int::toString), controls.map { it.stableId })
        val send = controls.last()
        assertEquals("Delay Send", send.displayName)
        assertEquals(0f, send.minimum)
        assertEquals(1f, send.maximum)
        assertEquals(0f, send.defaultValue)
        assertEquals(.65f, send.value.rawValue)
        assertEquals(EffectParameterType.FLOAT, send.type)
        assertEquals("fraction-percent", send.scale)
    }

    @Test fun reverbNeverQueriesDelaySend() {
        var queriedSend = false
        val controls = sharedInstrumentControls("fluidsynth.reverb", { id ->
            floatArrayOf(id.toFloat(), 0f, 1f, 0f, 0f, 0f, 0f)
        }, { .5f }, { queriedSend = true; 0f })
        assertFalse(queriedSend)
        assertEquals((0..4).map(Int::toString), controls.map { it.stableId })
    }
}
