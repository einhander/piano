package com.piano.sequencer.midi

import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Trigger types for a sequencer cell. */
const val TRIGGER_NOTE = "NOTE"
const val TRIGGER_CC = "CC"
const val TRIGGER_PITCH_BEND = "PITCH_BEND"

/**
 * A sequencer cell: one slot that can hold a MIDI file with learned trigger, loop, tempo,
 * and optional channel remap.
 *
 * D1: mapping key = trigger only (channel-agnostic). A trigger is either a MIDI
 * note (NOTE), a CC number (CC), or pitch bend (PITCH_BEND).
 * D3: single tempo per file, user-overridable, default = file's initial tempo.
 *
 * B4: `triggerType`/`ccNumber` were added later — old saved JSON lacks them and
 * deserializes as NOTE with the existing `note` (kotlinx default values).
 * For CC / PITCH_BEND cells `note` is -1 (no note); the trigger is fully
 * described by `triggerType` + `ccNumber`.
 */
@Serializable
data class SequencerCell(
    val id: Int,
    val note: Int = -1,        // learned MIDI key; -1 = none (always -1 for CC/PITCH_BEND cells)
    val filePath: String = "", // "" = no file
    val loop: Boolean = false,
    val tempo: Double = 120.0, // BPM, 20–300
    val channel: Int = -1,     // -1 = from file, 0-15 = remap all events
    val triggerType: String = TRIGGER_NOTE, // "NOTE" / "CC" / "PITCH_BEND"
    val ccNumber: Int? = null  // CC number; set for triggerType == "CC", null otherwise
) {
    /** True if the cell has a usable trigger (note >= 0, or a CC/pitch-bend trigger). */
    fun hasTrigger(): Boolean = when (triggerType) {
        TRIGGER_CC, TRIGGER_PITCH_BEND -> true
        else -> note >= 0
    }

    /**
     * Encoded trigger key in one Int space: NOTE 0–127, CC 128–255 (128+cc),
     * PITCH_BEND 256. Used as the slot-map key and as the toggle state machine
     * key, so all three trigger types share one code path.
     */
    fun triggerKey(): Int = when (triggerType) {
        TRIGGER_CC -> 128 + (ccNumber ?: 0)
        TRIGGER_PITCH_BEND -> 256
        else -> note
    }

    /** Trigger data for store lookups: note for NOTE, ccNumber for CC, 0 for PITCH_BEND. */
    fun triggerData(): Int = when (triggerType) {
        TRIGGER_CC -> ccNumber ?: 0
        else -> note
    }
}

/** A learned MIDI event: the first event of ANY type wins during learn mode. */
sealed class LearnedEvent {
    data class Note(val note: Int) : LearnedEvent()
    data class CC(val ccNumber: Int) : LearnedEvent()
    data object PitchBend : LearnedEvent()
}

