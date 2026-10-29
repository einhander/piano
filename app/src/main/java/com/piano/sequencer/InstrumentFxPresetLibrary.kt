package com.piano.sequencer

import com.piano.sequencer.midi.FileInstrumentSetStore
import com.piano.sequencer.midi.MAIN_INSTRUMENT_SET_ID
import com.piano.sequencer.midi.resolveInstrumentIdentityChannel
import com.piano.sequencer.service.PlaybackService
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** Raw native state only. Deliberately excludes routing, programs and shared/master effects. */
data class InstrumentFxPreset(val name: String, val rackEnabled: Boolean, val effects: List<Insert>, val delay: Delay) {
    data class Insert(val effectId: String, val enabled: Boolean, val parameters: Map<Int, Float>)
    data class Delay(val enabled: Boolean, val parameters: Map<Int, Float>)
}

object InstrumentFxPresetCodec {
    private const val VERSION = 1
    private val json=Json { ignoreUnknownKeys=false; encodeDefaults=true }
    val insertIds = listOf("lsp.parametric_eq", "lsp.compressor", "lsp.limiter", "lsp.chorus", "lsp.clipper", "lsp.filter", "lsp.phaser", "lsp.flanger")

    data class LibraryData(val presets: List<InstrumentFxPreset>, val factoryRevision: Int)

    @Serializable private data class Library(val version:Int,val presets:List<Entry>,val factoryRevision:Int=0)
    @Serializable private data class Entry(val name:String,val rackEnabled:Boolean,val effects:List<InsertEntry>,val delay:DelayEntry)
    @Serializable private data class InsertEntry(val id:String,val enabled:Boolean,val params:Map<String,Float>)
    @Serializable private data class DelayEntry(val enabled:Boolean,val params:Map<String,Float>)
    fun encode(presets: List<InstrumentFxPreset>, factoryRevision: Int = 0): String =
        json.encodeToString(Library(VERSION, presets.map { p ->
            Entry(
                p.name,
                p.rackEnabled,
                p.effects.map { InsertEntry(it.effectId, it.enabled, it.parameters.mapKeys { e -> e.key.toString() }) },
                DelayEntry(p.delay.enabled, p.delay.parameters.mapKeys { e -> e.key.toString() })
            )
        }, factoryRevision))

    fun decode(payload: String): List<InstrumentFxPreset> = decodeLibrary(payload).presets

    fun decodeLibrary(payload: String): LibraryData {
        val root=json.decodeFromString<Library>(payload)
        require(root.version==VERSION) { "Unsupported preset library version" }
        require(root.factoryRevision >= 0) { "Invalid factory revision" }
        val names=mutableSetOf<String>()
        val decoded = root.presets.map { p ->
            InstrumentFxPresetLibrary.validateName(p.name)
            require(names.add(p.name.lowercase())) { "Duplicate preset name" }
            require(p.effects.size==insertIds.size) { "Invalid insert count" }
            val inserts=p.effects.mapIndexed { i,e -> require(e.id==insertIds[i]) { "Unknown or reordered effect ID: ${e.id}" }; InstrumentFxPreset.Insert(e.id,e.enabled,readParams(e.params,null)) }
            val delay=readParams(p.delay.params,0..5)
            require(delay.isEmpty() || delay.keys==(0..5).toSet()) { "Delay parameters must be complete" }
            InstrumentFxPreset(p.name,p.rackEnabled,inserts,InstrumentFxPreset.Delay(p.delay.enabled,delay))
        }
        return LibraryData(decoded, root.factoryRevision)
    }
    private fun readParams(o: Map<String, Float>, allowed: IntRange?): Map<Int, Float> = buildMap {
        o.forEach { (key, value) ->
            val id = key.toIntOrNull() ?: error("Invalid parameter ID")
            require(id >= 0 && (allowed == null || id in allowed) && value.isFinite()) { "Invalid parameter $id" }
            require(!containsKey(id)) { "Duplicate numeric parameter ID $id" }
            put(id, value)
        }
    }
}

interface InstrumentFxPresetStorage {
    fun read(): String?
    fun commit(value: String): Boolean
}
class InstrumentFxPresetLibrary(private val storage: InstrumentFxPresetStorage) {
    fun list(): List<InstrumentFxPreset> = load().presets

