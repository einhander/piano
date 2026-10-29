package com.piano.sequencer

import org.junit.Assert.*
import org.junit.Test

class InstrumentFxPresetLibraryTest {
    private fun preset(name: String = "One") = InstrumentFxPreset(
        name,
        true,
        InstrumentFxPresetCodec.insertIds.map { InstrumentFxPreset.Insert(it, true, mapOf(1 to .5f)) },
        InstrumentFxPreset.Delay(true, (0..5).associateWith { .5f })
    )
    @Test fun codecRoundTripAndRejectUnknownIds() {
        val p=preset(); assertEquals(p,InstrumentFxPresetCodec.decode(InstrumentFxPresetCodec.encode(listOf(p))).single())
        val bad=InstrumentFxPresetCodec.encode(listOf(p)).replace("lsp.parametric_eq","unknown")
        assertThrows(IllegalArgumentException::class.java) { InstrumentFxPresetCodec.decode(bad) }
    }
    @Test fun libraryOverwriteDeleteAndFailedCommitPreservesData() {
        var raw: String? = null
        var fail = false
        val lib = InstrumentFxPresetLibrary(object : InstrumentFxPresetStorage {
            override fun read() = raw
            override fun commit(value: String): Boolean {
                if (fail) return false
                raw = value
                return true
            }
        })
        lib.save(preset())
        assertThrows(IllegalArgumentException::class.java) { lib.save(preset("one")) }
        lib.save(preset("one"), true)
        val before = raw
        fail = true
        assertThrows(IllegalStateException::class.java) { lib.delete("one") }
        assertEquals(before, raw)
    }

    @Test fun corruptedLibraryIsNeverReplaced() {
        val corrupt = "{broken"
        val lib = InstrumentFxPresetLibrary(object : InstrumentFxPresetStorage {
            override fun read() = corrupt
            override fun commit(value: String) = error("must not commit")
        })
        assertThrows(Exception::class.java) { lib.save(preset(), overwrite = true) }
    }

    @Test fun seedCommitFailureAndCorruptLibraryArePreserved() {
        var raw: String? = null
        val storage = object : InstrumentFxPresetStorage {
            override fun read() = raw
            override fun commit(value: String) = false
        }
        val library = InstrumentFxPresetLibrary(storage)
        assertThrows(IllegalStateException::class.java) { library.seedDefaultsIfAbsent(listOf(preset())) }
        assertNull(raw)

        val corrupt = "corrupt"
        val corruptLibrary = InstrumentFxPresetLibrary(object : InstrumentFxPresetStorage {
            override fun read() = corrupt
            override fun commit(value: String) = error("must not commit")
        })
        assertThrows(Exception::class.java) { corruptLibrary.seedDefaultsIfAbsent(listOf(preset())) }
    }

    private class Port : InstrumentFxChannelPort {
        val calls = mutableListOf<String>()
        var failAt: String? = null
        var factoryMode = false
        private fun record(value: String): Boolean { calls.add(value); return value != failAt }
        override fun validTarget(setId: String, localChannel: Int) = setId == "set-b" && localChannel == 4
        private fun factoryDescriptors(id: String): List<FloatArray> {
            fun descriptor(parameter: Int, min: Float = 0f, max: Float = 1f, default: Float = .5f) =
                floatArrayOf(parameter.toFloat(), min, max, default, 0f, 0f, 0f)
            return when (id) {
                "lsp.parametric_eq" -> listOf(
                    descriptor(28, 0f, 11f, 0f),
                    descriptor(33, 10f, 24000f, 1000f),
                    descriptor(35, .01585f, 63.0957f, 1f)
                )
                "lsp.chorus" -> listOf(descriptor(304, 0f, 100f, 100f))
                "lsp.clipper" -> listOf(descriptor(320, 0f, 10f, 1f))
                "lsp.filter" -> listOf(descriptor(360, 10f, 24000f, 1000f), descriptor(362, 0f, 10f, 0f))
                "lsp.flanger" -> listOf(descriptor(403, 0f, 100f, 100f))
                else -> listOf(descriptor(1), descriptor(2))
            }
        }
        override fun count(id: String) = if (factoryMode) factoryDescriptors(id).size else 1
        override fun info(id: String, index: Int) =
            if (factoryMode) factoryDescriptors(id).getOrNull(index) else floatArrayOf(1f, 0f, 1f, 0f, 0f, 0f, 0f)
        override fun delayInfo(parameter: Int): FloatArray {
            val max = when (parameter) { 1 -> 2000f; 2, 3 -> .8f; 5 -> 4f; else -> 1f }
            val defaults = listOf(0f, 250f, .35f, .30f, 0f, 0f)
            return floatArrayOf(parameter.toFloat(), 0f, max, defaults[parameter], 0f, 0f, 0f)
        }
        override fun available(setId: String, channel: Int, id: String) = true
        override fun setParam(s: String, c: Int, id: String, p: Int, v: Float) = record("$s:$c:$id:$p")
        override fun setEnabled(s: String, c: Int, id: String, e: Boolean) = record("enabled:$s:$c:$id")
        override fun setDelay(s: String, c: Int, p: Int, v: Float) = record("delay:$s:$c:$p")
        override fun delayAvailable() = true
        override fun setRack(s: String, c: Int, e: Boolean) = record("rack:$s:$c")
        override fun getParam(s: String, c: Int, id: String, p: Int) = .5f
        override fun isEnabled(s: String, c: Int, id: String) = true
        override fun getDelay(s: String, c: Int, p: Int) = .5f
        override fun rackEnabled(s: String, c: Int) = true
    }

