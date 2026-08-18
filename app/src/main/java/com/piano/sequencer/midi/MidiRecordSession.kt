package com.piano.sequencer.midi

/**
 * Process-level state for the active per-cell recording session.
 *
 * D5: the engine recorder is a process-level singleton (survives activity
 * recreation), so the "which cell is being recorded" state must too.
 * Activity instances mirror this for the UI; the holder is authoritative.
 */
object MidiRecordSession {
    /** Cell id being recorded; null when not recording. */
    @Volatile
    var cellId: Int? = null

    /** True while a start/stop worker task is in flight (engine state changing). */
    @Volatile
    var inFlight: Boolean = false
}