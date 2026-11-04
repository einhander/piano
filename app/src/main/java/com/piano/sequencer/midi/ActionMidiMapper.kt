package com.piano.sequencer.midi

import android.content.SharedPreferences

enum class MidiAction { RECORD, PANIC }
enum class MidiTriggerType { NOTE_ON, CC, PROGRAM }

data class ActionMidiMapping(val type: MidiTriggerType, val channel: Int, val data1: Int, val source: String? = null) {
    init { require(channel in 0..15 && data1 in 0..127) }
    fun describe(): String = "${type.name.replace('_', ' ')} · ch ${channel + 1} · $data1 · " +
        (source ?: "Inactive — learn again to select a MIDI device")
}

class ActionMidiMapper(private val prefs: SharedPreferences) {
    private val lock = Any()
    private var learning: MidiAction? = null
    private val ccArmed = mutableSetOf<MidiAction>()

    fun mapping(action: MidiAction): ActionMidiMapping? = synchronized(lock) { read(action) }
    /** Remove persisted binding for an action (Record/Panic unlearn). */
    fun clear(action: MidiAction) = synchronized(lock) {
        prefs.edit().remove(key(action)).apply()
        ccArmed -= action
    }
    fun isLearning(): Boolean = synchronized(lock) { learning != null }
    fun startLearn(action: MidiAction): Boolean = synchronized(lock) {
        if (learning != null) false else { learning = action; ccArmed.clear(); true }
    }
    fun cancelLearn() = synchronized(lock) { learning = null; ccArmed.clear() }

    /** Returns event consumption and any action to dispatch. */
    fun event(type: MidiTriggerType, channel: Int, data1: Int, value: Int, source: String = "unknown"): Result {
        synchronized(lock) {
            val target = learning
            if (target != null) {
                val valid = type != MidiTriggerType.NOTE_ON || value > 0
                if (!valid) return Result(consumed = true)
                val incoming = ActionMidiMapping(type, channel, data1, source)
                val conflict = MidiAction.entries.firstOrNull { it != target && read(it) == incoming }
                if (conflict != null) { learning = null; ccArmed.clear(); return Result(consumed = true, conflict = conflict) }
                write(target, incoming)
                learning = null
                ccArmed.clear()
                return Result(consumed = true, captured = target)
            }
            val mapping = MidiAction.entries.firstNotNullOfOrNull { action ->
                read(action)?.takeIf { it.type == type && it.channel == channel && it.data1 == data1 && it.source == source }?.let { action to it }
            } ?: return Result()
            if (type == MidiTriggerType.NOTE_ON && value <= 0) return Result()
            if (type == MidiTriggerType.CC) {
                if (value < 64) { ccArmed += mapping.first; return Result(consumed = true) }
                if (mapping.first !in ccArmed) return Result(consumed = true)
                ccArmed -= mapping.first
            }
            return Result(consumed = true, action = mapping.first)
        }
    }

    /** A learned note-on owns its release too, so note-off cannot leak to the synth. */
    fun consumesNoteOff(channel: Int, note: Int, source: String = "unknown"): Boolean = synchronized(lock) {
        MidiAction.entries.any { action ->
            read(action)?.let { it.type == MidiTriggerType.NOTE_ON && it.channel == channel && it.data1 == note && it.source == source } == true
        }
    }

    private fun key(action: MidiAction) = "midi_action_${action.name.lowercase()}"
    private fun read(action: MidiAction): ActionMidiMapping? {
        val raw = prefs.getString(key(action), null) ?: return null
        val pieces = raw.split(':')
        if (pieces.size !in 3..4) return null
        // Legacy mappings lack source identity; they remain inspectable but must be re-learned to bind safely.
        return runCatching { ActionMidiMapping(MidiTriggerType.valueOf(pieces[0]), pieces[1].toInt(), pieces[2].toInt(), pieces.getOrNull(3)?.let { java.net.URLDecoder.decode(it, "UTF-8") }) }.getOrNull()
    }
    private fun write(action: MidiAction, mapping: ActionMidiMapping) {
        prefs.edit().putString(key(action), "${mapping.type.name}:${mapping.channel}:${mapping.data1}:${java.net.URLEncoder.encode(mapping.source ?: "", "UTF-8")}").apply()
    }
    data class Result(val consumed: Boolean = false, val action: MidiAction? = null,
                      val captured: MidiAction? = null, val conflict: MidiAction? = null)
}
