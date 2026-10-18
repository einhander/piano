package com.piano.sequencer

import android.content.Context
import com.piano.sequencer.service.PlaybackService

/** Shared preference restoration for a newly loaded master effect chain. */
internal object EffectStateRestore {
    const val INSTRUMENT_VERSION = 1
    private val instrumentIds = listOf("lsp.parametric_eq", "lsp.compressor", "lsp.limiter")

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
}
