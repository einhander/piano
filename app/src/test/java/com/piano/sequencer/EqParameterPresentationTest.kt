package com.piano.sequencer

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class EqParameterPresentationTest {
    private fun parameter(id: Int, min: Float = 0f, max: Float = 10f,
                          effect: String? = "lsp.parametric_eq", integer: Boolean = false,
                          toggled: Boolean = false, name: String = "Native name") = effectParameterFromNativeInfo(
        floatArrayOf(id.toFloat(), min, max, 1f, 1f, if (integer) 1f else 0f,
            if (toggled) 1f else 0f), name, 1f, effect)!!

    private fun format(p: EffectParameter, value: Float) =
        formatEffectParameterValue(p, value, locale = Locale.US)

    @Test fun allBandsHaveVerifiedEnumNames() {
        val types = listOf("Off", "Bell", "Hi-pass", "Hi-shelf", "Lo-pass", "Lo-shelf",
            "Notch", "Resonance", "Allpass", "Bandpass", "Ladder-pass", "Ladder-rej")
        val modes = listOf("RLC (BT)", "RLC (MT)", "BWC (BT)", "BWC (MT)",
            "LRX (BT)", "LRX (MT)", "APO (DR)")
        for (base in listOf(30) + (130..270 step 10)) {
            types.forEachIndexed { code, name ->
                assertEquals(name, format(parameter(base, integer = true), code.toFloat()))
            }
            modes.forEachIndexed { code, name ->
                assertEquals(name, format(parameter(base + 1, integer = true), code.toFloat()))
            }
            assertEquals("Bell", format(parameter(base), 1.000001f))
            assertEquals("RLC (MT)", format(parameter(base + 1), 0.999999f))
            assertEquals("Unknown (99)", format(parameter(base), 99f))
            assertEquals("Unknown (1.5)", format(parameter(base), 1.5f))
            assertEquals("Unknown (NaN)", format(parameter(base), Float.NaN))
        }
    }

    @Test fun gainsDisplayDecibelsWithoutChangingRawMetadata() {
        for (id in listOf(1, 2, 34) + (134..274 step 10)) {
            val p = parameter(id, 0.01f, 100f)
            assertEquals("0.0 dB", format(p, 1f))
            assertEquals("0.0 dB", format(p, 0.99999f))
            assertEquals("-40.0 dB", format(p, p.minimum))
            assertEquals("+40.0 dB", format(p, p.maximum))
            assertEquals("+40.0 dB", format(p, 1000f))
            assertEquals("-40.0 dB", format(p, -1f))
            assertEquals(0.01f, p.minimum)
            assertEquals(100f, p.maximum)
            assertEquals(1f, p.value.rawValue)
        }
        assertEquals("−∞ dB", format(parameter(1), 0f))
    }

    @Test fun frequencyQAndMuteHaveReadablePresentation() {
        for ((band, base) in (listOf(30) + (130..270 step 10)).withIndex()) {
            val frequency = parameter(base + 3, name = "Band $band freq")
            assertEquals("Band $band Frequency", frequency.displayName)
            assertEquals("1234.5 Hz", format(frequency, 1234.5f))
            val q = parameter(base + 5, name = "Band $band Q")
            assertEquals("Band $band Q", q.displayName)
            assertEquals("1.250", format(q, 1.25f))
            val mute = parameter(base + 2, toggled = true)
            assertEquals("Off", format(mute, 0f))
            assertEquals("On", format(mute, 1f))
        }
    }

    @Test fun nonEqAndMissingContextStayUnchanged() {
        for (effect in listOf(null, "lsp.compressor", "lsp.limiter")) {
            for (id in listOf(1, 2, 30, 31, 33, 34, 35, 133, 274)) {
                val p = parameter(id, effect = effect)
                assertEquals("Native name", p.displayName)
                assertEquals("1.000", format(p, 1f))
                assertTrue(p.enumValues.isEmpty())
                assertNull(p.unit)
            }
            assertEquals("2", format(parameter(30, effect = effect, integer = true), 2f))
        }
    }
}