    /** Seed only genuinely uninitialized storage. Encoded empty libraries stay empty forever. */
    @Synchronized
    fun seedDefaultsIfAbsent(defaults: List<InstrumentFxPreset>) {
        if (readExisting() != null) return
        val validated = defaults.onEach(::validatePreset)
        require(validated.map { it.name.lowercase() }.toSet().size == validated.size) {
            "Default preset names must be unique"
        }
        commitSeed(validated, CURRENT_FACTORY_REVISION)
    }

    /** First-read entry point: invoke native factory only when storage has never been initialized. */
    @Synchronized
    fun listOrSeed(defaults: () -> List<InstrumentFxPreset>): List<InstrumentFxPreset> {
        val existing = readExisting()
        if (existing == null) {
            val validated = captureDefaults(defaults)
            commitSeed(validated, CURRENT_FACTORY_REVISION)
            return validated
        }
        if (existing.factoryRevision >= CURRENT_FACTORY_REVISION) return existing.presets

        // Add only this revision's new factory rows. Existing built-ins and user rows remain untouched.
        val additions = captureDefaults(defaults).filter { it.name in FACTORY_ADDITION_NAMES }
        require(additions.map { it.name }.toSet() == FACTORY_ADDITION_NAMES) {
            "Factory upgrade did not provide all revision $CURRENT_FACTORY_REVISION presets"
        }
        val existingNames = existing.presets.map { it.name.lowercase() }.toSet()
        val merged = existing.presets + additions.filter { it.name.lowercase() !in existingNames }
        commitSeed(merged, CURRENT_FACTORY_REVISION)
        return merged
    }

    private fun captureDefaults(defaults: () -> List<InstrumentFxPreset>): List<InstrumentFxPreset> {
        val initial = try {
            defaults()
        } catch (error: Exception) {
            throw IllegalStateException("Cannot capture factory presets: ${error.message ?: error.javaClass.simpleName}", error)
        }
        val validated = try {
            initial.onEach(::validatePreset)
        } catch (error: Exception) {
            throw IllegalStateException("Cannot validate factory presets: ${error.message ?: error.javaClass.simpleName}", error)
        }
        require(validated.map { it.name.lowercase() }.toSet().size == validated.size) {
            "Default preset names must be unique"
        }
        return validated
    }

    @Synchronized
    fun save(preset: InstrumentFxPreset, overwrite: Boolean = false) {
        validatePreset(preset)
        val old = load()
        val idx = old.presets.indexOfFirst { it.name.equals(preset.name, true) }
        require(idx < 0 || overwrite) { "Preset name already exists" }
        val next = old.presets.toMutableList()
        if (idx < 0) next.add(preset) else next[idx] = preset
        check(storage.commit(InstrumentFxPresetCodec.encode(next, old.factoryRevision))) { "Preset library write failed" }
    }

    @Synchronized
    fun delete(name: String) {
        val old = load()
        val next = old.presets.filterNot { it.name.equals(name, true) }
        require(next.size != old.presets.size) { "Preset not found" }
        check(storage.commit(InstrumentFxPresetCodec.encode(next, old.factoryRevision))) { "Preset library write failed" }
    }

    private fun load() = readExisting() ?: InstrumentFxPresetCodec.LibraryData(emptyList(), CURRENT_FACTORY_REVISION)

    private fun readExisting(): InstrumentFxPresetCodec.LibraryData? = try {
        storage.read()?.let(InstrumentFxPresetCodec::decodeLibrary)
    } catch (error: Exception) {
        throw IllegalStateException("Cannot read preset library: ${error.message ?: error.javaClass.simpleName}", error)
    }

    private fun commitSeed(presets: List<InstrumentFxPreset>, revision: Int) {
        val encoded = InstrumentFxPresetCodec.encode(presets, revision)
        val committed = try {
            storage.commit(encoded)
        } catch (error: Exception) {
            throw IllegalStateException("Cannot commit default preset library: ${error.message ?: error.javaClass.simpleName}", error)
        }
        check(committed) { "Cannot commit default preset library: preferences commit returned false" }
    }

