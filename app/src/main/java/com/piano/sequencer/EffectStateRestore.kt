package com.piano.sequencer

import android.content.Context
import com.piano.sequencer.service.PlaybackService
import com.piano.sequencer.midi.FileInstrumentSetStore
import com.piano.sequencer.midi.MAIN_INSTRUMENT_SET_ID
import com.piano.sequencer.midi.resolveInstrumentIdentityChannel
import com.piano.sequencer.midi.legacyInstrumentStateTargetSetId
import com.piano.sequencer.midi.restoredInstrumentEnabled
import com.piano.sequencer.midi.legacyInstrumentMigrationNeeded

/** Shared preference restoration for a newly loaded master effect chain. */
internal object EffectStateRestore {
    const val INSTRUMENT_VERSION = 1
    internal val instrumentIds = listOf("lsp.parametric_eq", "lsp.compressor", "lsp.limiter", "lsp.chorus", "lsp.clipper", "lsp.filter", "lsp.phaser", "lsp.flanger")
    private val sharedParamIds = mapOf("native.delay" to 0..5, "fluidsynth.reverb" to 0..4)

    /** Startup fan-out. Delay restore is independent of optional LSP rack availability. */
    internal fun restoreInstrumentStartup(
        bundleAvailable: Boolean,
        restoreRacks: () -> Unit,
        restoreDelays: () -> Unit
    ) {
        if (bundleAvailable) restoreRacks()
        restoreDelays()
    }

    internal fun instrumentRackKey(channel: Int) = "instrument_fx_v${INSTRUMENT_VERSION}_rack_$channel"
    internal fun instrumentEnabledKey(channel: Int, id: String) = "instrument_fx_v${INSTRUMENT_VERSION}_enabled_${channel}_${id.replace('.', '_')}"
    internal fun instrumentParamKey(channel: Int, id: String, paramId: Int) = "instrument_fx_v${INSTRUMENT_VERSION}_param_${channel}_${id.replace('.', '_')}_$paramId"
    internal fun instrumentIdentityKey(kind: String, setId: String, channel: Int, suffix: String = "") =
        "instrument_fx_v${INSTRUMENT_VERSION}_${kind}_${setId}_${channel}${if (suffix.isEmpty()) "" else "_$suffix"}"
    internal fun instrumentIdentityRackKey(setId: String, channel: Int) = instrumentIdentityKey("rack", setId, channel)
    internal fun instrumentIdentityEnabledKey(setId: String, channel: Int, id: String) = instrumentIdentityKey("enabled", setId, channel, id.replace('.', '_'))
    internal fun instrumentIdentityParamKey(setId: String, channel: Int, id: String, pid: Int) = instrumentIdentityKey("param", setId, channel, "${id.replace('.', '_')}_$pid")

    /** Restore off-thread after loading instrument bundle; parameter writes precede lazy instantiation. */
    fun restoreInstruments(context: Context, service: PlaybackService, bundleAvailable: Boolean) {
        val prefs = context.getSharedPreferences("piano_prefs", Context.MODE_PRIVATE)
        if (!bundleAvailable || prefs.getInt("instrument_fx_schema_version", 0) != INSTRUMENT_VERSION) return
        val sets = FileInstrumentSetStore.get(context).all()
        sets.filter { it.channelBase !in 16..112 }.forEach {
            AppLogger.warn("EffectStateRestore", "Unsupported persisted instrument set id=${it.id} base=${it.channelBase}; restore skipped")
        }
        val identities = listOf(MAIN_INSTRUMENT_SET_ID to null) + sets
            .filter { it.channelBase in 16..112 }.map { it.id to it.channelBase }
        if (!migrateLegacyInstrumentState(prefs)) return
        for ((setId, base) in identities) for (local in 0 until 16) {
            val channel = resolveInstrumentIdentityChannel(setId, local, base)
            if (channel !in 0..127) continue
            val rackOn = prefs.getBoolean(instrumentIdentityRackKey(setId, local), false)
            service.setInstrumentRackEnabled(channel, rackOn)
            for (id in instrumentIds) {
                val count = runCatching { service.getInstrumentEffectParamCount(id) }.getOrDefault(0)
                for (index in 0 until count) {
                    val info = runCatching { service.getInstrumentEffectParamInfo(id, index) }.getOrNull() ?: continue
                    if (info.size < 4) continue
                    val pid = info[0].toInt()
                    val key = instrumentIdentityParamKey(setId, local, id, pid)
                    if (prefs.contains(key)) runCatching { service.setInstrumentEffectParameter(channel, id, pid, prefs.getFloat(key, info[3])) }
                }
                runCatching { service.setInstrumentEffectEnabled(channel, id, restoredInstrumentEnabled(prefs.getBoolean(instrumentIdentityEnabledKey(setId, local, id), false))) }
            }
        }
    }

