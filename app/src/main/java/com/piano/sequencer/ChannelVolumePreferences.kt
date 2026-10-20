package com.piano.sequencer

import android.content.Context
import android.content.SharedPreferences

/** Persistence for UI-configured MIDI CC7 values (live external MIDI is not persisted). */
object ChannelVolumePreferences {
    const val DEFAULT = 100
    const val MIN = 0
    const val MAX = 127
    private const val PREFIX = "chan_volume_"

    fun key(channel: Int): String {
        require(channel in 0..15)
        return "$PREFIX$channel"
    }

    fun read(prefs: SharedPreferences, channel: Int): Int =
        prefs.getInt(key(channel), DEFAULT).coerceIn(MIN, MAX)

    fun snapshot(prefs: SharedPreferences): IntArray = IntArray(16) { read(prefs, it) }

    fun save(prefs: SharedPreferences, channel: Int, value: Int): Int {
        val clamped = value.coerceIn(MIN, MAX)
        prefs.edit().putInt(key(channel), clamped).apply()
        return clamped
    }

    fun read(context: Context, channel: Int): Int =
        read(context.getSharedPreferences("piano_prefs", Context.MODE_PRIVATE), channel)
}