    companion object {
        private const val CURRENT_FACTORY_REVISION = 1
        private val FACTORY_ADDITION_NAMES = setOf(
            "Guitar · Distortion",
            "Organ · Classic",
            "Strings · Distortion"
        )
        fun validateName(name: String) {
            require(name.trim() == name && name.length in 1..40 && name.none { it.isISOControl() }) {
                "Name must be 1–40 printable characters"
            }
        }

        fun validatePreset(p: InstrumentFxPreset) {
            validateName(p.name)
            require(p.effects.map { it.effectId } == InstrumentFxPresetCodec.insertIds) {
                "Preset must contain inserts in stable ID order"
            }
            p.effects.forEach {
                require(it.parameters.keys.all { id -> id >= 0 } && it.parameters.values.all(Float::isFinite)) {
                    "Invalid insert parameter"
                }
            }
            require(
                (p.delay.parameters.isEmpty() || p.delay.parameters.keys == (0..5).toSet()) &&
                    p.delay.parameters.values.all(Float::isFinite)
            ) { "Invalid delay parameters" }
        }
    }
}

/** Adapter port intentionally supports pure fake-port tests. */
interface InstrumentFxChannelPort {
    fun validTarget(setId: String, localChannel: Int): Boolean
    fun count(id: String): Int
    fun info(id: String, index: Int): FloatArray?
    fun delayInfo(parameter: Int): FloatArray?
    fun available(setId: String, channel: Int, id: String): Boolean
    fun setParam(setId: String, channel: Int, id: String, param: Int, value: Float): Boolean
    fun setEnabled(setId: String, channel: Int, id: String, enabled: Boolean): Boolean
    fun setDelay(setId: String, channel: Int, param: Int, value: Float): Boolean
    fun delayAvailable(): Boolean
    fun setRack(setId: String, channel: Int, enabled: Boolean): Boolean
    fun getParam(setId: String, channel: Int, id: String, param: Int): Float
    fun isEnabled(setId: String, channel: Int, id: String): Boolean
    fun getDelay(setId: String, channel: Int, param: Int): Float
    fun rackEnabled(setId: String, channel: Int): Boolean
}
data class PresetApplyResult(val success: Boolean, val message: String, val applied: Int, val skipped: List<String>)

