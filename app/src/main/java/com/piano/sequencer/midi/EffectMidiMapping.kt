package com.piano.sequencer.midi

import com.piano.sequencer.EffectParameter
import com.piano.sequencer.EffectParameterType
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

enum class PickupMode { SOFT_TAKEOVER }
enum class CcDisposition { PASS, CONSUME }
enum class EffectCcScope { INSTRUMENT, GLOBAL }
enum class EffectControllerType { CC, PITCH_BEND }

data class EffectCcMapping(
    val source: String?, val inputChannel: Int?, val controller: Int,
    val targetInstrumentChannel: Int = 0, val effectId: String,
    val parameterId: String, val minRaw: Float, val maxRaw: Float,
    val invert: Boolean = false, val pickupMode: PickupMode = PickupMode.SOFT_TAKEOVER,
    val disposition: CcDisposition = CcDisposition.PASS,
    val scope: EffectCcScope = EffectCcScope.INSTRUMENT,
    val targetInstrumentSetId: String = MAIN_INSTRUMENT_SET_ID
    ,val controllerType: EffectControllerType = EffectControllerType.CC
) {
    init {
        require(inputChannel == null || inputChannel in 0..15)
        require((controllerType == EffectControllerType.PITCH_BEND && controller == 0 || controllerType == EffectControllerType.CC && controller in 0..127) && targetInstrumentChannel in 0..15 && targetInstrumentSetId.isNotBlank())
        require(effectId in EFFECT_IDS)
            require(if (scope == EffectCcScope.GLOBAL) effectId in GLOBAL_EFFECT_IDS else effectId in INSTRUMENT_EFFECT_IDS || effectId == "native.delay")
        require(!(scope == EffectCcScope.INSTRUMENT && effectId == "native.delay") || parameterId.toInt() in 0..5)
        require(parameterId.toIntOrNull() != null)
        require(parameterId.toInt() >= -1)
        require(minRaw.isFinite() && maxRaw.isFinite() && maxRaw > minRaw)
    }
    fun matches(source: String, channel: Int, cc: Int) =
        controllerType == EffectControllerType.CC && (this.source == null || this.source == source) &&
            (inputChannel == null || inputChannel == channel) && controller == cc
    fun matchesPitchBend(source: String, channel: Int) = controllerType == EffectControllerType.PITCH_BEND &&
        (this.source == null || this.source == source) && (inputChannel == null || inputChannel == channel)

    companion object {
        val INSTRUMENT_EFFECT_IDS = setOf("lsp.parametric_eq", "lsp.compressor", "lsp.limiter", "lsp.chorus", "lsp.clipper", "lsp.filter", "lsp.phaser", "lsp.flanger", "piano.tremolo")
        val GLOBAL_EFFECT_IDS = setOf("native.delay", "fluidsynth.reverb")
        val EFFECT_IDS = INSTRUMENT_EFFECT_IDS + GLOBAL_EFFECT_IDS
    }
}

/** Resolve target channel from persisted mapping, never from MIDI source channel. */
fun effectCcTargetChannel(mapping: EffectCcMapping): Int = mapping.targetInstrumentChannel
fun resolveCcTargetLocalChannel(setId: String, localChannel: Int, validSetIds: List<String>): Int =
    if (setId == MAIN_INSTRUMENT_SET_ID) localChannel.takeIf { it in 0..15 } ?: -1
    else if (setId in validSetIds && localChannel in 0..15) localChannel else -1
enum class EffectCcAction { INSTRUMENT_PARAMETER, INSTRUMENT_ENABLED, SHARED_PARAMETER, DELAY_SEND }
fun effectCcAction(mapping: EffectCcMapping): EffectCcAction = effectCcAction(mapping.scope, mapping.effectId, mapping.parameterId)
fun effectCcAction(scope: EffectCcScope, effectId: String, parameterId: String): EffectCcAction = when {
    scope == EffectCcScope.INSTRUMENT && parameterId == "-1" -> EffectCcAction.INSTRUMENT_ENABLED
    scope == EffectCcScope.GLOBAL && effectId == "native.delay" && parameterId == "6" -> EffectCcAction.DELAY_SEND
    scope == EffectCcScope.GLOBAL -> EffectCcAction.SHARED_PARAMETER
    else -> EffectCcAction.INSTRUMENT_PARAMETER
}

