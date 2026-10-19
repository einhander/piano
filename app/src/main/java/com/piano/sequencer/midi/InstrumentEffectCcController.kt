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
    private data class ParamKey(val target: Int, val effect: String, val parameter: String)
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
        val key = ParamKey(selected.targetInstrumentChannel, selected.effectId, selected.parameterId)
        val cached = synchronized(lock) { cache[key] }
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
    fun manualParameterChanged(target: Int, effect: String, parameter: String, value: Float) {
        synchronized(lock) { cache[ParamKey(target, effect, parameter)]?.current = value }
    }
    private fun invalidateCache() {
        cacheReady = false
        service?.let { svc -> executor.execute { if (service === svc) refreshCache(svc) } }
    }
    private fun refreshCache(svc: PlaybackService) {
        try {
            mappings().map { ParamKey(it.targetInstrumentChannel, it.effectId, it.parameterId) }.distinct().forEach { key ->
                if (service !== svc) return
                val id = key.parameter.toIntOrNull() ?: return@forEach
                val index = (0 until svc.getInstrumentEffectParamCount(key.effect)).firstOrNull { i -> svc.getInstrumentEffectParamInfo(key.effect, i)?.getOrNull(0)?.toInt() == id } ?: return@forEach
                val info = svc.getInstrumentEffectParamInfo(key.effect, index) ?: return@forEach
                val value = svc.getInstrumentEffectParameter(key.target, key.effect, id)
                val descriptor = effectParameterFromNativeInfo(info, svc.getInstrumentEffectParamName(key.effect, index), value, key.effect) ?: return@forEach
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
                 if (!svc.setInstrumentEffectParameter(event.key.target, event.key.effect, event.parameterId, event.value)) android.util.Log.w("EffectCc", "Setter rejected ${event.key.effect}/${event.parameterId}")
                else synchronized(lock) { cache[event.key]?.current = event.value }
             } catch (e: Exception) { android.util.Log.e("EffectCc", "MIDI dispatch failed", e) } }
        }
    }
    private fun persist() { prefs.edit().putString(KEY, encode(coordinator.mappings())).apply() }
    companion object {
        private const val KEY = "instrument_effect_cc_mappings_v1"
        fun encode(items: List<EffectCcMapping>): String = JSONObject().put("version", 1).put("mappings", JSONArray().also { a -> items.forEach { m -> a.put(JSONObject().put("source",m.source).put("channel",m.inputChannel ?: -1).put("cc",m.controller).put("target",m.targetInstrumentChannel).put("effect",m.effectId).put("parameter",m.parameterId).put("min",m.minRaw.toDouble()).put("max",m.maxRaw.toDouble()).put("invert",m.invert).put("pickup",m.pickupMode.name).put("disposition",m.disposition.name)) } }).toString()
        fun decode(text: String?): List<EffectCcMapping> {
            if (text == null) return emptyList()
            return try {
            val root = JSONObject(text); require(root.getInt("version") == 1)
            val array = root.getJSONArray("mappings")
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                EffectCcMapping(if (o.isNull("source")) null else o.getString("source"), o.optInt("channel", -1).takeIf { it >= 0 }, o.getInt("cc"), o.getInt("target"), o.getString("effect"), o.getString("parameter"), o.getDouble("min").toFloat(), o.getDouble("max").toFloat(), o.optBoolean("invert"), PickupMode.valueOf(o.optString("pickup", PickupMode.SOFT_TAKEOVER.name)), CcDisposition.valueOf(o.optString("disposition", if (o.optBoolean("consume")) "CONSUME" else "PASS")))
            }.let { items -> items.takeIf { it.indices.all { i -> (0 until i).none { j -> collides(items[i], items[j]) } } } ?: emptyList() }
        } catch (_: Exception) { emptyList() }
        }
        private fun collides(a: EffectCcMapping,b: EffectCcMapping) = (a.source==null || b.source==null || a.source==b.source) && a.controller==b.controller && (a.inputChannel==null || b.inputChannel==null || a.inputChannel==b.inputChannel)
    }
}
