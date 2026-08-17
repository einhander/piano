package com.piano.sequencer.midi

import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Mapping of a MIDI keyboard note number (0–127) to a MIDI file path.
 *
 * D1: mapping key = note number only (channel-agnostic).
 * D3: single tempo per file, user-overridable, default = file's initial tempo.
 */
@Serializable
data class MidiFileAssignment(
    val note: Int,
    val filePath: String,
    val loop: Boolean,
    val tempo: Double // BPM, 20–300
)

/**
 * Persisted mapping store — JSON in SharedPreferences under key "midi_file_map".
 *
 * Format: {"36": {"note":36,"filePath":"...","loop":true,"tempo":120.0}, ...}
 * Load on init, save on every mutation. Thread-safe via synchronized.
 *
 * M2 fix: SINGLE instance shared between MainActivity and MidiFilesPanel.
 */
class MidiFileMappingStore(private val prefs: SharedPreferences) {

    companion object {
        private const val KEY = "midi_file_map"
        private val JSON = Json { ignoreUnknownKeys = true }
    }

    private val lock = Any()

    /** All assignments, keyed by note number. */
    private var _map = mutableMapOf<Int, MidiFileAssignment>()

    init {
        load()
    }

    /** Parse JSON from prefs into _map. */
    private fun load() {
        synchronized(lock) {
            val json = prefs.getString(KEY, null)
            _map = if (json.isNullOrEmpty()) {
                mutableMapOf()
            } else {
                try {
                    val decoded: Map<String, MidiFileAssignment> =
                        JSON.decodeFromString(json)
                    // n5: remove no-op mapValues; just convert key type
                    decoded.mapKeys { it.key.toInt() }.toMutableMap()
                } catch (_: Exception) {
                    mutableMapOf()
                }
            }
        }
    }

    /** Write _map to prefs as JSON. */
    private fun save() {
        synchronized(lock) {
            val json = JSON.encodeToString(_map)
            prefs.edit().putString(KEY, json).apply()
        }
    }

    /** Return all assignments (unmodifiable snapshot). */
    fun all(): Map<Int, MidiFileAssignment> = synchronized(lock) {
        _map.toMap()
    }

    /** Get assignment for a specific note, or null. */
    fun get(note: Int): MidiFileAssignment? = synchronized(lock) {
        _map[note]
    }

    /**
     * Set (or replace) an assignment for a note.
     * Re-learning the same note REPLACES the old assignment.
     */
    fun set(assignment: MidiFileAssignment) {
        synchronized(lock) {
            _map[assignment.note] = assignment
            save()
        }
    }

    /** Remove assignment for a note. */
    fun remove(note: Int) {
        synchronized(lock) {
            _map.remove(note)
            save()
        }
    }

    /** Clear all assignments. */
    fun clear() {
        synchronized(lock) {
            _map.clear()
            save()
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