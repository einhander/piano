package com.piano.sequencer.midi

/**
 * Process-level state for the active per-cell recording session.
 *
 * D5: the engine recorder is a process-level singleton (survives activity
 * recreation), so the "which cell is being recorded" state must too.
 * Activity instances mirror this for the UI; the holder is authoritative.
 */
object MidiRecordSession {
    /** Shared worker-side gate: never acquire from MIDI/audio callbacks. */
    val gate = Any()
    /** Cell id being recorded; null when not recording. */
    @Volatile
    var cellId: Int? = null

    /** True while a start/stop worker task is in flight (engine state changing). */
    @Volatile
    var inFlight: Boolean = false

    /** Recording save/commit remains active after cellId is cleared. */
    @Volatile
    var pendingSave: Boolean = false

    /** Identity of the cell being recorded, so the save cannot land on a mutated cell. */
    @Volatile
    var target: RecordCellIdentity? = null
}

/** Immutable identity for preventing recording into a changed/deleted cell. */
data class RecordCellIdentity(val id: Int, val filePath: String, val assetType: String, val mode: String)

fun recordTargetUnchanged(original: RecordCellIdentity?, latest: SequencerCell?): Boolean =
    original != null && latest != null && original == RecordCellIdentity(latest.id, latest.filePath, latest.assetType, latest.mode)

fun replacementAllowed(inFlight: Boolean, cellId: Int?, pendingSave: Boolean): Boolean =
    !inFlight && cellId == null && !pendingSave
