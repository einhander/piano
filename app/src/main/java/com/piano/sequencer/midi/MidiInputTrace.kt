package com.piano.sequencer.midi
import android.os.SystemClock
import com.piano.sequencer.AppLogger

internal object MidiInputTrace {
    private const val UNCHANGED_INTERVAL_MS = 1_000L
    private const val CHANGED_INTERVAL_MS = 100L

    private data class Entry(val value: String, val timeMs: Long)
    private val lock = Any()
    private val entries = HashMap<String, Entry>()

    /** Logs first value immediately; coalesces repeats and bounds changed-value logs. */
    fun routine(key: String, value: String, message: () -> String) {
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            val previous = entries[key]
            if (previous != null &&
                ((previous.value == value && now - previous.timeMs < UNCHANGED_INTERVAL_MS) ||
                    now - previous.timeMs < CHANGED_INTERVAL_MS)
            ) return
            entries[key] = Entry(value, now)
        }
        AppLogger.info("MIDI", message())
    }
}