/** Encoded trigger key for a learned event (same space as [SequencerCell.triggerKey]). */
fun triggerKeyOf(event: LearnedEvent): Int = when (event) {
    is LearnedEvent.Note -> event.note
    is LearnedEvent.CC -> 128 + event.ccNumber
    is LearnedEvent.PitchBend -> 256
}

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

    /** Find first cell with the given note; never matches note < 0. NOTE cells only. */
    fun findByNote(note: Int): SequencerCell? = synchronized(lock) {
        if (note < 0) return@synchronized null
        _cells.find { it.triggerType == TRIGGER_NOTE && it.note == note }
    }

    /** Find first cell mapped to the given CC number; null when absent or out of 0–127. */
    fun findByCC(ccNumber: Int): SequencerCell? = synchronized(lock) {
        if (ccNumber !in 0..127) return@synchronized null
        _cells.find { it.triggerType == TRIGGER_CC && it.ccNumber == ccNumber }
    }

    /** Find first cell mapped to pitch bend; null when absent. */
    fun findByPitchBend(): SequencerCell? = synchronized(lock) {
        _cells.find { it.triggerType == TRIGGER_PITCH_BEND }
    }

    /** Find first cell with the given trigger (type + data). */
    fun findByTrigger(type: String, data: Int): SequencerCell? = when (type) {
        TRIGGER_CC -> findByCC(data)
        TRIGGER_PITCH_BEND -> findByPitchBend()
        else -> findByNote(data)
    }

    /**
     * Apply a learned trigger to the cell with [cellId]:
     * 1. Uniqueness — remove the same trigger from all other cells (one trigger → one cell).
     * 2. Set the trigger on the target cell (note=-1 for CC/PITCH_BEND).
     *
     * Returns the updated target cell, or null when the cell no longer exists.
     */
    fun applyLearnedTrigger(cellId: Int, event: LearnedEvent): SequencerCell? {
        val cur = get(cellId) ?: return null
        val key = triggerKeyOf(event)
        for (c2 in all()) {
            if (c2.id != cellId && c2.hasTrigger() && c2.triggerKey() == key) {
                set(c2.copy(triggerType = TRIGGER_NOTE, ccNumber = null, note = -1))
            }
        }
        val updated = when (event) {
            is LearnedEvent.Note ->
                cur.copy(note = event.note, triggerType = TRIGGER_NOTE, ccNumber = null)
            is LearnedEvent.CC ->
                cur.copy(note = -1, triggerType = TRIGGER_CC, ccNumber = event.ccNumber)
            is LearnedEvent.PitchBend ->
                cur.copy(note = -1, triggerType = TRIGGER_PITCH_BEND, ccNumber = null)
        }
        set(updated)
        return updated
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
 * Learn state for MIDI file trigger assignment (note, CC, or pitch bend).
 *
 * M1 fix: real state machine — @Volatile state, startLearning sets LEARNING,
 * capture* checks LEARNING state, cancel() resets to IDLE.
 * m7: cancel on re-learn (new startLearning cancels previous).
 * B4: the callback takes a [LearnedEvent]; the FIRST event of ANY type wins —
 * capture, exit LEARNING, invoke the callback. (The 10s timeout in
 * MidiFilesPanel is a no-op once the state is IDLE.)
 */
object MidiFileLearnState {
    enum class State { IDLE, LEARNING }

    @Volatile
    private var _state = State.IDLE

    private var _callback: ((LearnedEvent) -> Unit)? = null

    fun getState(): State = _state

    fun startLearning(callback: (LearnedEvent) -> Unit) {
        // m7: cancel any previous learn before starting new one
        cancelLocked()
        _state = State.LEARNING
        _callback = callback
    }

    fun captureNote(note: Int) = capture(LearnedEvent.Note(note))

    fun captureCC(ccNumber: Int) = capture(LearnedEvent.CC(ccNumber))

    fun capturePitchBend() = capture(LearnedEvent.PitchBend)

    private fun capture(event: LearnedEvent) {
        if (_state != State.LEARNING) return
        _callback?.invoke(event)
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
 * Edge detector for a continuous controller (CC / pitch bend).
 *
 * Press semantics (B4, time-based idle detection): a press is a value change
 * that happens AFTER A STABLE VALUE — at least [threshold] ms since the last
 * event. While the value keeps changing (wheel still moving, events closer
 * than [threshold]) = key repeat (ignored). A same-value retransmit = repeat.
 *
 * [now] is passed in by the caller (SystemClock.uptimeMillis on the MIDI
 * binder thread) so this class stays pure-JVM testable. Pre-allocated,
 * thread-safe (MIDI binder threads).
 *
 * Default [threshold] = 50 ms: wheel sweep steps arrive ≈10–30 ms apart,
 * deliberate separate gestures are >100 ms apart.
 */
class ContinuousPressDetector(size: Int, val threshold: Long = 50) {

    /** Last-seen value per index; -1 = "no stable value yet" (sentinel). */
    private val lastValue = IntArray(size).apply { fill(-1) }

    /** Last event time per index (ms); 0 = never. */
    private val lastEventTime = LongArray(size)
    private val lock = Any()

    /**
     * @param now current time in ms (SystemClock.uptimeMillis).
     * @return true if this is a press: the value changed AND the controller was
     *   stable (≥ [threshold] ms since the last event, or never seen).
     * A false return means key repeat — the caller should ignore (but consume) the event.
     *
     * A value-change repeat (still moving) REFRESHES the idle timer — the
     * controller is still active, so the "stable" window restarts. A same-value
     * retransmit does NOT refresh it — an unchanged value confirms the
     * controller is stable, so the next change is a press.
     */
    fun isPress(index: Int, value: Int, now: Long): Boolean = synchronized(lock) {
        if (index !in 0 until lastValue.size) return@synchronized false
        if (value == lastValue[index]) return@synchronized false // same-value retransmit (stable)
        val last = lastEventTime[index]
        if (last != 0L && now - last < threshold) {
            lastEventTime[index] = now // still moving — refresh the idle timer
            return@synchronized false
        }
        lastValue[index] = value
        lastEventTime[index] = now
        true
    }

    /** Reset one entry (e.g. when the mapping is removed). Clears value AND time. */
    fun reset(index: Int) {
        synchronized(lock) {
            if (index in 0 until lastValue.size) {
                lastValue[index] = -1
                lastEventTime[index] = 0
            }
        }
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
     * loop == false (one-shot): every fresh press (re)starts playback; key repeat
     * (note-on without note-off between) is IGNORED.
     *
     * loop == true (toggle): press after note-off while stopped → TOGGLE_ON;
     * press after note-off while playing → TOGGLE_OFF; key repeat → IGNORED.
     *
     * TOGGLE_ON  — start slot
     * TOGGLE_OFF — stop slot
     * IGNORED    — key repeat (loop=false) or key repeat (loop=true) → nothing
     *
     * Caveat: a genuinely lost note-off (USB glitch, port disconnect) makes the
     * next fresh press look like a key repeat and be IGNORED; the press after a
     * real release works normally. Applies to both modes.
     *
     * Thread-safe: synchronized for concurrent access from MIDI binder thread
     * and main thread.
     */
    fun noteOn(note: Int, loop: Boolean): Result {
        synchronized(this) {
            if (!loop) {
                // One-shot mode: every fresh press (re)starts; key repeat (no
                // note-off between) must not retrigger.
                if (lastEvent[note] == true) return Result.IGNORED
                lastEvent[note] = true
                isPlaying[note] = true
                return Result.TOGGLE_ON
            }
            // Loop mode: existing toggle logic, unchanged.
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

    /**
     * Process a press of a CONTINUOUS trigger (CC / pitch bend; key = encoded
     * trigger key, 128+cc or 256).
     *
     * Unlike [noteOn], a continuous trigger has no release event, so there is
     * no internal key-repeat detection — the caller already filtered repeats
     * (same-value events, see ContinuousPressDetector). Every call is a fresh
     * press: loop == false → (re)start (TOGGLE_ON); loop == true → toggle
     * on/off. Thread-safe.
     */
    fun press(key: Int, loop: Boolean): Result {
        synchronized(this) {
            return if (loop) {
                val wasPlaying = isPlaying[key] == true
                isPlaying[key] = !wasPlaying
                if (wasPlaying) Result.TOGGLE_OFF else Result.TOGGLE_ON
            } else {
                isPlaying[key] = true
                Result.TOGGLE_ON
            }
        }
    }

    /** Check if a note is currently playing. Thread-safe. */
    fun isPlaying(note: Int): Boolean = synchronized(this) {
        isPlaying[note] == true
    }

    /** Stop playback for a trigger key AND reset state (m1: called by freeSlotForTrigger). */
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