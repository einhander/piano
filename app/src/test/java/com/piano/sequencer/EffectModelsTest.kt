package com.piano.sequencer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectModelsTest {
    @Test
    fun nativeFlagsDetermineTypeAndNativeValuesArePreserved() {
        val bool = effectParameterFromNativeInfo(floatArrayOf(8f, -2f, 3f, 1f, 0f, 1f, 1f), "", 1f)!!
        assertEquals(EffectParameterType.BOOLEAN, bool.type)
        assertEquals("8", bool.stableId)
        assertEquals("Param 8", bool.displayName)
        assertEquals(1f, bool.value.rawValue)
        assertEquals(-2f, bool.minimum)
        assertEquals(3f, bool.maximum)
        assertEquals(1f, bool.defaultValue)

        val integer = effectParameterFromNativeInfo(floatArrayOf(9f, -4f, 5f, 0f, 1f, 1f, 0f), "Count", 2f)!!
        assertEquals(EffectParameterType.INTEGER, integer.type)
        assertTrue(integer.logarithmic)
        assertEquals(-4f, integer.minimum)
        assertEquals(5f, integer.maximum)

        val float = effectParameterFromNativeInfo(floatArrayOf(10f, 0.25f, 7f, 1f, 0f, 0f, 0f), "Gain", 1.5f)!!
        assertEquals(EffectParameterType.FLOAT, float.type)
        assertEquals(0.25f, float.minimum)
        assertEquals(7f, float.maximum)
        assertEquals(1.5f, float.value.rawValue)
        assertFalse(float.logarithmic)
    }

    @Test
    fun malformedNativeMetadataIsRejected() {
        assertEquals(null, effectParameterFromNativeInfo(floatArrayOf(1f, 0f), "", 0f))
    }
}