class InstrumentFxPresetAdapter(private val port: InstrumentFxChannelPort) {
    /** Build complete presets from active native descriptor defaults; call on worker thread. */
    fun factoryDefaults(setId: String, channel: Int): List<InstrumentFxPreset> {
        require(port.validTarget(setId, channel)) { "Invalid instrument target" }
        val nativeDescriptors = mutableMapOf<String, Map<Int, FloatArray>>()
        val nativeDefaults = InstrumentFxPresetCodec.insertIds.map { id ->
            if (!port.available(setId, channel, id)) {
                InstrumentFxPreset.Insert(id, false, emptyMap())
            } else {
                val descriptors = (0 until port.count(id)).mapNotNull { index ->
                    val info = port.info(id, index)?.takeIf { it.size >= 7 } ?: return@mapNotNull null
                    val idValue = info[0]
                    if (!idValue.isFinite() || idValue.toInt().toFloat() != idValue ||
                        !info[1].isFinite() || !info[2].isFinite() || !info[3].isFinite() ||
                        info[1] > info[2] || info[3] !in info[1]..info[2]
                    ) return@mapNotNull null
                    idValue.toInt() to info
                }.toMap()
                require(descriptors.size == port.count(id)) { "Invalid native descriptors for $id" }
                nativeDescriptors[id] = descriptors
                val parameters = descriptors.mapValues { (_, info) -> info[3] }
                InstrumentFxPreset.Insert(id, false, parameters)
            }
        }
        val delayParameters = if (port.delayAvailable()) (0..5).associateWith { parameter ->
            val info = port.delayInfo(parameter)
            require(info != null && info.size >= 7 && info[0].toInt() == parameter && info[3].isFinite()) {
                "Missing native delay descriptor $parameter"
            }
            info[3]
        } else emptyMap()

        fun build(
            name: String,
            active: Set<String>,
            delay: Boolean = false,
            delayWet: Float = .30f,
            overrides: Map<String, Map<Int, Float>> = emptyMap()
        ): InstrumentFxPreset {
            val inserts = nativeDefaults.map { insert ->
                val changed = insert.parameters.toMutableMap()
                overrides[insert.effectId].orEmpty().forEach { (parameter, value) ->
                    val descriptor = nativeDescriptors[insert.effectId]?.get(parameter)
                    // Missing native effect stays an empty snapshot; apply skips it safely.
                    if (descriptor == null && insert.parameters.isEmpty()) return@forEach
                    requireNotNull(descriptor) { "Unknown factory parameter ${insert.effectId}.$parameter" }
                    require(value.isFinite() && value in descriptor[1]..descriptor[2]) {
                        "Factory parameter out of native bounds: ${insert.effectId}.$parameter"
                    }
                    changed[parameter] = value
                }
                insert.copy(enabled = insert.effectId in active, parameters = changed)
            }
            val delayValues = if (delayParameters.isEmpty()) emptyMap() else delayParameters.toMutableMap().apply {
                // Native delay identity is parameter 0; other values use the audited delay defaults.
                put(0, if (delay) 1f else 0f)
                if (delay) {
                    put(1, 250f)
                    put(2, .35f)
                    put(3, delayWet)
                }
            }
            delayValues.forEach { (parameter, value) ->
                val descriptor = port.delayInfo(parameter)!!
                require(value.isFinite() && value in descriptor[1]..descriptor[2]) {
                    "Factory delay value out of native bounds: $parameter"
                }
            }
            return InstrumentFxPreset(name, true, inserts, InstrumentFxPreset.Delay(delay, delayValues))
        }

        val clean = setOf("lsp.parametric_eq")
        val chorus = clean + "lsp.chorus"
        val crunch = clean + setOf("lsp.compressor", "lsp.clipper")
        val lead = clean + setOf("lsp.compressor", "lsp.chorus")
        val guitarDistortion = clean + setOf("lsp.compressor", "lsp.clipper")
        val organRotary = setOf("lsp.chorus", "lsp.phaser", "lsp.clipper")
        val stringsDistortion = setOf("lsp.compressor", "lsp.clipper", "lsp.chorus")
        return listOf(
            build("Guitar · Clean Chorus", chorus, overrides = mapOf("lsp.chorus" to mapOf(304 to 20f))),
            build("Guitar · Crunch", crunch, overrides = mapOf("lsp.clipper" to mapOf(320 to 1.5f))),
            build("Guitar · Lead", lead, delay = true, delayWet = .12f, overrides = mapOf("lsp.chorus" to mapOf(304 to 25f))),
            build(
                "Violin · Warm Solo",
                chorus + "lsp.filter",
                overrides = mapOf(
                    "lsp.chorus" to mapOf(304 to 12f),
                    "lsp.filter" to mapOf(360 to 9000f, 362 to 0f)
                )
            ),
            build(
                "Violin · Spacious",
                chorus + "lsp.phaser",
                delay = true,
                delayWet = .18f,
                overrides = mapOf("lsp.chorus" to mapOf(304 to 15f))
            ),
            build(
                "Piano · Clean",
                clean,
                overrides = mapOf("lsp.parametric_eq" to mapOf(30 to 5f, 33 to 200f, 34 to 1.122018f))
            ),
            build("Piano · Warm", chorus, overrides = mapOf("lsp.chorus" to mapOf(304 to 18f))),
            build(
                "Piano · Wide",
                chorus + "lsp.flanger",
                overrides = mapOf("lsp.chorus" to mapOf(304 to 18f), "lsp.flanger" to mapOf(403 to 20f))
            ),
            build(
                "Guitar · Distortion",
                guitarDistortion,
                overrides = mapOf("lsp.clipper" to mapOf(320 to 2f, 322 to -4f))
            ),
            build(
                "Organ · Classic",
                organRotary,
                overrides = mapOf(
                    "lsp.chorus" to mapOf(304 to 15f),
                    "lsp.phaser" to mapOf(380 to .25f, 384 to 18f),
                    "lsp.clipper" to mapOf(320 to 1.15f)
                )
            ),
            build(
                "Strings · Distortion",
                stringsDistortion,
                overrides = mapOf(
                    "lsp.chorus" to mapOf(304 to 10f),
                    "lsp.clipper" to mapOf(320 to 1.35f, 322 to -2f)
                )
            )
        ).onEach(InstrumentFxPresetLibrary::validatePreset)
    }

