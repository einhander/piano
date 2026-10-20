package com.piano.sequencer

import android.util.Log

/**
 * App-level error logger. Collects errors/warnings for display in the app.
 * Thread-safe. Bounded to avoid memory issues.
 */
object AppLogger {

    private const val MAX_ENTRIES = 200
    private val lock = Any()
    private val entries = ArrayList<String>(MAX_ENTRIES)

    fun log(level: String, tag: String, message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
        synchronized(lock) {
            entries.add("[$timestamp] [$level] $tag: $message")
            while (entries.size > MAX_ENTRIES) {
                // Preserve bounded audio diagnostics against subsequent MIDI
                // chatter too; evict MIDI records first regardless of writer.
                val midiNoise = entries.indexOfFirst { it.contains(" MIDI:") || it.contains(" MidiInputReceiver:") }
                entries.removeAt(if (midiNoise >= 0) midiNoise else 0)
            }
        }
    }

    fun error(tag: String, message: String) = log("ERROR", tag, message)
    fun warn(tag: String, message: String) = log("WARN", tag, message)
    fun info(tag: String, message: String) = log("INFO", tag, message)

    fun getAll(): List<String> = synchronized(lock) { entries.toList() }
    fun clear() { synchronized(lock) { entries.clear() } }
}
