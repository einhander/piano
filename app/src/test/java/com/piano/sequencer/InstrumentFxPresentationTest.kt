package com.piano.sequencer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstrumentFxPresentationTest {
    @Test fun insertCardOrderPreservesExistingSlotsAndAppendsNewStableIds() {
        assertEquals(listOf("lsp.parametric_eq", "lsp.compressor", "lsp.limiter", "lsp.chorus", "lsp.clipper",
            "lsp.filter", "lsp.phaser", "lsp.flanger"), instrumentInsertCardIds)
        assertEquals(8, instrumentInsertCardIds.distinct().size)
    }

    private fun presented(effect: String, id: Int, raw: Float = .5f): EffectParameter =
        instrumentPresentation(effect, EffectParameter(id.toString(), "Native name", EffectParameterValue(raw),
            .01f, 123f, .75f, EffectParameterType.FLOAT, true))

    @Test fun newPresentationPreservesNativeValuesBoundsDefaultsAndScaling() {
        listOf("lsp.filter" to (360..364), "lsp.phaser" to (380..384), "lsp.flanger" to (400..403), "lsp.chorus" to (341..342)).forEach { (effect, ids) ->
            ids.forEach { id ->
                val p = presented(effect, id)
                assertEquals(id.toString(), p.stableId)
                assertEquals(.5f, p.value.rawValue, 0f)
                assertEquals(.01f, p.minimum, 0f)
                assertEquals(123f, p.maximum, 0f)
                assertEquals(.75f, p.defaultValue, 0f)
                assertTrue(p.logarithmic)
                assertEquals(EffectParameterType.FLOAT, p.type)
            }
        }
    }

    @Test fun filterTypesUseVerifiedNativeCodesWithoutOffEntry() {
        val p = presented("lsp.filter", 362)
        val names = listOf("Lo-pass", "Hi-pass", "Lo-shelf", "Hi-shelf", "Bell", "Bandpass", "Notch",
            "Resonance", "Ladder-pass", "Ladder-rej", "Allpass")
        assertEquals(names.mapIndexed { index, name -> index.toFloat() to name }, p.enumValues)
        assertEquals("Lo-pass", instrumentValueLabel(p, 0f))
        assertEquals("Allpass", instrumentValueLabel(p, 10f))
        assertFalse(p.enumValues.any { it.second == "Off" })
    }

    @Test fun phaserStagesUseZeroBasedCodesAndNativeDefaultCodeThreeMeansFour() {
        val p = presented("lsp.phaser", 382)
        assertEquals((1..8).mapIndexed { index, count -> index.toFloat() to count.toString() }, p.enumValues)
        assertEquals("4", instrumentValueLabel(p, 3f))
    }

    @Test fun newUnitsDistinguishRateDepthFeedbackMixAndPlainQ() {
        assertEquals("Hz", presented("lsp.filter", 360).unit)
        assertEquals(null, presented("lsp.filter", 361).unit)
        assertEquals("Q", presented("lsp.filter", 361).displayName)
        listOf("lsp.phaser" to 380, "lsp.flanger" to 400).forEach { (effect, id) ->
            assertEquals("Hz", presented(effect, id).unit)
        }
        assertEquals("ms", presented("lsp.flanger", 401).unit)
        listOf("lsp.phaser" to 381, "lsp.phaser" to 383, "lsp.flanger" to 402).forEach { (effect, id) ->
            val p = presented(effect, id)
            assertEquals("%", p.unit)
            assertEquals("fraction-percent", p.scale)
            // Unit conversion is display-only; MIDI Learn and setters keep raw gain/fraction.
            assertEquals(formatEffectParameterValue(p.copy(scale = null), 50f), instrumentValueLabel(p, .5f))
            assertEquals(.5f, p.value.rawValue, 0f)
        }
        listOf("lsp.phaser" to 384, "lsp.flanger" to 403).forEach { (effect, id) ->
            val p = presented(effect, id)
            assertEquals("%", p.unit)
            assertEquals(null, p.scale)
            assertEquals(formatEffectParameterValue(p, 50f), instrumentValueLabel(p, 50f))
        }
    }

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

    @Test fun filterApplicabilityMatchesPinnedUpstreamForAllElevenTypes() {
        for (type in 0..10) {
            assertEquals(type in listOf(2, 3, 4, 7, 8, 9), instrumentFilterControlVisible("363", type.toFloat()))
            assertEquals(type in listOf(5, 8, 9), instrumentFilterControlVisible("364", type.toFloat()))
            listOf("360", "361", "362").forEach { assertTrue(instrumentFilterControlVisible(it, type.toFloat())) }
        }
    }

    @Test fun gainIsLinearAmplitudeAndWidthIsPlainOctaves() {
        listOf("lsp.filter" to 363, "lsp.chorus" to 341, "lsp.chorus" to 342).forEach { (effect, id) ->
            val p = presented(effect, id)
            assertEquals("dB", p.unit)
            assertEquals("linear-amplitude", p.scale)
            assertEquals("0.0 dB", instrumentValueLabel(p, 1f))
            assertEquals(formatEffectParameterValue(p, .501187f), instrumentValueLabel(p, .501187f))
        }
        assertEquals("Dry gain", presented("lsp.chorus", 341).displayName)
        assertEquals("Wet gain", presented("lsp.chorus", 342).displayName)
        val width = presented("lsp.filter", 364)
        assertEquals("octaves", width.unit)
        assertEquals(null, width.scale)
        assertTrue(instrumentValueLabel(width, 6f).endsWith(" octaves"))
        assertEquals("Frequency", presented("lsp.filter", 360).displayName)
        listOf("lsp.chorus" to 304, "lsp.phaser" to 384, "lsp.flanger" to 403).forEach { (effect, id) ->
            val p = presented(effect, id)
            assertEquals("Dry/Wet", p.displayName)
            assertEquals("%", p.unit)
            assertEquals(null, p.scale)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidChannelRejectedBeforeNativeRead() {
        instrumentDelayControls(16, { error("Must not query") }, { _, _ -> error("Must not read") })
    }
}
