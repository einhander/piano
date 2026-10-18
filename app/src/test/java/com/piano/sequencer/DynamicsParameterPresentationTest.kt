package com.piano.sequencer

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class DynamicsParameterPresentationTest {
    private fun parameter(effect: String, id: Int, min: Float = 0f, max: Float = 100f,
                          name: String = "Informative control name") =
        effectParameterFromNativeInfo(
            floatArrayOf(id.toFloat(), min, max, 1f, 0f, 0f, 0f),
            name, 0.5f, effect)!!

    private fun format(p: EffectParameter, value: Float) =
        formatEffectParameterValue(p, value, locale = Locale.US)

    @Test fun amplitudeMappingsPreserveNativeMetadataAndShowSignedDb() {
        val mappings = mapOf("lsp.compressor" to listOf(1, 2, 11, 15, 16, 17, 19),
            "lsp.limiter" to listOf(1, 2, 20))
        for ((effect, ids) in mappings) for (id in ids) {
            val p = parameter(effect, id)
            assertEquals("0.0 dB", format(p, 1f))
            assertEquals("-20.0 dB", format(p, 0.1f))
            assertEquals("+20.0 dB", format(p, 10f))
            assertEquals("−∞ dB", format(p, 0f))
            assertEquals("+40.0 dB", format(p, 200f))
            assertEquals(0f, p.minimum)
            assertEquals(100f, p.maximum)
            assertEquals(1f, p.defaultValue)
            assertEquals(0.5f, p.value.rawValue)
            assertFalse(p.logarithmic)
            assertEquals("Informative control name", p.displayName)
        }
    }

    @Test fun nativePositiveAmplitudeBoundsRemainAuthoritative() {
        val p = parameter("lsp.compressor", 11, 0.01f, 1f)
        assertEquals("-40.0 dB", format(p, p.minimum))
        assertEquals("0.0 dB", format(p, p.maximum))
        assertEquals("-40.0 dB", format(p, -1f))
    }

    @Test fun timePercentRatioAndAlreadyDbValuesHaveUnits() {
        for (id in listOf(12, 13, 111)) {
            assertEquals("0.063 ms", format(parameter("lsp.compressor", id), 0.0628717f))
        }
        for (id in listOf(21, 22, 23)) {
            assertEquals("3.25 ms", format(parameter("lsp.limiter", id), 3.25f))
        }
        assertEquals("50.0 %", format(parameter("lsp.compressor", 18), 50f))
        assertEquals("3.2:1", format(parameter("lsp.compressor", 14), 3.16228f))
        val knee = parameter("lsp.limiter", 24, -48f, 0f)
        assertNull(knee.scale)
        assertEquals("-24.0 dB", format(knee, -24f))
        assertEquals("0.0 dB", format(knee, 0f))
    }

    @Test fun displayNamesDoNotRepeatUnitsAndTimesRemainCompact() {
        for ((effect, ids) in mapOf("lsp.compressor" to listOf(12, 13, 111),
            "lsp.limiter" to listOf(21, 22, 23))) {
            for (id in ids) {
                val p = parameter(effect, id, name = "Attack (ms)")
                assertEquals("Attack", p.displayName)
                assertEquals("20 ms", format(p, 20f))
                assertEquals("0 ms", format(p, 0f))
                assertEquals("0.063 ms", format(p, 0.0628717f))
                assertEquals("0.000123 ms", format(p, 0.000123f))
                assertEquals("1,25 ms", formatEffectParameterValue(p, 1.25f, locale = Locale.GERMANY))
                assertEquals("ms", p.unit)
                assertEquals(0.5f, p.value.rawValue)
            }
        }
        assertEquals("Dry/Wet", parameter("lsp.compressor", 18, name = "Dry/Wet (%)").displayName)
        assertEquals("Knee", parameter("lsp.limiter", 24, name = "Knee (dB)").displayName)
        assertEquals("Other (ms)", parameter("lsp.compressor", 18, name = "Other (ms)").displayName)
        assertEquals("Band 0 Frequency", parameter("lsp.parametric_eq", 33, name = "Band 0 freq").displayName)
        assertEquals("1000.0 Hz", format(parameter("lsp.parametric_eq", 33), 1000f))
    }

    @Test fun exactPinnedEnumNamesAndInvalidCodesAreReadable() {
        for ((id, names) in mapOf(10 to listOf("Down", "Up", "Boot"),
            110 to listOf("Peak", "RMS", "LPF", "SMA"))) {
            val p = parameter("lsp.compressor", id)
            names.forEachIndexed { code, name -> assertEquals(name, format(p, code.toFloat())) }
            assertEquals(names[1], format(p, 0.999999f))
            assertEquals("Unknown (99)", format(p, 99f))
            assertEquals("Unknown (1.5)", format(p, 1.5f))
        }
    }

    @Test fun stableIdsAreScopedToEffects() {
        assertNull(parameter("lsp.limiter", 11).unit)
        assertNull(parameter("lsp.compressor", 24).unit)
        assertEquals("1.000", format(parameter("unknown", 1), 1f))
        assertEquals("1.000", format(parameter("lsp.parametric_eq", 11), 1f))
    }
}