    fun capture(setId: String, channel: Int, name: String): InstrumentFxPreset {
        InstrumentFxPresetLibrary.validateName(name)
        require(port.validTarget(setId, channel)) { "Invalid instrument target" }
        val effects = InstrumentFxPresetCodec.insertIds.map { id ->
            if (!port.available(setId, channel, id)) {
                InstrumentFxPreset.Insert(id, false, emptyMap())
            } else {
                val params = (0 until port.count(id)).mapNotNull { index ->
                    val info = port.info(id, index)?.takeIf { it.size >= 7 } ?: return@mapNotNull null
                    val parameter = info[0]
                    if (!parameter.isFinite() || parameter.toInt().toFloat() != parameter) return@mapNotNull null
                    val idValue = parameter.toInt()
                    idValue to port.getParam(setId, channel, id, idValue)
                }.toMap()
                InstrumentFxPreset.Insert(id, port.isEnabled(setId, channel, id), params)
            }
        }
        val delayParams = if (port.delayAvailable()) {
            (0..5).associateWith { port.getDelay(setId, channel, it) }
        } else {
            emptyMap()
        }
        return InstrumentFxPreset(
            name,
            port.rackEnabled(setId, channel),
            effects,
            InstrumentFxPreset.Delay(delayParams[0]?.let { it >= .5f } ?: false, delayParams)
        )
    }
    fun apply(setId: String, channel: Int, preset: InstrumentFxPreset): PresetApplyResult {
        if (!port.validTarget(setId, channel)) {
            return PresetApplyResult(false, "Invalid instrument target", 0, emptyList())
        }
        try {
            InstrumentFxPresetLibrary.validatePreset(preset)
        } catch (e: Exception) {
            return PresetApplyResult(false, e.message ?: "Invalid preset", 0, emptyList())
        }
        var applied = 0
        val skipped = mutableListOf<String>()
        fun nativeFailure(setting: String) = PresetApplyResult(
            false,
            "Native setter rejected $setting; earlier changes may remain",
            applied,
            skipped
        )

        // Resolve and validate every active descriptor before first write.
        val plans = mutableListOf<InstrumentFxPreset.Insert>()
        for (effect in preset.effects) {
            if (!port.available(setId, channel, effect.effectId)) {
                skipped.add(effect.effectId)
                continue
            }
            if (effect.parameters.isEmpty()) {
                skipped.add("${effect.effectId} (no captured parameters)")
                continue
            }
            val descriptors = (0 until port.count(effect.effectId)).mapNotNull { index ->
                port.info(effect.effectId, index)
                    ?.takeIf { info ->
                        info.size >= 7 && info[0].isFinite() &&
                            info[0].toInt().toFloat() == info[0] && info[1].isFinite() && info[2].isFinite()
                    }
                    ?.let { it[0].toInt() to it }
            }.toMap()
            if (effect.parameters.keys.any { it !in descriptors }) {
                return PresetApplyResult(false, "Invalid descriptor parameter for ${effect.effectId}", 0, skipped)
            }
            for ((parameter, value) in effect.parameters) {
                val descriptor = descriptors.getValue(parameter)
                if (!value.isFinite() || value < descriptor[1] || value > descriptor[2]) {
                    return PresetApplyResult(false, "Parameter out of descriptor bounds", 0, skipped)
                }
            }
            plans.add(effect)
        }

        val delayAvailable = port.delayAvailable()
        if (delayAvailable && preset.delay.parameters.isNotEmpty()) {
            for ((parameter, value) in preset.delay.parameters) {
                val descriptor = port.delayInfo(parameter)
                    ?: return PresetApplyResult(false, "Missing delay descriptor $parameter", 0, skipped)
                if (descriptor.size < 7 || !descriptor[0].isFinite() || descriptor[0].toInt() != parameter ||
                    !descriptor[1].isFinite() || !descriptor[2].isFinite() || !value.isFinite() ||
                    value < descriptor[1] || value > descriptor[2]
                ) {
                    return PresetApplyResult(false, "Invalid delay descriptor/value $parameter", 0, skipped)
                }
            }
        } else skipped.add("native.delay")

        for (effect in plans) {
            for ((parameter, value) in effect.parameters) {
                if (!port.setParam(setId, channel, effect.effectId, parameter, value)) {
                    return nativeFailure("${effect.effectId}.$parameter")
                }
                applied++
            }
            if (!port.setEnabled(setId, channel, effect.effectId, effect.enabled)) {
                return nativeFailure("${effect.effectId} enabled")
            }
            applied++
        }
        if (delayAvailable && preset.delay.parameters.isNotEmpty()) {
            for ((parameter, value) in preset.delay.parameters.toSortedMap().filterKeys { it != 0 }) {
                if (!port.setDelay(setId, channel, parameter, value)) return nativeFailure("delay.$parameter")
                applied++
            }
            if (!port.setDelay(setId, channel, 0, if (preset.delay.enabled) 1f else 0f)) {
                return nativeFailure("delay enabled")
            }
            applied++
        }
        if (!port.setRack(setId, channel, preset.rackEnabled)) return nativeFailure("rack enabled")
        applied++
        val message = if (skipped.isEmpty()) "Preset applied" else "Preset applied with unavailable effects skipped"
        return PresetApplyResult(true, message, applied, skipped)
    }
}