    internal fun migrateLegacyInstrumentState(prefs: android.content.SharedPreferences): Boolean {
        val marker = "instrument_fx_identity_migration_v1_done"
        if (!legacyInstrumentMigrationNeeded(prefs.getBoolean(marker, false))) return true
        val edit = prefs.edit()
        for (ch in 0..15) {
            if (prefs.contains(instrumentRackKey(ch))) edit.putBoolean(instrumentIdentityRackKey(legacyInstrumentStateTargetSetId(), ch), prefs.getBoolean(instrumentRackKey(ch), false))
            for (id in instrumentIds) {
                val enabled = instrumentEnabledKey(ch, id)
                if (prefs.contains(enabled)) edit.putBoolean(instrumentIdentityEnabledKey(legacyInstrumentStateTargetSetId(), ch, id), prefs.getBoolean(enabled, false))
                for (key in prefs.all.keys.filter { it.startsWith("instrument_fx_v${INSTRUMENT_VERSION}_param_${ch}_${id.replace('.', '_')}_") }) {
                    val pid = key.substringAfterLast('_').toIntOrNull() ?: continue
                    edit.putFloat(instrumentIdentityParamKey(legacyInstrumentStateTargetSetId(), ch, id, pid), prefs.getFloat(key, 0f))
                }
            }
        }
        return edit.putBoolean(marker, true).commit()
    }