/** Instrument enable pseudo-target; -1 remains separate from plugin parameter IDs (including old ID 0). */
fun instrumentEffectEnabledDescriptor(effectId: String, enabled: Boolean = false): EffectParameter = EffectParameter(
    stableId = "-1", displayName = "Enabled", value = com.piano.sequencer.EffectParameterValue(if (enabled) 1f else 0f),
    minimum = 0f, maximum = 1f, defaultValue = 0f, type = EffectParameterType.BOOLEAN, logarithmic = false
)

data class CcConflict(val incoming: EffectCcMapping, val existing: EffectCcMapping)

/** Pure runtime coordinator; caller serializes ingress and parameter dispatch. */
class EffectMidiCoordinator {
    private val mappings = mutableListOf<EffectCcMapping>()
    private val pickup = mutableMapOf<EffectCcMapping, PickupState>()
    private data class PickupState(var armed: Boolean = false, var previous: Float? = null, var current: Float? = null)

    @Synchronized fun mappings() = mappings.toList()
    @Synchronized fun restore(items: List<EffectCcMapping>) {
        items.forEach { require(mappings.none { old -> collides(old, it) }) }
        mappings.clear(); pickup.clear(); mappings.addAll(items)
    }
    @Synchronized fun add(mapping: EffectCcMapping): CcConflict? {
        val conflict = mappings.firstOrNull { collides(it, mapping) }
        if (conflict != null) return CcConflict(mapping, conflict)
        mappings.add(mapping); return null
    }
    @Synchronized fun remove(mapping: EffectCcMapping) { mappings.remove(mapping); pickup.remove(mapping) }
    @Synchronized fun replace(mapping: EffectCcMapping, old: EffectCcMapping?): CcConflict? {
        val conflict = mappings.firstOrNull { it != old && collides(it, mapping) }
        if (conflict != null) return CcConflict(mapping, conflict)
        if (old != null && old !in mappings) return CcConflict(mapping, old)
        if (old != null) { mappings.remove(old); pickup.remove(old) }
        mappings.add(mapping)
        return null
    }
    @Synchronized fun onCc(source: String, channel: Int, cc: Int, value: Int,
        parameter: (String, String) -> EffectParameter?, current: (String, String) -> Float,
        dispatch: (String, String, Float) -> Unit): Boolean {
        require(channel in 0..15 && cc in 0..127 && value in 0..127)
        val chosen = mappings.filter { it.matches(source, channel, cc) }
            .maxWithOrNull(compareBy<EffectCcMapping> { if (it.source != null) 1 else 0 }.thenBy { if (it.inputChannel != null) 1 else 0 })
            ?: return false
        val p = parameter(chosen.effectId, chosen.parameterId) ?: return chosen.disposition == CcDisposition.CONSUME
        val lo = chosen.minRaw.coerceAtLeast(p.minimum); val hi = chosen.maxRaw.coerceAtMost(p.maximum)
        if (!p.minimum.isFinite() || !p.maximum.isFinite() || p.maximum <= p.minimum || !lo.isFinite() || !hi.isFinite() || hi <= lo)
            return chosen.disposition == CcDisposition.CONSUME
        val state = pickup.getOrPut(chosen) { PickupState() }
        val targetCurrent = current(chosen.effectId, chosen.parameterId)
        if (state.current != targetCurrent) { state.armed = false; state.previous = null; state.current = targetCurrent }
        val target = scale(chosen, p, value)
        val cur = normalize(chosen, p, targetCurrent, lo, hi)
        val now = normalize(chosen, p, target, lo, hi)
        val prev = state.previous
        if (!state.armed && (abs(now-cur) <= PICKUP_TOLERANCE || (prev != null && (prev-cur)*(now-cur) <= 0f))) state.armed = true
        state.previous = now
        if (!state.armed) return chosen.disposition == CcDisposition.CONSUME
        dispatch(chosen.effectId, chosen.parameterId, target)
        state.current = target
        return chosen.disposition == CcDisposition.CONSUME
    }

