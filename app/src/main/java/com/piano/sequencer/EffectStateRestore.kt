package com.piano.sequencer

import android.content.Context
import com.piano.sequencer.service.PlaybackService

/** Shared preference restoration for a newly loaded master effect chain. */
internal object EffectStateRestore {
    const val INSTRUMENT_VERSION = 1
    private val instrumentIds = listOf("lsp.parametric_eq", "lsp.compressor", "lsp.limiter", "lsp.chorus", "lsp.clipper")
    private val sharedParamIds = mapOf("native.delay" to 0..5, "fluidsynth.reverb" to 0..4)

    internal fun instrumentRackKey(channel: Int) = "instrument_fx_v${INSTRUMENT_VERSION}_rack_$channel"
    internal fun instrumentEnabledKey(channel: Int, id: String) = "instrument_fx_v${INSTRUMENT_VERSION}_enabled_${channel}_${id.replace('.', '_')}"
    internal fun instrumentParamKey(channel: Int, id: String, paramId: Int) = "instrument_fx_v${INSTRUMENT_VERSION}_param_${channel}_${id.replace('.', '_')}_$paramId"

    /** Restore off-thread after loading instrument bundle; parameter writes precede lazy instantiation. */
    fun restoreInstruments(context: Context, service: PlaybackService, bundleAvailable: Boolean) {
        val prefs = context.getSharedPreferences("piano_prefs", Context.MODE_PRIVATE)
        if (!bundleAvailable || prefs.getInt("instrument_fx_schema_version", 0) != INSTRUMENT_VERSION) return
        for (channel in 0 until 16) {
            val rackOn = prefs.getBoolean(instrumentRackKey(channel), false)
            service.setInstrumentRackEnabled(channel, rackOn)
            for (id in instrumentIds) {
                val count = runCatching { service.getInstrumentEffectParamCount(id) }.getOrDefault(0)
                for (index in 0 until count) {
                    val info = runCatching { service.getInstrumentEffectParamInfo(id, index) }.getOrNull() ?: continue
                    if (info.size < 4) continue
                    val pid = info[0].toInt()
                    val key = instrumentParamKey(channel, id, pid)
                    if (prefs.contains(key)) runCatching { service.setInstrumentEffectParameter(channel, id, pid, prefs.getFloat(key, info[3])) }
                }
                if (prefs.getBoolean(instrumentEnabledKey(channel, id), false)) {
                    runCatching { service.setInstrumentEffectEnabled(channel, id, true) }
                }
            }
        }
    }

    /** Persist a changed instrument setting using synchronous commit for crash-safe small preference writes. */
    fun persistInstrumentRack(context: Context, channel: Int, enabled: Boolean): Boolean {
        if (channel !in 0..15) return false
        return context.getSharedPreferences("piano_prefs", Context.MODE_PRIVATE).edit().putInt("instrument_fx_schema_version", INSTRUMENT_VERSION).putBoolean(instrumentRackKey(channel), enabled).commit()
    }
    fun persistInstrumentEffect(context: Context, channel: Int, id: String, enabled: Boolean): Boolean {
        if (channel !in 0..15 || id !in instrumentIds) return false
        return context.getSharedPreferences("piano_prefs", Context.MODE_PRIVATE).edit().putInt("instrument_fx_schema_version", INSTRUMENT_VERSION).putBoolean(instrumentEnabledKey(channel,id), enabled).commit()
    }
    fun persistInstrumentParameter(context: Context, channel: Int, id: String, paramId: Int, value: Float): Boolean {
        if (channel !in 0..15 || id !in instrumentIds || !value.isFinite() || paramId < 0) return false
        return context.getSharedPreferences("piano_prefs", Context.MODE_PRIVATE).edit().putInt("instrument_fx_schema_version", INSTRUMENT_VERSION).putFloat(instrumentParamKey(channel,id,paramId), value).commit()
    }

    fun restore(context: Context, service: PlaybackService, effectCount: Int) {
        val prefs = context.getSharedPreferences("piano_prefs", Context.MODE_PRIVATE)
        for (slot in 0 until effectCount) {
            val paramCount = runCatching { service.getMasterEffectParamCount(slot) }.getOrDefault(0)
            for (index in 0 until paramCount) {
                val info = runCatching { service.getMasterEffectParamInfo(slot, index) }.getOrNull() ?: continue
                if (info.size < 4) continue
                val paramId = info[0].toInt()
                val key = "fx_param_${slot}_$paramId"
                if (prefs.contains(key)) {
                    runCatching { service.setMasterEffectParameter(slot, paramId, prefs.getFloat(key, info[3])) }
                }
            }
            runCatching { service.setMasterEffectEnabled(slot, prefs.getBoolean("fx_enabled_$slot", false)) }
        }
    }