    @Test fun firstRunSeedsNineNativeDefaultBasedPresetsOnceOnly() {
        var raw: String? = null
        var commits = 0
        val storage = object : InstrumentFxPresetStorage {
            override fun read() = raw
            override fun commit(value: String): Boolean { commits++; raw = value; return true }
        }
        val library = InstrumentFxPresetLibrary(storage)
        val port = Port().apply { factoryMode = true }
        val defaults = InstrumentFxPresetAdapter(port).factoryDefaults("set-b", 4)
        assertEquals(8, defaults.size)
        assertEquals(
            listOf("Guitar · Clean Chorus", "Guitar · Crunch", "Guitar · Lead", "Violin · Warm Solo", "Violin · Spacious", "Piano · Clean", "Piano · Warm", "Piano · Wide"),
            defaults.map { it.name }
        )
        defaults.forEach { preset ->
            assertEquals(InstrumentFxPresetCodec.insertIds, preset.effects.map { it.effectId })
            assertTrue(preset.effects.all { it.parameters.isNotEmpty() })
            assertEquals((0..5).toSet(), preset.delay.parameters.keys)
            assertTrue(preset.effects.flatMap { it.parameters.values }.all(Float::isFinite))
        }
        val byName = defaults.associateBy { it.name }
        val cleanChorus = byName.getValue("Guitar · Clean Chorus")
        val warmViolin = byName.getValue("Violin · Warm Solo")
        assertEquals(20f, cleanChorus.effects.first { it.effectId == "lsp.chorus" }.parameters[304])
        assertEquals(12f, warmViolin.effects.first { it.effectId == "lsp.chorus" }.parameters[304])
        assertEquals(9000f, warmViolin.effects.first { it.effectId == "lsp.filter" }.parameters[360])
        assertEquals(1.5f, byName.getValue("Guitar · Crunch").effects.first { it.effectId == "lsp.clipper" }.parameters[320])
        assertEquals(25f, byName.getValue("Guitar · Lead").effects.first { it.effectId == "lsp.chorus" }.parameters[304])
        assertEquals(1f, port.info("lsp.clipper", 0)!![3])
        assertEquals(.12f, byName.getValue("Guitar · Lead").delay.parameters[3])
        assertEquals(.18f, byName.getValue("Violin · Spacious").delay.parameters[3])
        assertEquals(20f, byName.getValue("Piano · Wide").effects.first { it.effectId == "lsp.flanger" }.parameters[403])
        val pianoEq = byName.getValue("Piano · Clean").effects.first { it.effectId == "lsp.parametric_eq" }.parameters
        assertEquals(5f, pianoEq[28])
        assertEquals(200f, pianoEq[33])
        assertEquals(1.122018f, pianoEq[35])
        var generated = 0
        val firstRead = library.listOrSeed { generated++; defaults }
        assertEquals(1, generated)
        assertEquals(defaults, firstRead)
        assertEquals(defaults, library.list())
        assertEquals(1, commits)
        defaults.forEach { library.delete(it.name) }
        val afterDelete = library.list()
        library.listOrSeed { error("persisted empty library must not reseed") }
        assertEquals(afterDelete, library.list())
        assertEquals(9, commits)
    }
    @Test fun targetIsolationAndPartialNativeFailure() {
        val port = Port()
        val adapter = InstrumentFxPresetAdapter(port)
        val p = preset()
        assertFalse(adapter.apply("wrong", 4, p).success)
        assertTrue(port.calls.isEmpty())
        port.failAt = "enabled:set-b:4:lsp.parametric_eq"
        val result = adapter.apply("set-b", 4, p)
        assertFalse(result.success)
        assertEquals(1, result.applied)
        assertTrue(port.calls.all { it.startsWith("set-b:4:") || it.startsWith("enabled:set-b:4:") })
    }

    @Test fun lateInvalidDescriptorPreflightPreventsAllWritesAndDelayEnableIsLast() {
        val port = Port()
        val adapter = InstrumentFxPresetAdapter(port)
        val invalidLate = preset().copy(effects = preset().effects.mapIndexed { index, effect ->
            if (index == 7) effect.copy(parameters = mapOf(999 to .5f)) else effect
        })
        val rejected = adapter.apply("set-b", 4, invalidLate)
        assertFalse(rejected.success)
        assertTrue(port.calls.isEmpty())

        val result = adapter.apply("set-b", 4, preset())
        assertTrue(result.success)
        assertTrue(port.calls.indexOf("delay:set-b:4:5") < port.calls.indexOf("delay:set-b:4:0"))
    }

    @Test fun emptyUnavailableEffectSnapshotDoesNotDisableAvailableTargetEffect() {
        val port = Port()
        val adapter = InstrumentFxPresetAdapter(port)
        val sparse = preset().copy(
            effects = InstrumentFxPresetCodec.insertIds.map { id ->
                InstrumentFxPreset.Insert(id, id == "lsp.flanger", emptyMap())
            },
            delay = InstrumentFxPreset.Delay(false, emptyMap())
        )
        val result = adapter.apply("set-b", 4, sparse)
        assertTrue(result.success)
        assertTrue(result.skipped.contains("lsp.flanger (no captured parameters)"))
        assertFalse(port.calls.any { it.contains("lsp.flanger") })
    }
}
