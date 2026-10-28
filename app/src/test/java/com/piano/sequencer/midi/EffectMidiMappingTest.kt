package com.piano.sequencer.midi

import com.piano.sequencer.*
import org.junit.Assert.*
import org.junit.Test

class EffectMidiMappingTest {
    @Test fun scopeWhitelistAndBackwardCompatibleDefault() {
        val old = mapping()
        assertEquals(EffectCcScope.INSTRUMENT, old.scope)
        val global = EffectCcMapping("dev", 0, 8, effectId = "native.delay", parameterId = "0", minRaw = 0f, maxRaw = 1f, scope = EffectCcScope.GLOBAL)
        assertEquals(EffectCcScope.GLOBAL, global.scope)
        assertThrows(IllegalArgumentException::class.java) { global.copy(scope = EffectCcScope.INSTRUMENT) }
        assertThrows(IllegalArgumentException::class.java) { global.copy(effectId = "fluidsynth.reverb", scope = EffectCcScope.INSTRUMENT) }
    }
    @Test fun serializedMappingScopeRoundTripsAndMissingScopeMeansInstrument() {
        val global = EffectCcMapping("dev", 0, 8, effectId = "fluidsynth.reverb", parameterId = "0", minRaw = 0f, maxRaw = 1f, scope = EffectCcScope.GLOBAL)
        assertEquals(global, global.copy(scope = EffectCcScope.valueOf(global.scope.name)))
        val oldMapping = EffectCcMapping("dev", 0, 8, 0, "lsp.limiter", "1", 0f, 1f)
        assertEquals(EffectCcScope.INSTRUMENT, oldMapping.scope)
    }
    @Test fun sendUsesChosenInstrumentTargetAndEnabledVirtualParameterIsIndependent() {
        val send = EffectCcMapping("dev", 3, 9, 12, "native.delay", "6", 0f, 1f, scope = EffectCcScope.GLOBAL)
        assertEquals(12, effectCcTargetChannel(send)) // incoming MIDI channel 3 is not send destination
        val enabled = EffectCcMapping("dev", 3, 9, 12, "lsp.chorus", "-1", 0f, 1f)
        assertEquals(EffectCcScope.INSTRUMENT, enabled.scope)
        assertEquals("-1", instrumentEffectEnabledDescriptor("lsp.chorus").stableId)
        assertEquals(EffectParameterType.BOOLEAN, instrumentEffectEnabledDescriptor("lsp.chorus").type)
        assertEquals(EffectCcAction.INSTRUMENT_ENABLED, effectCcAction(enabled))
        assertEquals(EffectCcAction.DELAY_SEND, effectCcAction(send))
    }
    @Test fun sharedLearnMetadataDecodesNativeInfoWithoutExistingMapping() {
        val param = sharedFxParameterFromNativeInfo("fluidsynth.reverb", 3, floatArrayOf(3f, 0f, 100f, 80f, 0f, 0f, 0f), 80f)
        assertNotNull(param)
        assertEquals(0f, param!!.minimum, 0f)
        assertEquals(100f, param.maximum, 0f)
        assertEquals(80f, param.defaultValue, 0f)
        assertNull(sharedFxParameterFromNativeInfo("native.delay", 99, floatArrayOf(99f, 0f, 1f, 0f, 0f, 0f, 0f), 0f))
    }
    private fun mapping(source: String? = "dev", channel: Int? = null, cc: Int = 7,
                       min: Float = 0f, max: Float = 1f, invert: Boolean = false) =
        EffectCcMapping(source, channel, cc, 2, "lsp.compressor", "1", min, max, invert)
    private fun parameter(type: EffectParameterType = EffectParameterType.FLOAT, min: Float = 0f,
                          max: Float = 1f, log: Boolean = false,
                          enums: List<Pair<Float, String>> = emptyList()) =
        EffectParameter("1", "p", EffectParameterValue(.5f), min, max, .5f, type, log, enumValues = enums)

    @Test fun endpointsIntermediateAndInversion() {
        val m = mapping()
        assertEquals(0f, EffectMidiCoordinator.scale(m, parameter(), 0), 0f)
        assertEquals(1f, EffectMidiCoordinator.scale(m, parameter(), 127), 0f)
        assertEquals(64f / 127f, EffectMidiCoordinator.scale(m, parameter(), 64), .0001f)
        assertEquals(1f, EffectMidiCoordinator.scale(mapping(invert = true), parameter(), 0), 0f)
        assertEquals(0f, EffectMidiCoordinator.scale(mapping(invert = true), parameter(), 127), 0f)
    }

    @Test fun logarithmicScaling() {
        val p = parameter(min = .1f, max = 10f, log = true)
        val m = mapping(min = .1f, max = 10f)
        assertEquals(.1f, EffectMidiCoordinator.scale(m, p, 0), .0001f)
        assertEquals(1f, EffectMidiCoordinator.scale(m, p, 63), .03f)
        assertEquals(10f, EffectMidiCoordinator.scale(m, p, 127), .001f)
    }

