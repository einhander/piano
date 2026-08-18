package com.piano.sequencer.midi

import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * A sequencer cell: one slot that can hold a MIDI file with learned key, loop, tempo,
 * and optional channel remap.
 *
 * D1: mapping key = note number only (channel-agnostic).
 * D3: single tempo per file, user-overridable, default = file's initial tempo.
 */
@Serializable
data class SequencerCell(
    val id: Int,
    val note: Int = -1,        // learned MIDI key; -1 = none
    val filePath: String = "", // "" = no file
    val loop: Boolean = false,
    val tempo: Double = 120.0, // BPM, 20–300
    val channel: Int = -1      // -1 = from file, 0-15 = remap all events
)

/**
 * Persisted mapping store — JSON in SharedPreferences under key "midi_file_map".
 *
 * New format: array of SequencerCell objects.
 * Legacy format: map keyed by note string → migrated to cells on load.
 * Thread-safe via synchronized.
 *
 * M2 fix: SINGLE instance shared between MainActivity and MidiFilesPanel.
 */
class MidiFileMappingStore(private val prefs: SharedPreferences) {

    companion object {
        private const val KEY = "midi_file_map"
        private val JSON = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
        @Volatile
        private var instance: MidiFileMappingStore? = null

        /** Process-level singleton — avoids the M2 two-instance divergence bug. */
        fun get(context: android.content.Context): MidiFileMappingStore {
            instance ?: run {
                synchronized(MidiFileMappingStore::class) {
                    instance ?: run {
                        instance = MidiFileMappingStore(
                            context.getSharedPreferences("piano_prefs", android.content.Context.MODE_PRIVATE)
                        )
                    }
                }
            }
            return instance!!
        }
    }

    /** Called outside the lock after a cell is saved. Set once by MidiFileTriggerController.bind. */
    var onCellSaved: ((SequencerCell) -> Unit)? = null

    private val lock = Any()
    private var legacyLoad = false

    /** All cells, in insertion order. */
    private var _cells = mutableListOf<SequencerCell>()

    init {
        load()
    }

    /** Legacy map entry format (for migration). */
    @Serializable
    private data class LegacyAssignment(
        val note: Int,
        val filePath: String,
        val loop: Boolean,
        val tempo: Double,
        val channel: Int = -1
    )

    /** Parse JSON from prefs into _cells. */
    private fun load() {
        synchronized(lock) {
            val json = prefs.getString(KEY, null)
            if (json.isNullOrEmpty()) {
                _cells = mutableListOf()
                legacyLoad = true
                return
            }
            try {
                val trimmed = json.trim()
                if (trimmed.startsWith('[')) {
                    // New format: array of SequencerCell
                    val list: List<SequencerCell> = JSON.decodeFromString(json)
                    _cells = list.toMutableList()
                    legacyLoad = false
                } else if (trimmed.startsWith('{')) {
                    // Legacy format: map keyed by note string
                    val legacyMap: Map<String, LegacyAssignment> = JSON.decodeFromString(json)
                    _cells = legacyMap.entries
                        .map { it.value }
                        .sortedBy { it.note }
                        .mapIndexed { index, legacy ->
                            SequencerCell(
                                id = index + 1,
                                note = legacy.note,
                                filePath = legacy.filePath,
                                loop = legacy.loop,
                                tempo = legacy.tempo,
                                channel = legacy.channel
                            )
                        }
                        .toMutableList()
                    legacyLoad = true
                } else {
                    _cells = mutableListOf()
                    legacyLoad = true
                }
            } catch (_: Exception) {
                _cells = mutableListOf()
                legacyLoad = true
            }
        }
    }

    /** Write _cells to prefs as JSON array. */
    private fun save() {
        synchronized(lock) {
            val json = JSON.encodeToString(_cells)
            prefs.edit().putString(KEY, json).apply()
        }
    }

    /** Return all cells (unmodifiable snapshot, insertion order). */
    fun all(): List<SequencerCell> = synchronized(lock) {
        _cells.toList()
    }

    /** Get cell by id, or null. */
    fun get(id: Int): SequencerCell? = synchronized(lock) {
        _cells.find { it.id == id }
    }

    /**
     * Set (add or replace) a cell by id.
     */
    fun set(cell: SequencerCell) {
        synchronized(lock) {
            val idx = _cells.indexOfFirst { it.id == cell.id }
            if (idx >= 0) {
                _cells[idx] = cell
            } else {
                _cells.add(cell)
            }
            save()
        }
        if (cell.filePath.isNotEmpty()) onCellSaved?.invoke(cell)
    }

    /** Remove cell by id. */
    fun remove(id: Int) {
        synchronized(lock) {
            _cells.removeAll { it.id == id }
            save()
        }
    }

    /** Find first cell with the given note; never matches note < 0. */
    fun findByNote(note: Int): SequencerCell? = synchronized(lock) {
        if (note < 0) return@synchronized null
        _cells.find { it.note == note }
    }

