package com.piano.sequencer

import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * App-level error logger. Collects errors/warnings for display in the app.
 * Thread-safe. Bounded to avoid memory issues.
 */
object AppLogger {

    private const val MAX_ENTRIES = 200
    private val entries = CopyOnWriteArrayList<String>()

    fun log(level: String, tag: String, message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
        entries.add("[$timestamp] [$level] $tag: $message")
        // Trim oldest entries
        while (entries.size > MAX_ENTRIES) {
            entries.removeAt(0)
        }
    }

    fun error(tag: String, message: String) = log("ERROR", tag, message)
    fun warn(tag: String, message: String) = log("WARN", tag, message)
    fun info(tag: String, message: String) = log("INFO", tag, message)

    fun getAll(): List<String> = entries.toList()
    fun clear() { entries.clear() }
}