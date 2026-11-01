package com.piano.sequencer

import com.piano.sequencer.midi.EffectCcScope
import org.junit.Assert.*
import org.junit.Test

class GlobalReverbLearnTemplateTest {
    @Test fun templatesPreserveEveryNativeParameterRange() {
        (0..4).forEach { id ->
            val p = EffectParameter(id.toString(), "Native parameter", EffectParameterValue(0f),
                0f, if (id == 3) 100f else 1f, 0f,
                if (id == 0) EffectParameterType.BOOLEAN else EffectParameterType.FLOAT, false)
            val mapping = globalReverbLearnTemplate(p)
            assertEquals(EffectCcScope.GLOBAL, mapping.scope)
            assertEquals("fluidsynth.reverb", mapping.effectId)
            assertEquals(id.toString(), mapping.parameterId)
            assertEquals(p.minimum, mapping.minRaw, 0f)
            assertEquals(p.maximum, mapping.maxRaw, 0f)
            assertNull(mapping.source)
            assertNull(mapping.inputChannel)
        }
    }

    @Test fun templateUsesDescriptorRatherThanDuplicatedUiRange() {
        val p = EffectParameter("3", "Width", EffectParameterValue(20f),
            5f, 85f, 20f, EffectParameterType.FLOAT, false)
        val mapping = globalReverbLearnTemplate(p)
        assertEquals(5f, mapping.minRaw, 0f)
        assertEquals(85f, mapping.maxRaw, 0f)
    }
}
