package com.piano.sequencer.midi

import java.util.concurrent.ConcurrentHashMap

/**
 * Collects the notes of a single chord from live MIDI during a chord-cell
 * recording window. Lives at the process level (survives activity recreation,
 * like [MidiRecordSession]) so the chord is not lost on rotation.
 *
 * Behaviour (confirmed with user):
 * - The user may press notes sequentially (strum) or all at once — only the
 *   final *set* of notes + per-note last velocity is kept; record timing is
 *   discarded.
 * - On note-off the key is NOT removed: a chord is the union of every key the
 *   user played during the window. (The gate semantics are handled at playback
 *   by the trigger, not by which keys were held at record-stop.)
 * - The last-seen velocity per (channel, note) wins, so repeated presses keep
 *   the most recent dynamics.
 *
 * Thread-safe: called from the MIDI binder thread (onNoteOn) and read on the
 * stop worker thread. ConcurrentHashMap for the note map; no further locking.
 */
object ChordRecorder {

    /** Key = note (0–127); value = ChordNote with last velocity + channel. */
    private val notes = ConcurrentHashMap<Int, ChordNote>()

    /** True while a chord recording is active. Set/cleared on the stop worker. */
    @Volatile
    private var active = false

    fun isActive(): Boolean = active

    /** Begin a chord recording window — clears any previous notes. */
    fun start() {
        notes.clear()
        active = true
    }

    /** Record a live note-on into the chord (updates velocity if repeated). */
    fun onNoteOn(channel: Int, note: Int, velocity: Int) {
        if (!active || note !in 0..127) return
        notes[note] = ChordNote(note, velocity.coerceIn(0, 127), channel.coerceIn(0, 15))
    }

    /**
     * Finish the chord recording window and return the chord as a stable list,
     * sorted by note ascending. Returns an empty list if no notes were played.
     * Deactivates the collector (live notes after this are ignored).
     */
    fun stop(): List<ChordNote> {
        active = false
        val result = notes.values.sortedBy { it.note }
        notes.clear()
        return result
    }

    /** Cancel without producing a chord. */
    fun cancel() {
        active = false
        notes.clear()
    }
}
