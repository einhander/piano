package com.piano.sequencer

import org.junit.Assert.assertEquals
import org.junit.Test

class InstrumentParameterMappingTest {
    private fun parameter(min: Float, max: Float, log: Boolean = false,
                          type: EffectParameterType = EffectParameterType.FLOAT) =
        EffectParameter("7", "Native parameter", EffectParameterValue(min), min, max, min, type, log)

    @Test fun logarithmicMappingUsesNativeBounds() {
        val p = parameter(20f, 20000f, true)
        assertEquals(20f, instrumentParameterValue(p, 0), 0.001f)
        assertEquals(20000f, instrumentParameterValue(p, 1000), 0.01f)
        assertEquals(632.4555f, instrumentParameterValue(p, 500), 0.01f)
        assertEquals(500, instrumentParameterProgress(p, instrumentParameterValue(p, 500)))
    }

    @Test fun integerMappingRoundsAndClamps() {
        val p = parameter(-3f, 6f, type = EffectParameterType.INTEGER)
        assertEquals(2f, instrumentParameterValue(p, 500), 0f)
        assertEquals(-3f, instrumentParameterValue(p, -20), 0f)
        assertEquals(6f, instrumentParameterValue(p, 2000), 0f)
    }

    @Test fun fixedRangeAndInvalidReadbackHaveSafeProgress() {
        val p = parameter(2f, 2f)
        assertEquals(0, instrumentParameterProgress(p, 2f))
        assertEquals(0, instrumentParameterProgress(p, Float.NaN))
        assertEquals(2f, instrumentParameterValue(p, 500), 0f)
    }
}
