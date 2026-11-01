package com.piano.sequencer

import android.content.SharedPreferences
import com.piano.sequencer.midi.MAIN_INSTRUMENT_SET_ID
import com.piano.sequencer.midi.resolveInstrumentIdentityChannel
import com.piano.sequencer.midi.FileInstrumentSetStore
import android.content.Context
import com.piano.sequencer.service.PlaybackService

/** Persistent release settings keyed by stable instrument-set identity and local channel 0..15. */
object InstrumentReleasePreferences {
    const val DEFAULT_TIMECENTS = 0f
    const val MIN_TIMECENTS = -2400f
    const val MAX_TIMECENTS = 2400f
    fun key(setId: String, localChannel: Int) = "instrument_release_${setId}_$localChannel"

    fun load(prefs: SharedPreferences, setId: String, localChannel: Int): Float {
        require(localChannel in 0..15)
        return prefs.getFloat(key(setId, localChannel), DEFAULT_TIMECENTS)
            .takeIf { it.isFinite() && it in MIN_TIMECENTS..MAX_TIMECENTS } ?: DEFAULT_TIMECENTS
    }

    fun save(prefs: SharedPreferences, setId: String, localChannel: Int, timecents: Float): Boolean {
        if (localChannel !in 0..15 || !timecents.isFinite() || timecents !in MIN_TIMECENTS..MAX_TIMECENTS) return false
        return prefs.edit().putFloat(key(setId, localChannel), timecents).commit()
    }

    fun reset(prefs: SharedPreferences, setId: String, localChannel: Int): Boolean =
        localChannel in 0..15 && prefs.edit().remove(key(setId, localChannel)).commit()

    /** Clear persisted identity on explicit set removal only after native range reset succeeds. */
    fun resetSet(prefs: SharedPreferences, setId: String): Boolean {
        if (setId.isBlank()) return false
        val edit = prefs.edit()
        for (local in 0..15) edit.remove(key(setId, local))
        return edit.commit()
    }

    /** Main channel maps directly; external sets resolve absolute slot through established identity mapper. */
    fun restore(prefs: SharedPreferences, setId: String, localChannel: Int, absoluteChannel: Int, setter: (Int, Float) -> Boolean): Boolean {
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
            if (!restore(prefs, id, local, absolute) { channel, value -> service.setChannelRelease(channel, value) }) okay = false
        }
        return okay
    }
}
