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
}