    @Synchronized fun onPitchBend(source: String, channel: Int, value: Int,
        parameter: (String, String) -> EffectParameter?, current: (String, String) -> Float,
        dispatch: (String, String, Float) -> Unit): Boolean {
        require(channel in 0..15 && value in 0..16383)
        val chosen = mappings.filter { it.matchesPitchBend(source, channel) }.maxWithOrNull(
            compareBy<EffectCcMapping> { if (it.source != null) 1 else 0 }.thenBy { if (it.inputChannel != null) 1 else 0 }
        ) ?: return false
        val p = parameter(chosen.effectId, chosen.parameterId) ?: return chosen.disposition == CcDisposition.CONSUME
        val lo = chosen.minRaw.coerceAtLeast(p.minimum); val hi = chosen.maxRaw.coerceAtMost(p.maximum)
        if (!lo.isFinite() || !hi.isFinite() || hi <= lo || p.maximum <= p.minimum) return chosen.disposition == CcDisposition.CONSUME
        val state = pickup.getOrPut(chosen) { PickupState() }; val curValue = current(chosen.effectId, chosen.parameterId)
        if (state.current != curValue) { state.armed = false; state.previous = null; state.current = curValue }
        val mappedValue = scale(chosen,p,value,16383)
        val now = normalize(chosen,p,mappedValue,lo,hi)
        val cur = normalize(chosen,p,curValue,lo,hi)
        val prev = state.previous
        if (!state.armed && (kotlin.math.abs(now-cur) <= PICKUP_TOLERANCE || (prev != null && (prev-cur)*(now-cur) <= 0f))) state.armed = true
        state.previous = now
        if (state.armed) {
            val target=scale(chosen,p,value,16383)
            dispatch(chosen.effectId, chosen.parameterId, target)
            state.current=target
        }
        return chosen.disposition == CcDisposition.CONSUME
    }

    companion object {
        private const val PICKUP_TOLERANCE = 1f / 127f
        fun scale(m: EffectCcMapping, p: EffectParameter, raw: Int): Float {
            require(raw in 0..127)
            require(m.minRaw.isFinite() && m.maxRaw.isFinite() && m.maxRaw > m.minRaw)
            require(p.minimum.isFinite() && p.maximum.isFinite() && p.maximum > p.minimum)
            val lo = m.minRaw.coerceAtLeast(p.minimum); val hi = m.maxRaw.coerceAtMost(p.maximum)
            require(hi > lo)
            val t = (raw / 127f).let { if (m.invert) 1f-it else it }
            val v = if (p.logarithmic && lo > 0f) lo * exp(ln(hi/lo)*t) else lo+(hi-lo)*t
            return when {
                p.type == EffectParameterType.BOOLEAN -> if (t >= .5f) hi else lo
                p.enumValues.isNotEmpty() -> p.enumValues.minByOrNull { abs(it.first-v) }?.first ?: v
                p.type == EffectParameterType.INTEGER -> v.roundToInt().toFloat()
                else -> v
            }.coerceIn(lo, hi)
        }
        fun scale(m: EffectCcMapping, p: EffectParameter, raw: Int, maximum: Int): Float {
            require(raw in 0..maximum)
            val lo=m.minRaw.coerceAtLeast(p.minimum); val hi=m.maxRaw.coerceAtMost(p.maximum)
            val t=(raw.toFloat()/maximum).let { if(m.invert) 1f-it else it }
            val v=if(p.logarithmic && lo>0f) lo*exp(ln(hi/lo)*t) else lo+(hi-lo)*t
            return when { p.type==EffectParameterType.BOOLEAN -> if(t>=.5f) hi else lo; p.enumValues.isNotEmpty() -> p.enumValues.minByOrNull { abs(it.first-v) }?.first ?: v; p.type==EffectParameterType.INTEGER -> v.roundToInt().toFloat(); else -> v }.coerceIn(lo,hi)
        }
        private fun normalizeCc(m: EffectCcMapping, value:Int) = value/127f
        private fun normalize(m:EffectCcMapping,p:EffectParameter,v:Float,lo:Float,hi:Float):Float {
            val clamped=v.coerceIn(lo,hi)
            return if(p.logarithmic && lo>0f) (ln(clamped/lo)/ln(hi/lo)).coerceIn(0f,1f) else ((clamped-lo)/(hi-lo)).coerceIn(0f,1f)
        }
        private fun collides(a:EffectCcMapping,b:EffectCcMapping) = a.controllerType==b.controllerType && (a.source==null || b.source==null || a.source==b.source) && a.controller==b.controller && (a.inputChannel==null || b.inputChannel==null || a.inputChannel==b.inputChannel)
    }
}
