package com.piano.sequencer

import android.content.SharedPreferences
import com.piano.sequencer.midi.MAIN_INSTRUMENT_SET_ID
import com.piano.sequencer.midi.resolveInstrumentIdentityChannel
import com.piano.sequencer.midi.FileInstrumentSetStore
import android.content.Context
import com.piano.sequencer.service.PlaybackService

/**
 * Persistent per-channel vibrato state keyed by stable instrument-set identity and local channel 0..15.
 * Mirrors [InstrumentReleasePreferences]: one tuple (enabled + last values) per channel, so disabling
 * keeps the last rate/depth/delay for re-enabling. Bounds match the native validation in
 * FluidSynthEngine::setChannelVibrato (rate 0.5..12 Hz, depth 0..100 cents, delay 0..2000 ms).
 */
object InstrumentVibratoPreferences {
    const val FIELD_ENABLED = "enabled"
    const val FIELD_RATE = "rate"
    const val FIELD_DEPTH = "depth"
    const val FIELD_DELAY = "delay"

    const val DEFAULT_ENABLED = false
    const val DEFAULT_RATE_HZ = 5f
    const val DEFAULT_DEPTH_CENTS = 25f
    const val DEFAULT_DELAY_MS = 0f
    const val MIN_RATE_HZ = 0.5f
    const val MAX_RATE_HZ = 12f
    const val MIN_DEPTH_CENTS = 0f
    const val MAX_DEPTH_CENTS = 100f
    const val MIN_DELAY_MS = 0f
    const val MAX_DELAY_MS = 2000f

    /** Full per-channel vibrato tuple; the whole tuple is saved, disabled state included. */
    data class Vibrato(val enabled: Boolean, val rateHz: Float, val depthCents: Float, val delayMs: Float) {
        val isValid: Boolean
            get() = rateHz.isFinite() && rateHz in MIN_RATE_HZ..MAX_RATE_HZ &&
                depthCents.isFinite() && depthCents in MIN_DEPTH_CENTS..MAX_DEPTH_CENTS &&
                delayMs.isFinite() && delayMs in MIN_DELAY_MS..MAX_DELAY_MS
    }

    fun baseKey(setId: String, localChannel: Int) = "instrument_vibrato_${setId}_$localChannel"
    fun key(setId: String, localChannel: Int, field: String): String =
        when (field) {
            FIELD_ENABLED, FIELD_RATE, FIELD_DEPTH, FIELD_DELAY -> "${baseKey(setId, localChannel)}_$field"
            else -> error("Unknown vibrato field $field")
        }

    /** True when at least one tuple key exists for the channel (save() writes all four together). */
    fun has(prefs: SharedPreferences, setId: String, localChannel: Int): Boolean =
        localChannel in 0..15 && prefs.contains(key(setId, localChannel, FIELD_RATE))

    fun load(prefs: SharedPreferences, setId: String, localChannel: Int): Vibrato {
        require(localChannel in 0..15)
        val rate = prefs.getFloat(key(setId, localChannel, FIELD_RATE), DEFAULT_RATE_HZ)
            .takeIf { it.isFinite() && it in MIN_RATE_HZ..MAX_RATE_HZ } ?: DEFAULT_RATE_HZ
        val depth = prefs.getFloat(key(setId, localChannel, FIELD_DEPTH), DEFAULT_DEPTH_CENTS)
            .takeIf { it.isFinite() && it in MIN_DEPTH_CENTS..MAX_DEPTH_CENTS } ?: DEFAULT_DEPTH_CENTS
        val delay = prefs.getFloat(key(setId, localChannel, FIELD_DELAY), DEFAULT_DELAY_MS)
            .takeIf { it.isFinite() && it in MIN_DELAY_MS..MAX_DELAY_MS } ?: DEFAULT_DELAY_MS
        return Vibrato(prefs.getBoolean(key(setId, localChannel, FIELD_ENABLED), DEFAULT_ENABLED), rate, depth, delay)
    }

    fun save(prefs: SharedPreferences, setId: String, localChannel: Int, vibrato: Vibrato): Boolean {
        if (localChannel !in 0..15 || !vibrato.isValid) return false
        return prefs.edit()
            .putBoolean(key(setId, localChannel, FIELD_ENABLED), vibrato.enabled)
            .putFloat(key(setId, localChannel, FIELD_RATE), vibrato.rateHz)
            .putFloat(key(setId, localChannel, FIELD_DEPTH), vibrato.depthCents)
            .putFloat(key(setId, localChannel, FIELD_DELAY), vibrato.delayMs)
            .commit()
    }

    fun reset(prefs: SharedPreferences, setId: String, localChannel: Int): Boolean {
        if (localChannel !in 0..15) return false
        val edit = prefs.edit()
        listOf(FIELD_ENABLED, FIELD_RATE, FIELD_DEPTH, FIELD_DELAY).forEach { edit.remove(key(setId, localChannel, it)) }
        return edit.commit()
    }

    /** Clear persisted identity on explicit set removal only after native range reset succeeds. */
    fun resetSet(prefs: SharedPreferences, setId: String): Boolean {
        if (setId.isBlank()) return false
        val edit = prefs.edit()
        for (local in 0..15) listOf(FIELD_ENABLED, FIELD_RATE, FIELD_DEPTH, FIELD_DELAY).forEach {
            edit.remove(key(setId, local, it))
        }
        return edit.commit()
    }

    /** Main channel maps directly; external sets resolve absolute slot through established identity mapper. */
    fun restore(
        prefs: SharedPreferences,
        setId: String,
        localChannel: Int,
        absoluteChannel: Int,
        setter: (Int, Vibrato) -> Boolean
    ): Boolean {
        if (localChannel !in 0..15 || absoluteChannel !in 0..127) return false
        return setter(absoluteChannel, load(prefs, setId, localChannel))
    }

    /** Worker-thread startup restore. Resolves each stable set identity independently. */
    fun restoreStartup(context: Context, service: PlaybackService): Boolean {
        val prefs = context.getSharedPreferences("piano_prefs", Context.MODE_PRIVATE)
        val sets = FileInstrumentSetStore.get(context).all().filter { it.channelBase in 16..112 }
        val identities = listOf(MAIN_INSTRUMENT_SET_ID to null) + sets.map { it.id to it.channelBase }
        var okay = true
        for ((id, base) in identities) for (local in 0..15) {
            val absolute = resolveInstrumentIdentityChannel(id, local, base)
            if (!restore(prefs, id, local, absolute) { channel, v ->
                service.setChannelVibrato(channel, v.enabled, v.rateHz, v.depthCents, v.delayMs)
            }) okay = false
        }
        return okay
    }
}
