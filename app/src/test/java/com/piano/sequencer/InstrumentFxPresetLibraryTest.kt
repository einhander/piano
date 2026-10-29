package com.piano.sequencer

import org.junit.Assert.*
import org.junit.Test

class InstrumentFxPresetLibraryTest {
    private data class NativeParameter(
        val id: Int,
        val stableName: String,
        val minimum: Float,
        val maximum: Float,
        val defaultValue: Float
    ) {
        fun jniInfo() = floatArrayOf(id.toFloat(), minimum, maximum, defaultValue, 0f, 0f, 0f)
    }

    private object NativeCatalog {
        val byEffect: Map<String, List<NativeParameter>> by lazy {
            val stream = InstrumentFxPresetLibraryTest::class.java.getResourceAsStream("/instrument_fx_native_catalog.tsv")
                ?: error("Missing host-generated native catalog fixture")
            stream.bufferedReader().useLines { lines ->
                lines.filter { it.isNotBlank() }.map { line ->
                    val columns = line.split('\t')
                    require(columns.size == 6) { "Invalid native fixture line: $line" }
                    columns[0] to NativeParameter(
                        columns[1].toInt(), columns[2], columns[3].toFloat(), columns[4].toFloat(), columns[5].toFloat()
                    )
                }.toList().groupBy({ it.first }, { it.second })
            }
        }

        fun parameter(effectId: String, id: Int) =
            byEffect.getValue(effectId).first { it.id == id }
    }

