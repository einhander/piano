package com.piano.sequencer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class InstrumentFxPresentationTest {
    @Test fun delayReadsOnlySelectedChannelAndSixNativeParameters() {
        for (channel in 0..15) {
            val reads = mutableListOf<Pair<Int, Int>>()
            val controls = instrumentDelayControls(channel, { id ->
                floatArrayOf(id.toFloat(), 0f, 1f, 0f, 0f, 0f, 0f)
            }, { target, id -> reads.add(target to id); .25f })
            assertEquals((0..5).map { channel to it }, reads)
            assertEquals((0..5).map(Int::toString), controls.map { it.stableId })
            assertFalse(controls.any { it.displayName.contains("Send") })
        }
    }

    @Test fun instrumentCardsExcludeGlobalReverb() {
        assertEquals(listOf("native.delay"), instrumentDelayCardIds)
        assertFalse(instrumentDelayCardIds.contains("fluidsynth.reverb"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidChannelRejectedBeforeNativeRead() {
        instrumentDelayControls(16, { error("Must not query") }, { _, _ -> error("Must not read") })
    }
}