    /** Persist a changed instrument setting using synchronous commit for crash-safe small preference writes. */
    fun persistInstrumentRack(context: Context, channel: Int, enabled: Boolean): Boolean {
        if (channel !in 0..15) return false
        return context.getSharedPreferences("piano_prefs", Context.MODE_PRIVATE).edit().putInt("instrument_fx_schema_version", INSTRUMENT_VERSION)
            .putBoolean(instrumentRackKey(channel), enabled).putBoolean(instrumentIdentityRackKey(MAIN_INSTRUMENT_SET_ID, channel), enabled).commit()
    }
    fun persistInstrumentEffect(context: Context, channel: Int, id: String, enabled: Boolean): Boolean {
        if (channel !in 0..15 || id !in instrumentIds) return false
        return context.getSharedPreferences("piano_prefs", Context.MODE_PRIVATE).edit().putInt("instrument_fx_schema_version", INSTRUMENT_VERSION)
            .putBoolean(instrumentEnabledKey(channel,id), enabled).putBoolean(instrumentIdentityEnabledKey(MAIN_INSTRUMENT_SET_ID, channel, id), enabled).commit()
    }
    fun persistInstrumentParameter(context: Context, channel: Int, id: String, paramId: Int, value: Float): Boolean {
        if (channel !in 0..15 || id !in instrumentIds || !value.isFinite() || paramId < 0) return false
        return context.getSharedPreferences("piano_prefs", Context.MODE_PRIVATE).edit().putInt("instrument_fx_schema_version", INSTRUMENT_VERSION)
            .putFloat(instrumentParamKey(channel,id,paramId), value).putFloat(instrumentIdentityParamKey(MAIN_INSTRUMENT_SET_ID, channel, id, paramId), value).commit()
    }
    fun persistInstrumentRack(context: Context, setId: String, channel: Int, enabled: Boolean): Boolean =
        persistIdentityValue(context, setId, channel, instrumentIdentityRackKey(setId, channel), enabled)
    fun persistInstrumentEffect(context: Context, setId: String, channel: Int, id: String, enabled: Boolean): Boolean {
        if (id !in instrumentIds) return false
        return persistIdentityValue(context, setId, channel, instrumentIdentityEnabledKey(setId, channel, id), enabled)
    }
    fun persistInstrumentParameter(context: Context, setId: String, channel: Int, id: String, pid: Int, value: Float): Boolean {
        if (id !in instrumentIds || pid < 0 || !value.isFinite()) return false
        return persistIdentityValue(context, setId, channel, instrumentIdentityParamKey(setId, channel, id, pid), value)
    }
    private fun persistIdentityValue(context: Context, setId: String, channel: Int, key: String, value: Any): Boolean {
        if (setId.isBlank() || channel !in 0..15 || (setId != MAIN_INSTRUMENT_SET_ID && FileInstrumentSetStore.get(context).all().none { it.id == setId && it.channelBase in 16..112 })) return false
        val edit = context.getSharedPreferences("piano_prefs", Context.MODE_PRIVATE).edit().putInt("instrument_fx_schema_version", INSTRUMENT_VERSION)
        when (value) { is Boolean -> edit.putBoolean(key, value); is Float -> edit.putFloat(key, value); else -> return false }
        return edit.commit()
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
    internal fun instrumentIdentityDelayKey(setId: String, channel: Int, id: Int) = "instrument_delay_v2_${setId}_${channel}_$id"
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
        migrateLegacyDelay(prefs, service)
        val identities = listOf(MAIN_INSTRUMENT_SET_ID to null) + FileInstrumentSetStore.get(context).all()
            .filter { it.channelBase in 16..112 }.map { it.id to it.channelBase }
        for ((setId, base) in identities) for (local in 0 until 16) {
            val channel = resolveInstrumentIdentityChannel(setId, local, base)
            if (channel !in 0..127) continue
            for (id in 0..5) {
                val key = instrumentIdentityDelayKey(setId, local, id)
                if (prefs.contains(key)) service.setInstrumentDelayParameter(channel, id, prefs.getFloat(key, 0f))
                else if (id == 0) service.setInstrumentDelayParameter(channel, id, 0f)
            }
        }
    }
    private fun migrateLegacyDelay(prefs: android.content.SharedPreferences, service: PlaybackService) {
        val marker = "instrument_delay_identity_migration_v2_done"
        if (!legacyInstrumentMigrationNeeded(prefs.getBoolean(marker, false))) return
        var ok = true
        for (ch in 0..15) for (id in 0..5) {
            val old = instrumentDelayKey(ch, id)
            if (prefs.contains(old) && !service.setInstrumentDelayParameter(ch, id, prefs.getFloat(old, 0f))) ok = false
            if (prefs.contains(old)) ok = prefs.edit().putFloat(instrumentIdentityDelayKey(legacyInstrumentStateTargetSetId(), ch, id), prefs.getFloat(old, 0f)).commit() && ok
        }
        if (ok) prefs.edit().putBoolean(marker, true).commit()
    }
    fun persistInstrumentDelay(context: Context, channel: Int, id: Int, value: Float): Boolean {
        if (channel !in 0..15 || id !in 0..5 || !value.isFinite()) return false
        return context.getSharedPreferences("piano_prefs", Context.MODE_PRIVATE).edit().putFloat(instrumentDelayKey(channel,id), value)
            .putFloat(instrumentIdentityDelayKey(MAIN_INSTRUMENT_SET_ID, channel, id), value).commit()
    }
    fun persistInstrumentDelay(context: Context, setId: String, channel: Int, id: Int, value: Float): Boolean {
        if (channel !in 0..15 || id !in 0..5 || !value.isFinite() || setId.isBlank()) return false
        return context.getSharedPreferences("piano_prefs", Context.MODE_PRIVATE).edit()
            .putFloat(instrumentIdentityDelayKey(setId, channel, id), value).commit()
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