class PlaybackServiceInstrumentFxPort(private val service: PlaybackService) : InstrumentFxChannelPort {
    override fun validTarget(setId: String, localChannel: Int): Boolean {
        if (localChannel !in 0..15) return false
        if (setId == MAIN_INSTRUMENT_SET_ID) return true
        return FileInstrumentSetStore.get(service).all().any { set ->
            set.id == setId && set.channelBase in 16..112 && set.channelBase % 16 == 0 &&
                resolveInstrumentIdentityChannel(setId, localChannel, set.channelBase) in 0..127
        }
    }

    private fun absolute(setId: String, channel: Int): Int =
        if (setId == MAIN_INSTRUMENT_SET_ID) channel
        else FileInstrumentSetStore.get(service).all().first { it.id == setId }.channelBase + channel

    override fun count(id: String) = service.getInstrumentEffectParamCount(id)
    override fun info(id: String, index: Int) = service.getInstrumentEffectParamInfo(id, index)
    override fun delayInfo(parameter: Int) = service.getInstrumentDelayParamInfo(parameter)
    override fun available(setId: String, channel: Int, id: String) =
        service.isInstrumentEffectAvailable(absolute(setId, channel), id)
    override fun setParam(setId: String, channel: Int, id: String, param: Int, value: Float) =
        service.setInstrumentEffectParameter(setId, channel, id, param, value)
    override fun setEnabled(setId: String, channel: Int, id: String, enabled: Boolean) =
        service.setInstrumentEffectEnabled(setId, channel, id, enabled)
    override fun setDelay(setId: String, channel: Int, param: Int, value: Float) =
        service.setInstrumentDelayParameter(setId, channel, param, value)
    override fun delayAvailable() = service.isInstrumentDelayAvailable()
    override fun setRack(setId: String, channel: Int, enabled: Boolean) =
        service.setInstrumentRackEnabled(setId, channel, enabled)
    override fun getParam(setId: String, channel: Int, id: String, param: Int) =
        service.getInstrumentEffectParameter(setId, channel, id, param)
    override fun isEnabled(setId: String, channel: Int, id: String) =
        service.isInstrumentEffectEnabled(setId, channel, id)
    override fun getDelay(setId: String, channel: Int, param: Int) =
        service.getInstrumentDelayParameter(setId, channel, param)
    override fun rackEnabled(setId: String, channel: Int) = service.isInstrumentRackEnabled(setId, channel)
}

/** Separate app-private preference key; caller supplies storage backed by application context. */
class PreferenceInstrumentFxPresetStorage(private val context: android.content.Context) : InstrumentFxPresetStorage {
    private val prefs
        get() = context.getSharedPreferences("piano_instrument_fx_presets", android.content.Context.MODE_PRIVATE)

    override fun read() = prefs.getString("library_v1", null)
    override fun commit(value: String) = prefs.edit().putString("library_v1", value).commit()
}
