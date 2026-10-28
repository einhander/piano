package com.piano.sequencer.midi

import android.content.Context
import com.piano.sequencer.effectParameterFromNativeInfo
import com.piano.sequencer.service.PlaybackService
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

data class EffectCcLearnResult(val mapping: EffectCcMapping? = null, val conflict: CcConflict? = null, val timedOut: Boolean = false)

/** Process-lifetime owner. MIDI ingress only mutates bounded memory state. */
class InstrumentEffectCcController(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("piano_prefs", Context.MODE_PRIVATE)
    private val coordinator = EffectMidiCoordinator()
    private val lock = Any()
    private val pending = LinkedHashMap<ParamKey, Pending>()
    private val cache = HashMap<ParamKey, Cached>()
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "effect-cc-worker").apply { isDaemon = true } }
    private val serviceAttachments = ServiceAttachmentRegistry<PlaybackService>()
    @Volatile private var service: PlaybackService? = null
    @Volatile private var cacheReady = false
    private data class ParamKey(val target: Int, val effect: String, val parameter: String, val scope: EffectCcScope = EffectCcScope.INSTRUMENT)
    private data class Cached(val descriptor: com.piano.sequencer.EffectParameter, var current: Float)
    private data class Pending(val key: ParamKey, val parameterId: Int, val value: Float)
    private var learning: Learn? = null
    private data class Learn(val template: EffectCcMapping, val callback: (EffectCcLearnResult) -> Unit, val timeout: java.util.concurrent.ScheduledFuture<*>)
    private var draining = false

    init { coordinator.restore(decode(prefs.getString(KEY, null))) }
    fun attach(owner: Any, svc: PlaybackService) {
        serviceAttachments.attach(owner, svc)
        service = serviceAttachments.active()
        cacheReady = false
        executor.execute {
            refreshCache(svc)
            synchronized(lock) {
                if (pending.isNotEmpty() && !draining) { draining = true; executor.execute(::drain) }
            }
        }
    }
    fun detach(owner: Any, svc: PlaybackService) {
        serviceAttachments.detach(owner, svc)
        service = serviceAttachments.active()
    }
    /** Refresh descriptors after native effect bundles and persisted state are ready. */
    fun refreshCache() {
        val svc = service ?: return
        executor.execute {
            if (service === svc) refreshCache(svc)
        }
    }
    fun mappings() = coordinator.mappings()
    /** Native-derived target metadata for CC Learn UI; includes toggles. */
    fun parameterMetadata(scope: EffectCcScope, effectId: String, parameterId: Int, targetChannel: Int = 0): com.piano.sequencer.EffectParameter? =
        when {
            scope == EffectCcScope.INSTRUMENT && parameterId == -1 && effectId in EffectCcMapping.INSTRUMENT_EFFECT_IDS -> instrumentEffectEnabledDescriptor(effectId)
            scope == EffectCcScope.INSTRUMENT && effectId == "native.delay" -> nativeDelayMetadata(parameterId)
            scope == EffectCcScope.GLOBAL && effectId == "native.delay" && parameterId == 6 -> sendDescriptorWithoutService(targetChannel)
            scope == EffectCcScope.GLOBAL -> sharedMetadata(effectId, parameterId)
            else -> synchronized(lock) { cache[ParamKey(targetChannel, effectId, parameterId.toString(), scope)]?.descriptor }
        }
    fun globalTargets(): List<com.piano.sequencer.EffectParameter> = synchronized(lock) {
        listOf("fluidsynth.reverb" to (0..4)).flatMap { (fx, ids) -> ids.mapNotNull { parameterMetadata(EffectCcScope.GLOBAL, fx, it) } }.distinctBy { it.stableId + it.displayName }
    }
    /** Query native-owned current values/defaults off-thread; safe before any mapping exists. */
    fun queryParameterMetadata(scope: EffectCcScope, effectId: String, parameterId: Int, targetChannel: Int = 0,
                               callback: (com.piano.sequencer.EffectParameter?) -> Unit) {
        val svc = service ?: run { callback(parameterMetadata(scope, effectId, parameterId, targetChannel)); return }
        executor.execute {
            val result = if (scope == EffectCcScope.INSTRUMENT && effectId == "native.delay") {
                val info = runCatching { svc.getInstrumentDelayParamInfo(parameterId) }.getOrNull()
                val value = runCatching { svc.getInstrumentDelayParameter(targetChannel, parameterId) }.getOrNull() ?: Float.NaN
                info?.let { sharedFxParameterFromNativeInfo(effectId, parameterId, it, value) }
            } else if (scope == EffectCcScope.GLOBAL) {
                val info = runCatching { svc.getSharedFxParamInfo(effectId, parameterId) }.getOrNull()
                val value = runCatching { svc.getSharedFxParameter(effectId, parameterId) }.getOrNull() ?: Float.NaN
                info?.let { sharedFxParameterFromNativeInfo(effectId, parameterId, it, value) }
            } else parameterMetadata(scope, effectId, parameterId, targetChannel)
            callback(result)
        }
    }
    private fun sendDescriptorWithoutService(channel: Int) = com.piano.sequencer.EffectParameter("6", "Delay Send", com.piano.sequencer.EffectParameterValue(0f), 0f, 1f, 0f, com.piano.sequencer.EffectParameterType.FLOAT, false, "%")
    private fun nativeDelayMetadata(id: Int) = sharedMetadata("native.delay", id)
    private fun sharedMetadata(scope: String, id: Int): com.piano.sequencer.EffectParameter? {
        data class Meta(val min: Float, val max: Float, val default: Float, val toggle: Boolean, val integer: Boolean, val name: String, val unit: String?)
        val m = when (scope) {
            "native.delay" -> when(id) {
                0 -> Meta(0f,1f,0f,true,true,"Enabled",null); 1 -> Meta(1f,2000f,250f,false,false,"Time","ms")
                2 -> Meta(0f,.90f,.35f,false,false,"Feedback","%"); 3 -> Meta(0f,1f,.30f,false,false,"Wet","%")
                4 -> Meta(0f,1f,0f,true,true,"Sync",null); 5 -> Meta(0f,4f,0f,false,true,"Division",null); else -> return null
            }
            "fluidsynth.reverb" -> when(id) {
                0 -> Meta(0f,1f,1f,true,true,"Enabled",null); 1 -> Meta(0f,1f,0f,false,false,"Room Size",null)
                2 -> Meta(0f,1f,0f,false,false,"Damping",null); 3 -> Meta(0f,100f,100f,false,false,"Width","%")
                4 -> Meta(0f,1f,0f,false,false,"Level",null); else -> return null
            }
            else -> return null
        }
        val type = if (m.toggle) com.piano.sequencer.EffectParameterType.BOOLEAN else if (m.integer) com.piano.sequencer.EffectParameterType.INTEGER else com.piano.sequencer.EffectParameterType.FLOAT
        return com.piano.sequencer.EffectParameter(id.toString(), m.name, com.piano.sequencer.EffectParameterValue(m.default), m.min, m.max, m.default, type, false, m.unit)
    }
    @Synchronized fun isLearning(): Boolean = learning != null
    @Synchronized fun add(mapping: EffectCcMapping): CcConflict? = coordinator.add(mapping).also { if (it == null) { persist(); invalidateCache() } }
    @Synchronized fun replace(mapping: EffectCcMapping, old: EffectCcMapping?): CcConflict? = coordinator.replace(mapping, old).also { if (it == null) { persist(); invalidateCache() } }
    @Synchronized fun remove(mapping: EffectCcMapping) { coordinator.remove(mapping); persist() }
    @Synchronized fun replace(items: List<EffectCcMapping>): CcConflict? {
        for (i in items.indices) for (j in 0 until i) if (collides(items[i], items[j])) return CcConflict(items[i], items[j])
        coordinator.restore(items); synchronized(lock) { pending.clear() }; persist(); invalidateCache(); return null
    }

    /** Starts a single 10-second capture; refused during MIDI-file trigger learn. */
    fun startLearn(template: EffectCcMapping, callback: (EffectCcLearnResult) -> Unit): Boolean =
        MidiFileLearnState.withTransitionLock {
            synchronized(this) {
                if (MidiFileLearnState.getState() == MidiFileLearnState.State.LEARNING) return@synchronized false
                learning?.timeout?.cancel(false)
                val timeout = executor.schedule({ finishLearn(EffectCcLearnResult(timedOut = true)) }, 10, TimeUnit.SECONDS)
                learning = Learn(template, callback, timeout)
                true
            }
        }
    @Synchronized fun cancelLearn() { learning?.timeout?.cancel(false); learning = null }
    @Synchronized fun cancelLearnWithResult() {
        val old = learning ?: return
        learning = null; old.timeout.cancel(false); old.callback(EffectCcLearnResult())
    }
    private fun finishLearn(result: EffectCcLearnResult) {
        val l = synchronized(this) { learning.also { learning = null } } ?: return
        l.timeout.cancel(false)
        l.callback(result)
    }
    fun onControlChange(source: String, channel: Int, cc: Int, value: Int): Boolean {
        require(channel in 0..15 && cc in 0..127 && value in 0..127)
        val learn = synchronized(this) { learning.also { if (it != null) { learning = null; it.timeout.cancel(false) } } }
        if (learn != null) {
            val mapping = learn.template.copy(source = source, inputChannel = channel, controller = cc)
            learn.callback(EffectCcLearnResult(mapping, coordinator.add(mapping)))
            if (coordinator.mappings().contains(mapping)) {
                cacheReady = false
                executor.execute { persist(); service?.let(::refreshCache) }
            }
            return true
        }
        val matching = coordinator.mappings().filter { it.matches(source, channel, cc) }
        val selected = matching.maxByOrNull { (if (it.source != null) 2 else 0) + if (it.inputChannel != null) 1 else 0 } ?: return false
        val consume = selected.disposition == CcDisposition.CONSUME
        val mappedTarget = effectCcTargetChannel(selected)
        val key = ParamKey(mappedTarget, selected.effectId, selected.parameterId, selected.scope)
        val cached = synchronized(lock) { cache[key] } ?: when {
            selected.scope == EffectCcScope.INSTRUMENT && selected.parameterId == "-1" -> Cached(instrumentEffectEnabledDescriptor(selected.effectId), 0f)
            selected.scope == EffectCcScope.GLOBAL && selected.effectId == "native.delay" && selected.parameterId == "6" -> Cached(sendDescriptorWithoutService(mappedTarget), 0f)
            else -> null
        }
        if (cached == null) {
            // No descriptor means unsupported, not a transient cache miss.
            if (!cacheReady && service != null) refreshCache()
            return consume && !cacheReady
        }
        if (service == null) return consume
        val current = synchronized(lock) { cached.current }
        var dispatched = false
        coordinator.onCc(source, channel, cc, value,
            { effect, parameter -> if (effect == key.effect && parameter == key.parameter) cached.descriptor else null },
            { effect, parameter -> if (effect == key.effect && parameter == key.parameter) current else 0f }) { _, _, raw ->
                dispatched = true
                synchronized(lock) { pending[key] = Pending(key, key.parameter.toInt(), raw) }
            }
        if (dispatched && service != null) synchronized(lock) {
            if (!draining) { draining = true; executor.execute(::drain) }
        }
        return consume
    }
    fun manualParameterChanged(target: Int, effect: String, parameter: String, value: Float, scope: EffectCcScope = EffectCcScope.INSTRUMENT) {
        synchronized(lock) { cache[ParamKey(target, effect, parameter, scope)]?.current = value }
    }
    private fun invalidateCache() {
        cacheReady = false
        service?.let { svc -> executor.execute { if (service === svc) refreshCache(svc) } }
    }
    private fun refreshCache(svc: PlaybackService) {
        try {
                mappings().map { ParamKey(effectCcTargetChannel(it), it.effectId, it.parameterId, it.scope) }.distinct().forEach { key ->
                if (service !== svc) return
                val id = key.parameter.toIntOrNull() ?: return@forEach
                if (key.scope == EffectCcScope.INSTRUMENT && id == -1) {
                    synchronized(lock) { cache[key] = Cached(instrumentEffectEnabledDescriptor(key.effect, svc.isInstrumentEffectEnabled(key.target, key.effect)), if (svc.isInstrumentEffectEnabled(key.target, key.effect)) 1f else 0f) }
                    return@forEach
                }
                if (key.scope == EffectCcScope.GLOBAL && key.effect == "native.delay" && id == 6) {
                    val value = svc.getDelaySend(key.target)
                    val descriptor = sendDescriptorWithoutService(key.target).copy(value = com.piano.sequencer.EffectParameterValue(value))
                    synchronized(lock) { cache[key] = Cached(descriptor, value) }; return@forEach
                }
                val info: FloatArray?; val name: String; val value: Float
                if (key.scope == EffectCcScope.GLOBAL) {
                    info = svc.getSharedFxParamInfo(key.effect, id); name = sharedName(key.effect, id)
                    value = svc.getSharedFxParameter(key.effect, id)
                } else if (key.effect == "native.delay") {
                    info = svc.getInstrumentDelayParamInfo(id); name = sharedName(key.effect, id)
                    value = svc.getInstrumentDelayParameter(key.target, id)
                } else {
                    val index = (0 until svc.getInstrumentEffectParamCount(key.effect)).firstOrNull { i -> svc.getInstrumentEffectParamInfo(key.effect, i)?.getOrNull(0)?.toInt() == id } ?: return@forEach
                    info = svc.getInstrumentEffectParamInfo(key.effect, index) ?: return@forEach
                    name = svc.getInstrumentEffectParamName(key.effect, index)
                    value = svc.getInstrumentEffectParameter(key.target, key.effect, id)
                }
                val descriptor = info?.let { effectParameterFromNativeInfo(it, name, value, key.effect) } ?: return@forEach
                synchronized(lock) { cache[key] = Cached(descriptor, value) }
            }
            if (service === svc) cacheReady = true
        } catch (e: Exception) { cacheReady = false; android.util.Log.e("EffectCc", "Cache refresh failed", e) }
    }
    private fun drain() {
        while (true) {
              val batch = synchronized(lock) { if (pending.isEmpty()) { draining = false; null } else pending.values.toList().also { pending.clear() } } ?: return
              val svc = service
              if (svc == null) {
                  synchronized(lock) {
                      batch.forEach { pending.putIfAbsent(it.key, it) }
                      draining = false
                  }
                  return
              }
              batch.forEach { event -> try {
                 if (service !== svc) {
                     synchronized(lock) { pending.putIfAbsent(event.key, event) }
                     return@forEach
                 }
                    val action = effectCcAction(event.key.scope, event.key.effect, event.parameterId.toString())
                   val ok = if (action == EffectCcAction.SHARED_PARAMETER || action == EffectCcAction.DELAY_SEND) {
                      val result = if (event.key.effect == "native.delay" && event.parameterId == 6) svc.setDelaySend(event.key.target, event.value)
                                   else svc.setSharedFxParameter(event.key.effect, event.parameterId, event.value)
                      if (result) {
                          if (event.key.effect == "native.delay" && event.parameterId == 6) com.piano.sequencer.EffectStateRestore.persistDelaySend(svc, event.key.target, event.value)
                          else com.piano.sequencer.EffectStateRestore.persistSharedParameter(svc, event.key.effect, event.parameterId, event.value)
                      }
                      result
                    } else if (event.key.scope == EffectCcScope.INSTRUMENT && event.key.effect == "native.delay") {
                        svc.setInstrumentDelayParameter(event.key.target, event.parameterId, event.value)
                    } else if (action == EffectCcAction.INSTRUMENT_ENABLED) {
                       val enabled = event.value >= .5f
                       val result = svc.setInstrumentEffectEnabled(event.key.target, event.key.effect, enabled)
                       if (result) com.piano.sequencer.EffectStateRestore.persistInstrumentEffect(svc, event.key.target, event.key.effect, enabled)
                       result
                   } else svc.setInstrumentEffectParameter(event.key.target, event.key.effect, event.parameterId, event.value).also { ok ->
                       if (ok) com.piano.sequencer.EffectStateRestore.persistInstrumentParameter(svc, event.key.target, event.key.effect, event.parameterId, event.value)
                   }
                   if (!ok) android.util.Log.w("EffectCc", "Setter rejected channel=${event.key.target} id=${event.parameterId} result=$ok")
                else synchronized(lock) { cache[event.key]?.current = event.value }
             } catch (e: Exception) { android.util.Log.e("EffectCc", "MIDI dispatch failed", e) } }
        }
    }
    private fun persist() { prefs.edit().putString(KEY, encode(coordinator.mappings())).apply() }
    companion object {
        private const val KEY = "instrument_effect_cc_mappings_v1"
        fun encode(items: List<EffectCcMapping>): String = JSONObject().put("version", 1).put("mappings", JSONArray().also { a -> items.forEach { m -> a.put(JSONObject().put("source",m.source).put("channel",m.inputChannel ?: -1).put("cc",m.controller).put("target",m.targetInstrumentChannel).put("effect",m.effectId).put("parameter",m.parameterId).put("min",m.minRaw.toDouble()).put("max",m.maxRaw.toDouble()).put("invert",m.invert).put("pickup",m.pickupMode.name).put("disposition",m.disposition.name).put("scope",m.scope.name)) } }).toString()
        fun decode(text: String?): List<EffectCcMapping> {
            if (text == null) return emptyList()
        return try {
            val root = JSONObject(text); require(root.getInt("version") == 1)
            val array = root.getJSONArray("mappings")
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                EffectCcMapping(if (o.isNull("source")) null else o.getString("source"), o.optInt("channel", -1).takeIf { it >= 0 }, o.getInt("cc"), o.getInt("target"), o.getString("effect"), o.getString("parameter"), o.getDouble("min").toFloat(), o.getDouble("max").toFloat(), o.optBoolean("invert"), PickupMode.valueOf(o.optString("pickup", PickupMode.SOFT_TAKEOVER.name)), CcDisposition.valueOf(o.optString("disposition", if (o.optBoolean("consume")) "CONSUME" else "PASS")), EffectCcScope.valueOf(o.optString("scope", EffectCcScope.INSTRUMENT.name)))
            }.let { items -> items.takeIf { it.indices.all { i -> (0 until i).none { j -> collides(items[i], items[j]) } } } ?: emptyList() }
        } catch (_: Exception) { emptyList() }
        }
        private fun collides(a: EffectCcMapping,b: EffectCcMapping) = (a.source==null || b.source==null || a.source==b.source) && a.controller==b.controller && (a.inputChannel==null || b.inputChannel==null || a.inputChannel==b.inputChannel)
    }
    private fun sharedName(scope: String, id: Int) = when(scope) {
        "native.delay" -> listOf("Enabled", "Time", "Feedback", "Wet", "Sync", "Division").getOrElse(id) { "Parameter $id" }
        "fluidsynth.reverb" -> listOf("Enabled", "Room Size", "Damping", "Width", "Level").getOrElse(id) { "Parameter $id" }
        else -> "Parameter $id"
    }
}

/** Decode worker-fetched native shared-FX metadata; independent of existing MIDI mappings/cache. */
fun sharedFxParameterFromNativeInfo(effectId: String, parameterId: Int, info: FloatArray, value: Float): com.piano.sequencer.EffectParameter? {
    if (effectId !in EffectCcMapping.GLOBAL_EFFECT_IDS || info.size < 7 || info[0].toInt() != parameterId) return null
    val name = when (effectId) {
        "native.delay" -> listOf("Enabled", "Time", "Feedback", "Wet", "Sync", "Division").getOrNull(parameterId)
            ?: if (parameterId == 6) "Delay Send" else null
        "fluidsynth.reverb" -> listOf("Enabled", "Room Size", "Damping", "Width", "Level").getOrNull(parameterId)
        else -> null
    } ?: return null
    val type = when {
        info[6] != 0f -> com.piano.sequencer.EffectParameterType.BOOLEAN
        info[5] != 0f -> com.piano.sequencer.EffectParameterType.INTEGER
        else -> com.piano.sequencer.EffectParameterType.FLOAT
    }
    return com.piano.sequencer.EffectParameter(parameterId.toString(), name,
        com.piano.sequencer.EffectParameterValue(value), info[1], info[2], info[3], type, info[4] != 0f)
}