    private const val SHARED_FX_PREFIX = "shared_fx_v1_"
    private const val DELAY_MIGRATED = "instrument_delay_migration_v1_done"
    internal fun instrumentDelayKey(channel: Int, id: Int) = "instrument_delay_v1_${channel}_$id"
    internal fun sharedParameterKey(scope: String, id: Int) = "${SHARED_FX_PREFIX}${scope.replace('.', '_')}_param_$id"
    internal fun delaySendKey(channel: Int) = "${SHARED_FX_PREFIX}delay_send_$channel"

    /** Restore globals after synth initialization, independently of optional LSP availability. */
    fun restoreShared(context: Context, service: PlaybackService) {
        val prefs = context.getSharedPreferences("piano_prefs", Context.MODE_PRIVATE)
        for ((scope, ids) in sharedParamIds) {
            if (!service.isSharedFxAvailable(scope)) continue
            for (id in ids) {
                // Existing reverb enable key remains source of truth when present.
                if (scope == "fluidsynth.reverb" && id == 0) {
                    val value = when {
                        prefs.contains("reverb") -> if (prefs.getInt("reverb", 1) != 0) 1f else 0f
                        prefs.contains(sharedParameterKey(scope, id)) -> prefs.getFloat(sharedParameterKey(scope, id), 1f)
                        else -> null
                    }
                    if (value != null) service.setSharedFxParameter(scope, id, value)
                    continue
                }
                val key = sharedParameterKey(scope, id)
                if (prefs.contains(key)) service.setSharedFxParameter(scope, id, prefs.getFloat(key, 0f))
            }
        }
        if (service.isSharedFxAvailable("native.delay")) for (channel in 0 until 16) {
            val key = delaySendKey(channel)
            if (prefs.contains(key)) service.setDelaySend(channel, prefs.getFloat(key, 0f))
        }
    }

    /** Restore independent inserts, migrating legacy shared state without changing audible attenuation. */
    fun restoreInstrumentDelays(context: Context, service: PlaybackService) {
        val prefs = context.getSharedPreferences("piano_prefs", Context.MODE_PRIVATE)
        if (!service.isInstrumentDelayAvailable()) return
        if (!prefs.getBoolean(DELAY_MIGRATED, false)) {
            val params = (0..5).associateWith { id -> prefs.getFloat(sharedParameterKey("native.delay", id), floatArrayOf(0f,250f,.35f,.30f,0f,0f)[id]) }
            val enabled = params.getValue(0) >= .5f
            var success = true
            for (channel in 0 until 16) {
                val send = prefs.getFloat(delaySendKey(channel), 0f)
                if (send <= 0f) continue
                // Legacy output was delay wet × send. Fold send into wet to preserve level.
                val channelValues = params.toMutableMap().apply { put(0, if (enabled) 1f else 0f); put(3, params.getValue(3) * send) }
                for ((id, value) in channelValues) if (!service.setInstrumentDelayParameter(channel, id, value)) success = false
            }
            if (success) prefs.edit().putBoolean(DELAY_MIGRATED, true).commit()
        }
        for (channel in 0 until 16) for (id in 0..5) {
            val key = instrumentDelayKey(channel, id)
            if (prefs.contains(key)) service.setInstrumentDelayParameter(channel, id, prefs.getFloat(key, 0f))
            else if (id == 0) service.setInstrumentDelayParameter(channel, id, 0f)
        }
    }
    fun persistInstrumentDelay(context: Context, channel: Int, id: Int, value: Float): Boolean {
        if (channel !in 0..15 || id !in 0..5 || !value.isFinite()) return false
        return context.getSharedPreferences("piano_prefs", Context.MODE_PRIVATE).edit().putFloat(instrumentDelayKey(channel,id), value).commit()
    }

    fun persistSharedParameter(context: Context, scope: String, id: Int, value: Float): Boolean {
        if (scope !in sharedParamIds || id !in sharedParamIds.getValue(scope) || !value.isFinite()) return false
        if (scope == "fluidsynth.reverb" && id == 0) {
            return context.getSharedPreferences("piano_prefs", Context.MODE_PRIVATE).edit()
                .putInt("reverb", if (value >= 0.5f) 1 else 0)
                .remove(sharedParameterKey(scope, id)).commit()
        }
        val editor = context.getSharedPreferences("piano_prefs", Context.MODE_PRIVATE).edit().putFloat(sharedParameterKey(scope, id), value)
        return editor.commit()
    }
    fun persistDelaySend(context: Context, channel: Int, value: Float): Boolean {
        if (channel !in 0..15 || !value.isFinite() || value !in 0f..1f) return false
        return context.getSharedPreferences("piano_prefs", Context.MODE_PRIVATE).edit().putFloat(delaySendKey(channel), value).commit()
    }
}