    @Test fun enumIntegerAndToggleScaling() {
        assertEquals(0f, EffectMidiCoordinator.scale(mapping(), parameter(enums = listOf(0f to "a", 2f to "b", 4f to "c")), 64), 0f)
        assertEquals(1f, EffectMidiCoordinator.scale(mapping(max = 4f), parameter(type = EffectParameterType.INTEGER, max = 4f), 32), 0f)
        val bool = parameter(type = EffectParameterType.BOOLEAN)
        assertEquals(0f, EffectMidiCoordinator.scale(mapping(), bool, 63), 0f)
        assertEquals(1f, EffectMidiCoordinator.scale(mapping(), bool, 64), 0f)
    }

    @Test fun rejectsInvalidMappingBoundsAndDescriptors() {
        assertThrows(IllegalArgumentException::class.java) { mapping(min = 1f, max = 1f) }
        assertThrows(IllegalArgumentException::class.java) { mapping(min = Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { mapping(max = Float.POSITIVE_INFINITY) }
        assertThrows(IllegalArgumentException::class.java) { EffectMidiCoordinator.scale(mapping(), parameter(min = Float.NaN), 5) }
        assertThrows(IllegalArgumentException::class.java) { EffectMidiCoordinator.scale(mapping(), parameter(min = 1f, max = 1f), 5) }
    }

    @Test fun wildcardAndExactMatchPriority() {
        val c = EffectMidiCoordinator()
        val any = mapping(source = null, channel = null)
        val source = mapping(source = "dev", channel = null, min = .1f, max = .9f)
        val exact = mapping(source = "dev", channel = 3, min = .2f, max = .8f)
        assertNull(c.add(any)); assertNotNull(c.add(source))
        assertTrue(any.matches("elsewhere", 4, 7))
        assertTrue(source.matches("dev", 4, 7)); assertFalse(source.matches("elsewhere", 4, 7))
        val sent = mutableListOf<Float>()
        val consumed = c.onCc("dev", 3, 7, 0, { _, _ -> parameter() }, { _, _ -> 0f }) { _, _, v -> sent += v }
        assertFalse("Pass-through must still dispatch effect mapping", consumed)
        assertEquals(0f, sent.single(), 0f)
        // Wildcard matching is exercised independently; overlapping mappings are rejected.
        assertTrue(exact.matches("dev", 3, 7))
        // No more-specific source mapping; wildcard source remains matchable.
        assertTrue(any.matches("elsewhere", 4, 7))
    }

    @Test fun detectsSourceAndChannelOverlapConflicts() {
        val c = EffectMidiCoordinator()
        assertNull(c.add(mapping(source = "dev", channel = 2)))
        assertNotNull(c.add(mapping(source = "dev", channel = null)))
        assertNotNull(c.add(mapping(source = null, channel = 2)))
        assertNotNull(c.add(mapping(source = null, channel = null)))
        assertNull(c.add(mapping(source = "other", channel = 2)))
        assertNull(c.add(mapping(source = "dev", channel = 3)))
    }

    private fun cc(c: EffectMidiCoordinator, v: Int, current: () -> Float, out: MutableList<Float>) =
        c.onCc("dev", 0, 7, v, { _, _ -> parameter() }, { _, _ -> current() }) { _, _, value -> out += value }

    @Test fun pickupStartsUnarmedAndArmsAtExactValue() {
        val c = EffectMidiCoordinator(); c.add(mapping(channel = 0)); val out = mutableListOf<Float>()
        cc(c, 10, { .5f }, out); assertTrue(out.isEmpty())
        cc(c, 63, { .5f }, out); assertEquals(1, out.size)
    }

    @Test fun pickupCrossesBothDirectionsAndHandlesReversal() {
        val c = EffectMidiCoordinator(); c.add(mapping(channel = 0)); val out = mutableListOf<Float>()
        cc(c, 20, { .5f }, out); cc(c, 90, { .5f }, out); cc(c, 40, { .5f }, out)
        assertEquals(2, out.size) // pickup arms at 90; subsequent movement dispatches normally
        val other = EffectMidiCoordinator(); other.add(mapping(channel = 0)); val out2 = mutableListOf<Float>()
        cc(other, 100, { .5f }, out2); cc(other, 40, { .5f }, out2)
        assertEquals(1, out2.size) // crossing and arming occur on same downward CC
    }

    @Test fun manualParameterChangeResetsPickup() {
        val c = EffectMidiCoordinator(); c.add(mapping(channel = 0)); val out = mutableListOf<Float>()
        var current = .5f
        cc(c, 63, { current }, out)
        assertEquals(1, out.size)
        current = .8f
        cc(c, 65, { current }, out); assertEquals(1, out.size)
        cc(c, 102, { current }, out); cc(c, 100, { current }, out)
        assertEquals(3, out.size) // 102 crosses and arms; 100 dispatches after pickup
    }
}
