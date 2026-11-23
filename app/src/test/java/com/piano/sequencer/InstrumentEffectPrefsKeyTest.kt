package com.piano.sequencer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class InstrumentEffectPrefsKeyTest {
    @Test fun keysEncodeChannelEffectAndStableParameterId() {
        assertEquals("instrument_fx_v1_rack_0", EffectStateRestore.instrumentRackKey(0))
        assertEquals("instrument_fx_v1_enabled_15_lsp_compressor", EffectStateRestore.instrumentEnabledKey(15, "lsp.compressor"))
        assertEquals("instrument_fx_v1_param_4_lsp_parametric_eq_271", EffectStateRestore.instrumentParamKey(4, "lsp.parametric_eq", 271))
        assertNotEquals(EffectStateRestore.instrumentParamKey(0, "lsp.limiter", 12), EffectStateRestore.instrumentParamKey(1, "lsp.limiter", 12))
        assertNotEquals(EffectStateRestore.instrumentParamKey(0, "lsp.limiter", 12), EffectStateRestore.instrumentParamKey(0, "lsp.limiter", 13))
    }

    @Test fun instrumentSchemaIsIndependentAndVersioned() {
        assertEquals(1, EffectStateRestore.INSTRUMENT_VERSION)
        assertNotEquals("fx_enabled_0", EffectStateRestore.instrumentEnabledKey(0, "lsp.parametric_eq"))
        assertNotEquals("fx_param_0_12", EffectStateRestore.instrumentParamKey(0, "lsp.parametric_eq", 12))
    }
    @Test fun newInsertsAppendWithoutChangingV1KeysAndSharedKeysAreSeparate() {
        assertEquals("instrument_fx_v1_enabled_2_lsp_chorus", EffectStateRestore.instrumentEnabledKey(2, "lsp.chorus"))
        assertEquals("shared_fx_v1_native_delay_param_1", EffectStateRestore.sharedParameterKey("native.delay", 1))
        assertEquals("shared_fx_v1_delay_send_15", EffectStateRestore.delaySendKey(15))
        assertNotEquals(EffectStateRestore.sharedParameterKey("native.delay", 0), EffectStateRestore.sharedParameterKey("fluidsynth.reverb", 0))
    }
    @Test fun newInsertKeysRemainV1AndOldMappingsRemainStable() {
        assertEquals(listOf("lsp.parametric_eq", "lsp.compressor", "lsp.limiter", "lsp.chorus", "lsp.clipper", "lsp.filter", "lsp.phaser", "lsp.flanger", "piano.tremolo", "guitar.rig"), EffectStateRestore.instrumentIds)
        assertEquals("instrument_fx_v1_enabled_2_lsp_filter", EffectStateRestore.instrumentEnabledKey(2, "lsp.filter"))
        assertEquals("instrument_fx_v1_param_2_lsp_flanger_400", EffectStateRestore.instrumentParamKey(2, "lsp.flanger", 400))
        assertEquals("instrument_fx_v1_enabled_2_lsp_chorus", EffectStateRestore.instrumentEnabledKey(2, "lsp.chorus"))
        assertEquals("instrument_fx_v1_enabled_main_2_piano_tremolo", EffectStateRestore.instrumentIdentityEnabledKey("main", 2, "piano.tremolo"))
        assertEquals("instrument_fx_v1_param_main_2_piano_tremolo_410", EffectStateRestore.instrumentIdentityParamKey("main", 2, "piano.tremolo", 410))
    }
    @Test fun guitarRigUsesTheSameV1SchemeWithoutKeyCollisions() {
        // "guitar.rig" is just another id in the same instrument_fx_v1_*
        // scheme (dots replaced) — no format-version bump, no private prefix.
        assertEquals("instrument_fx_v1_enabled_3_guitar_rig", EffectStateRestore.instrumentEnabledKey(3, "guitar.rig"))
        assertEquals("instrument_fx_v1_param_3_guitar_rig_10", EffectStateRestore.instrumentParamKey(3, "guitar.rig", 10))
        assertEquals("instrument_fx_v1_enabled_main_3_guitar_rig", EffectStateRestore.instrumentIdentityEnabledKey("main", 3, "guitar.rig"))
        assertEquals("instrument_fx_v1_param_main_3_guitar_rig_8", EffectStateRestore.instrumentIdentityParamKey("main", 3, "guitar.rig", 8))
        // No collisions with any existing insert id under the same channel/param.
        EffectStateRestore.instrumentIds.filter { it != "guitar.rig" }.forEach { other ->
            assertNotEquals(EffectStateRestore.instrumentEnabledKey(3, "guitar.rig"), EffectStateRestore.instrumentEnabledKey(3, other))
            assertNotEquals(EffectStateRestore.instrumentParamKey(3, "guitar.rig", 10), EffectStateRestore.instrumentParamKey(3, other, 10))
            assertNotEquals(EffectStateRestore.instrumentIdentityParamKey("main", 3, "guitar.rig", 10), EffectStateRestore.instrumentIdentityParamKey("main", 3, other, 10))
        }
    }
    @Test fun newInsertPresentationAddsAuditedUnitsAndEnumsWithoutChangingNativeMetadata() {
        val filterType = EffectParameter("362", "Type", EffectParameterValue(0f), 0f, 10f, 0f, EffectParameterType.INTEGER, false)
        val presented = instrumentPresentation("lsp.filter", filterType)
        assertEquals(11, presented.enumValues.size)
        assertEquals(10f, presented.maximum, 0f)
        val feedback = instrumentPresentation("lsp.phaser", EffectParameter("383", "Feedback", EffectParameterValue(0f), 0f, .89125f, 0f, EffectParameterType.FLOAT, false))
        assertEquals("fraction-percent", feedback.scale)
        assertEquals("%", feedback.unit)
    }
}