    /** Next available id: max id + 1, or 1 when empty. */
    fun nextId(): Int = synchronized(lock) {
        if (_cells.isEmpty()) 1 else _cells.maxOfOrNull { it.id }!! + 1
    }

    /** Clear all cells. */
    fun clear() {
        synchronized(lock) {
            _cells.clear()
            save()
        }
    }

    /** True if the loaded JSON was legacy map format or absent (needs backfill). */
    fun needsLegacyBackfill(): Boolean = synchronized(lock) {
        legacyLoad
    }

    /** Clear the legacy-backfill flag (called after the one-time backfill runs). */
    fun markBackfillDone() {
        synchronized(lock) {
            legacyLoad = false
        }
    }
}

/**
 * Learn state for MIDI file key assignment.
 *
 * M1 fix: real state machine — @Volatile state, startLearning sets LEARNING,
 * captureNote checks LEARNING state, cancel() resets to IDLE.
 * m7: cancel on re-learn (new startLearning cancels previous).
 */
object MidiFileLearnState {
    enum class State { IDLE, LEARNING }

    @Volatile
    private var _state = State.IDLE

    private var _callback: ((Int) -> Unit)? = null

    fun getState(): State = _state

    fun startLearning(callback: (Int) -> Unit) {
        // m7: cancel any previous learn before starting new one
        cancelLocked()
        _state = State.LEARNING
        _callback = callback
    }

    fun captureNote(note: Int) {
        if (_state != State.LEARNING) return
        _callback?.invoke(note)
        _callback = null
        _state = State.IDLE
    }

    fun cancel() {
        _callback = null
        _state = State.IDLE
    }

    // Internal cancel without resetting state (used by startLearning)
    private fun cancelLocked() {
        _callback = null
    }
}

/**
 * Toggle result for per-note key press — tri-state to distinguish ON/OFF/ignored.
 *
 * M3 fix: was Boolean (toggle-OFF == key-repeat both false). Now returns
 * Result enum so MainActivity can start vs stop the slot correctly.
 */
class NoteToggleStateMachine {

    /** Last event type per note: true = noteOn, false = noteOff. null = never seen. */
    private val lastEvent = mutableMapOf<Int, Boolean>()

    /** Current playback state per note: true = playing, false = stopped. */
    private val isPlaying = mutableMapOf<Int, Boolean>()

    /**
     * Process a note-on event.
     *
     * TOGGLE_ON  — first event or after note-off while stopped → start slot
     * TOGGLE_OFF — was playing, user released (noteOff), pressing again → stop slot
     * IGNORED    — key repeat (note-on without note-off between) → nothing
     *
     * Thread-safe: synchronized for concurrent access from MIDI binder thread
     * and main thread.
     */
    fun noteOn(note: Int): Result {
        synchronized(this) {
            val wasNoteOff = lastEvent[note] == false
            val firstEvent = lastEvent[note] == null
            lastEvent[note] = true
            return when {
                firstEvent -> {
                    isPlaying[note] = true
                    Result.TOGGLE_ON
                }
                wasNoteOff && isPlaying[note] == true -> {
                    // Was playing, user released, now pressing again → toggle OFF
                    isPlaying[note] = false
                    Result.TOGGLE_OFF
                }
                wasNoteOff -> {
                    // Was not playing, user released, now pressing again → toggle ON
                    isPlaying[note] = true
                    Result.TOGGLE_ON
                }
                else -> {
                    // Key repeat (note-on without note-off between) → ignored
                    Result.IGNORED
                }
            }
        }
    }

    /**
     * Process a note-off event. Never stops playback (loop keeps playing after release).
     * Records last event as noteOff. Thread-safe.
     */
    fun noteOff(note: Int) {
        synchronized(this) {
            lastEvent[note] = false
        }
    }

    /** Check if a note is currently playing. Thread-safe. */
    fun isPlaying(note: Int): Boolean = synchronized(this) {
        isPlaying[note] == true
    }

    /** Stop playback for a note AND reset state (m1: called by freeSlotForNote). */
    fun stopPlaying(note: Int) {
        synchronized(this) {
            isPlaying[note] = false
            lastEvent.remove(note) // m1: reset so first press after free is TOGGLE_ON
        }
    }

    /** Reset all state (e.g. on mapping clear). */
    fun reset() {
        synchronized(this) {
            lastEvent.clear()
            isPlaying.clear()
        }
    }

    /** Toggle result enum (M3). */
    enum class Result { TOGGLE_ON, TOGGLE_OFF, IGNORED }
}

/**
 * Convert a MIDI note number (0–127) to a human-readable name like "C3", "C#4", "B0".
 * MIDI note 60 = C4 (middle C).
 * Uses standard scientific pitch notation: note 0 = C-1.
 */
fun noteToName(note: Int): String {
    val names = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    val octave = note / 12 - 1
    return "${names[note % 12]}$octave"
}