    private fun preset(name: String = "One") = InstrumentFxPreset(
        name,
        true,
        InstrumentFxPresetCodec.insertIds.map { id ->
            InstrumentFxPreset.Insert(id, true, NativeCatalog.byEffect.getValue(id).associate { it.id to it.defaultValue })
        },
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
        var failCommit = true
        val storage = object : InstrumentFxPresetStorage {
            override fun read() = raw
            override fun commit(value: String): Boolean {
                if (failCommit) return false
                raw = value
                return true
            }
        }
        val library = InstrumentFxPresetLibrary(storage)
        val commitError = assertThrows(IllegalStateException::class.java) {
            library.listOrSeed { listOf(preset()) }
        }
        assertTrue(commitError.message.orEmpty().contains("Cannot commit default preset library"))
        assertNull(raw)
        failCommit = false
        assertEquals(listOf(preset()), library.listOrSeed { listOf(preset()) })
        assertNotNull(raw)

        val readLibrary = InstrumentFxPresetLibrary(object : InstrumentFxPresetStorage {
            override fun read(): String? = error("disk read failure")
            override fun commit(value: String) = error("must not commit")
        })
        val readError = assertThrows(IllegalStateException::class.java) { readLibrary.listOrSeed { emptyList() } }
        assertTrue(readError.message.orEmpty().contains("Cannot read preset library"))

        val captureLibrary = InstrumentFxPresetLibrary(object : InstrumentFxPresetStorage {
            override fun read() = null
            override fun commit(value: String) = error("must not commit")
        })
        val captureError = assertThrows(IllegalStateException::class.java) {
            captureLibrary.listOrSeed { error("native catalog failure") }
        }
        assertTrue(captureError.message.orEmpty().contains("Cannot capture factory presets"))

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
        private fun record(value: String): Boolean { calls.add(value); return value != failAt }
        override fun validTarget(setId: String, localChannel: Int) = setId == "set-b" && localChannel == 4
        override fun count(id: String) = NativeCatalog.byEffect.getValue(id).size
        override fun info(id: String, index: Int) = NativeCatalog.byEffect.getValue(id).getOrNull(index)?.jniInfo()
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

    @Test fun firstRunSeedsElevenNativeDefaultBasedPresetsOnceOnly() {
        var raw: String? = null
        var commits = 0
        val storage = object : InstrumentFxPresetStorage {
            override fun read() = raw
            override fun commit(value: String): Boolean { commits++; raw = value; return true }
        }
        val library = InstrumentFxPresetLibrary(storage)
        val port = Port()
        val defaults = InstrumentFxPresetAdapter(port).factoryDefaults("set-b", 4)
        assertEquals(11, defaults.size)
        assertEquals(
            listOf(
                "Guitar · Clean Chorus", "Guitar · Crunch", "Guitar · Lead", "Violin · Warm Solo",
                "Violin · Spacious", "Piano · Clean", "Piano · Warm", "Piano · Wide",
                "Guitar · Distortion", "Organ · Classic", "Strings · Distortion"
            ),
            defaults.map { it.name }
        )
        defaults.forEach { preset ->
            assertEquals(InstrumentFxPresetCodec.insertIds, preset.effects.map { it.effectId })
            assertEquals(NativeCatalog.byEffect.mapValues { it.value.map(NativeParameter::id).toSet() },
                preset.effects.associate { it.effectId to it.parameters.keys })
            assertEquals((0..5).toSet(), preset.delay.parameters.keys)
            assertTrue(preset.effects.flatMap { it.parameters.values }.all(Float::isFinite))
            preset.effects.forEach { effect -> effect.parameters.forEach { (id, value) ->
                val descriptor = NativeCatalog.parameter(effect.effectId, id)
                assertTrue("${effect.effectId}.$id=$value", value in descriptor.minimum..descriptor.maximum)
            } }
        }
        val byName = defaults.associateBy { it.name }
        val cleanChorus = byName.getValue("Guitar · Clean Chorus")
        val warmViolin = byName.getValue("Violin · Warm Solo")
        assertEquals(20f, cleanChorus.effects.first { it.effectId == "lsp.chorus" }.parameters[304])
        assertEquals(12f, warmViolin.effects.first { it.effectId == "lsp.chorus" }.parameters[304])
        assertEquals(9000f, warmViolin.effects.first { it.effectId == "lsp.filter" }.parameters[360])
        assertEquals(1.5f, byName.getValue("Guitar · Crunch").effects.first { it.effectId == "lsp.clipper" }.parameters[320])
        assertEquals(25f, byName.getValue("Guitar · Lead").effects.first { it.effectId == "lsp.chorus" }.parameters[304])
        assertEquals(1f, NativeCatalog.parameter("lsp.clipper", 320).defaultValue)
        assertEquals(.12f, byName.getValue("Guitar · Lead").delay.parameters[3])
        assertEquals(.18f, byName.getValue("Violin · Spacious").delay.parameters[3])
        assertEquals(20f, byName.getValue("Piano · Wide").effects.first { it.effectId == "lsp.flanger" }.parameters[403])
        val pianoEq = byName.getValue("Piano · Clean").effects.first { it.effectId == "lsp.parametric_eq" }.parameters
        assertEquals(5f, pianoEq[30])
        assertEquals(200f, pianoEq[33])
        assertEquals(1.122018f, pianoEq[34])
        assertEquals("band0_type", NativeCatalog.parameter("lsp.parametric_eq", 30).stableName)
        assertEquals("band0_gain", NativeCatalog.parameter("lsp.parametric_eq", 34).stableName)
        assertEquals("feedback", NativeCatalog.parameter("lsp.chorus", 303).stableName)
        assertEquals("mix", NativeCatalog.parameter("lsp.chorus", 304).stableName)
        assertEquals("drive", NativeCatalog.parameter("lsp.clipper", 320).stableName)
        assertEquals("mix", NativeCatalog.parameter("lsp.flanger", 403).stableName)
        val guitarClipper = byName.getValue("Guitar · Distortion").effects.first { it.effectId == "lsp.clipper" }.parameters
        assertEquals(8f, guitarClipper[320])
        assertEquals(-6f, guitarClipper[322])
        assertEquals(.125f, guitarClipper[323])
        val organ = byName.getValue("Organ · Classic")
        assertTrue(organ.effects.first { it.effectId == "lsp.chorus" }.enabled)
        assertTrue(organ.effects.first { it.effectId == "lsp.phaser" }.enabled)
        assertEquals(18f, organ.effects.first { it.effectId == "lsp.phaser" }.parameters[384])
        assertEquals(1.15f, organ.effects.first { it.effectId == "lsp.clipper" }.parameters[320])
        val strings = byName.getValue("Strings · Distortion")
        assertEquals(9f, strings.effects.first { it.effectId == "lsp.clipper" }.parameters[320])
        assertEquals(-6f, strings.effects.first { it.effectId == "lsp.clipper" }.parameters[322])
        assertEquals(.111111f, strings.effects.first { it.effectId == "lsp.clipper" }.parameters[323])
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
        assertEquals(12, commits)
    }

    @Test fun legacyLibraryGetsOnlyNewFactoryRowsOnceWithoutReplacingUserRows() {
        val defaults = InstrumentFxPresetAdapter(Port()).factoryDefaults("set-b", 4)
        val legacy = defaults.take(8) + preset("My Own Preset")
        val legacyJson = InstrumentFxPresetCodec.encode(legacy).replace(",\"factoryRevision\":0", "")
        assertEquals(0, InstrumentFxPresetCodec.decodeLibrary(legacyJson).factoryRevision)
        var raw: String? = legacyJson
        var commits = 0
        val library = InstrumentFxPresetLibrary(object : InstrumentFxPresetStorage {
            override fun read() = raw
            override fun commit(value: String): Boolean { commits++; raw = value; return true }
        })
        var factoryCalls = 0
        val migrated = library.listOrSeed { factoryCalls++; defaults }
        assertEquals(1, factoryCalls)
        assertEquals(legacy, migrated.take(legacy.size))
        assertEquals(12, migrated.size)
        assertEquals(2, InstrumentFxPresetCodec.decodeLibrary(raw!!).factoryRevision)
        val addedNames = migrated.drop(legacy.size).map { it.name }.toSet()
        assertEquals(setOf("Guitar · Distortion", "Organ · Classic", "Strings · Distortion"), addedNames)
        migrated.filter { it.name in addedNames }.forEach { library.delete(it.name) }
        val deleted = library.list()
        library.listOrSeed { error("completed factory migration must not re-add deleted presets") }
        assertEquals(deleted, library.list())
        assertEquals(4, commits)
    }

    @Test fun migrationPreservesCaseInsensitiveNameCollisionAndOriginalBytesOnCommitFailure() {
        val defaults = InstrumentFxPresetAdapter(Port()).factoryDefaults("set-b", 4)
        val userCollision = preset("gUiTaR · dIsToRtIoN")
        val legacy = defaults.take(8) + userCollision
        val legacyJson = InstrumentFxPresetCodec.encode(legacy).replace(",\"factoryRevision\":0", "")
        var raw: String? = legacyJson
        var allowCommit = false
        val library = InstrumentFxPresetLibrary(object : InstrumentFxPresetStorage {
            override fun read() = raw
            override fun commit(value: String): Boolean {
                if (!allowCommit) return false
                raw = value
                return true
            }
        })
        assertThrows(IllegalStateException::class.java) { library.listOrSeed { defaults } }
        assertEquals(legacyJson, raw)
        allowCommit = true
        val migrated = library.listOrSeed { defaults }
        assertEquals(1, migrated.count { it.name.equals(userCollision.name, true) })
        assertEquals(userCollision, migrated.first { it.name.equals(userCollision.name, true) })
        assertEquals(1, migrated.count { it.name == "Organ · Classic" })
        assertEquals(1, migrated.count { it.name == "Strings · Distortion" })
        assertEquals(legacy.size + 2, migrated.size)
    }

    @Test fun revisionOneMigrationRepairsOnlyUneditedFactoryDistortionRows() {
        val defaults = InstrumentFxPresetAdapter(Port()).factoryDefaults("set-b", 4)
        fun revisionOne(name: String, drive: Float, threshold: Float) = defaults.first { it.name == name }.copy(
            effects = defaults.first { it.name == name }.effects.map { effect ->
                if (effect.effectId != "lsp.clipper") effect else effect.copy(
                    parameters = effect.parameters.toMutableMap().apply {
                        put(320, drive)
                        put(322, threshold)
                        put(323, .5f)
                    }
                )
            }
        )
        val oldGuitar = revisionOne("Guitar · Distortion", 2f, -4f)
        val editedStrings = revisionOne("Strings · Distortion", 4f, -2f)
        val revisionOneRows = defaults.filterNot { it.name == "Organ · Classic" }.map {
            when (it.name) {
                "Guitar · Distortion" -> oldGuitar
                "Strings · Distortion" -> editedStrings
                else -> it
            }
        } + preset("User Preset")
        val original = InstrumentFxPresetCodec.encode(revisionOneRows, factoryRevision = 1)
        var raw: String? = original
        var allowCommit = false
        val library = InstrumentFxPresetLibrary(object : InstrumentFxPresetStorage {
            override fun read() = raw
            override fun commit(value: String): Boolean {
                if (!allowCommit) return false
                raw = value
                return true
            }
        })
        assertThrows(IllegalStateException::class.java) { library.listOrSeed { defaults } }
        assertEquals(original, raw)
        allowCommit = true
        val migrated = library.listOrSeed { defaults }
        assertEquals(defaults.first { it.name == "Guitar · Distortion" }, migrated.first { it.name == "Guitar · Distortion" })
        assertEquals(editedStrings, migrated.first { it.name == "Strings · Distortion" })
        assertFalse(migrated.any { it.name == "Organ · Classic" })
        assertTrue(migrated.any { it.name == "User Preset" })
        assertEquals(2, InstrumentFxPresetCodec.decodeLibrary(raw!!).factoryRevision)

        library.delete("Guitar · Distortion")
        library.listOrSeed { error("revision-two deletion must remain deleted") }
        assertFalse(library.list().any { it.name == "Guitar · Distortion" })
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
        assertEquals(NativeCatalog.byEffect.getValue("lsp.parametric_eq").size, result.applied)
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
