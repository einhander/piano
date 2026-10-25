package com.piano.sequencer.midi

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Per-import MIDI instrument banks. Channel ranges are 16-channel aligned and never overlap. */
class FileInstrumentSetStore(private val prefs: SharedPreferences) {
    @Serializable
    data class InstrumentSet(val id: String, val name: String, val programs: List<Int>, val channelBase: Int)

    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    companion object {
        private const val KEY = "midi_file_instrument_sets"
        private var singleton: FileInstrumentSetStore? = null
        fun initialPrograms(): List<Int> = MidiInitialPrograms.defaults()
        fun get(context: Context): FileInstrumentSetStore = synchronized(this) {
            singleton ?: FileInstrumentSetStore(context.getSharedPreferences("piano_prefs", Context.MODE_PRIVATE)).also { singleton = it }
        }
    }

    fun all(): List<InstrumentSet> = synchronized(lock) { read() }

    /** Remove sets absent from the complete persisted cell-reference snapshot. */
    fun removeUnreferenced(referencedIds: Set<String>): Int = synchronized(lock) {
        val sets = read()
        val retained = sets.filter { it.id in referencedIds }
        val removed = sets.size - retained.size
        if (removed > 0) write(retained)
        removed
    }

    /** Create and associate set for imported cell; null means capacity exhausted. */
    fun createForCell(cell: SequencerCell, prefs: SharedPreferences, name: String = "${cell.filePath.substringAfterLast('/')} instruments"): SequencerCell? {
        return createForCell(cell, initialPrograms(), name)
    }

    fun createForCell(cell: SequencerCell, programs: List<Int>, name: String = "${cell.filePath.substringAfterLast('/')} instruments"): SequencerCell? {
        val set = create(name, programs) ?: return null
        return cell.copy(instrumentSetId = set.id, trackInstrumentSets = emptyMap())
    }

    /** Reuse old range only when this cell is its sole persistent owner. */
    fun replaceForCell(cell: SequencerCell, oldSetId: String?, referencedIds: Set<String>, programs: List<Int>, name: String): SequencerCell? = synchronized(lock) {
        val existing = read().firstOrNull { it.id == oldSetId }
        if (existing != null && oldSetId !in referencedIds) {
            write(read().map { if (it.id == oldSetId) it.copy(name = name, programs = normalize(programs)) else it })
            return@synchronized cell.copy(instrumentSetId = oldSetId, trackInstrumentSets = emptyMap())
        }
        val created = create(name, programs) ?: return@synchronized null
        cell.copy(instrumentSetId = created.id, trackInstrumentSets = emptyMap())
    }

    fun createForCell(cell: SequencerCell, name: String = "${cell.filePath.substringAfterLast('/')} instruments"): SequencerCell? =
        createForCell(cell, prefs, name)

    fun updatePreset(id: String, channel: Int, packedBankProgram: Int): Boolean {
        if (channel !in 0..15 || packedBankProgram !in 0..0x3fff_ff) return false
        val set = all().firstOrNull { it.id == id } ?: return false
        return updatePrograms(id, set.programs.toMutableList().also { it[channel] = packedBankProgram })
    }

    /** Returns null at capacity or when no safe range remains. */
    fun create(name: String, mainPrograms: List<Int>): InstrumentSet? = synchronized(lock) {
        val sets = read()
        if (sets.size >= 15) return@synchronized null
        val used = sets.map { it.channelBase }.toSet()
        val base = (16..240 step 16).firstOrNull { it !in used } ?: return@synchronized null
        val item = InstrumentSet(java.util.UUID.randomUUID().toString(), name, normalize(mainPrograms), base)
        write(sets + item)
        item
    }

    fun updatePrograms(id: String, programs: List<Int>): Boolean = synchronized(lock) {
        val sets = read(); val index = sets.indexOfFirst { it.id == id }
        if (index < 0) return@synchronized false
        write(sets.toMutableList().also { it[index] = it[index].copy(programs = normalize(programs)) })
        true
    }

    fun remove(id: String): Boolean = synchronized(lock) {
        val sets = read(); val next = sets.filterNot { it.id == id }
        if (next.size == sets.size) false else { write(next); true }
    }

    private fun read(): List<InstrumentSet> = try {
        json.decodeFromString<List<InstrumentSet>>(prefs.getString(KEY, "[]") ?: "[]")
            .filter { it.channelBase in 16..240 && it.channelBase % 16 == 0 && it.programs.size == 16 }
            .distinctBy { it.channelBase }.take(15)
    } catch (_: Exception) { emptyList() }

    private fun write(sets: List<InstrumentSet>) { prefs.edit().putString(KEY, json.encodeToString(sets)).apply() }
    private fun normalize(programs: List<Int>) = List(16) { programs.getOrElse(it) { 0 }.coerceIn(0, 0x3fff_ff) }
}

enum class InstrumentSetSource { MAIN, FILE }

/** Resolve local channel selection without allowing source track numbers to become synth channels. */
fun resolveSynthChannel(localChannel: Int, source: InstrumentSetSource, channelBase: Int): Int {
    if (localChannel !in 0..15) return -1
    return if (source == InstrumentSetSource.MAIN) localChannel else (channelBase + localChannel).takeIf { it in 16..255 } ?: -1
}
