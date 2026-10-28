package com.piano.sequencer

import com.piano.sequencer.midi.resolveInstrumentIdentityChannel
import com.piano.sequencer.midi.EffectCcMapping
import org.junit.Assert.*
import org.junit.Test

class InstrumentEffectsIdentityTest {
    @Test fun onlyLegacyMissingIdentityDefaultsToMain() {
        assertEquals("main", instrumentEffectsSetId(false, null))
        assertNull(instrumentEffectsSetId(true, null))
        assertNull(instrumentEffectsSetId(true, ""))
        assertEquals("file-id", instrumentEffectsSetId(true, "file-id"))
    }

    @Test fun localChannelOneIsDistinctAcrossSets() {
        assertEquals(0, resolveInstrumentIdentityChannel("main", 0, null))
        assertEquals(16, resolveInstrumentIdentityChannel("file-id", 0, 16))
        assertEquals(127, resolveInstrumentIdentityChannel("file-id", 15, 112))
        assertEquals(-1, resolveInstrumentIdentityChannel("deleted", 0, null))
        assertEquals(-1, resolveInstrumentIdentityChannel("unsupported", 0, 128))
        val main = EffectCcMapping(null, null, 1, 0, "lsp.parametric_eq", "0", 0f, 1f)
        val file = main.copy(targetInstrumentSetId = "file-id")
        assertNotEquals(main, file)
        assertEquals(0, file.targetInstrumentChannel)
        assertEquals("file-id", file.targetInstrumentSetId)
    }

    @Test fun editorLearnTemplateRetainsStableIdentityAndLocalChannel() {
        val parameter = EffectParameter("-1", "Insert enabled", EffectParameterValue(0f),
            0f, 1f, 0f, EffectParameterType.BOOLEAN, false)
        val main = instrumentEffectsLearnTemplate("main", 0, "lsp.filter", parameter)
        val file = instrumentEffectsLearnTemplate("file-id", 0, "lsp.filter", parameter)
        assertEquals("main", main.targetInstrumentSetId)
        assertEquals("file-id", file.targetInstrumentSetId)
        assertEquals(0, file.targetInstrumentChannel)
        assertEquals("-1", file.parameterId)
        assertNotEquals(main, file)
    }

    @Test fun startupRestoresDelayEvenWhenOptionalRackBundleUnavailable() {
        val calls = mutableListOf<String>()
        EffectStateRestore.restoreInstrumentStartup(
            bundleAvailable = false,
            restoreRacks = { calls += "rack" },
            restoreDelays = { calls += "delay" }
        )
        assertEquals(listOf("delay"), calls)
        calls.clear()
        EffectStateRestore.restoreInstrumentStartup(true, { calls += "rack" }, { calls += "delay" })
        assertEquals(listOf("rack", "delay"), calls)
    }
}
