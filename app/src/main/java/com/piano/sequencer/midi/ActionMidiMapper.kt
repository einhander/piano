package com.piano.sequencer.midi

import android.content.SharedPreferences

enum class MidiAction { RECORD, PANIC }
enum class MidiTriggerType { NOTE_ON, CC, PROGRAM }

data class ActionMidiMapping(val type: MidiTriggerType, val channel: Int, val data1: Int) {
    init { require(channel in 0..15 && data1 in 0..127) }
    fun describe(): String = "${type.name.replace('_', ' ')} · ch ${channel + 1} · $data1"
}

class ActionMidiMapper(private val prefs: SharedPreferences) {
    private val lock = Any()
    private var learning: MidiAction? = null
    private val ccArmed = mutableSetOf<MidiAction>()

    fun mapping(action: MidiAction): ActionMidiMapping? = synchronized(lock) { read(action) }
    fun isLearning(): Boolean = synchronized(lock) { learning != null }
    fun startLearn(action: MidiAction): Boolean = synchronized(lock) {
        if (learning != null) false else { learning = action; ccArmed.clear(); true }
    }
    fun cancelLearn() = synchronized(lock) { learning = null; ccArmed.clear() }

    /** Returns event consumption and any action to dispatch. */
    fun event(type: MidiTriggerType, channel: Int, data1: Int, value: Int): Result {
        synchronized(lock) {
            val target = learning
            if (target != null) {
                val valid = type != MidiTriggerType.NOTE_ON || value > 0
                if (!valid) return Result(consumed = true)
                val incoming = ActionMidiMapping(type, channel, data1)
                val conflict = MidiAction.entries.firstOrNull { it != target && read(it) == incoming }
                if (conflict != null) { learning = null; ccArmed.clear(); return Result(consumed = true, conflict = conflict) }
                write(target, incoming)
                learning = null
                ccArmed.clear()
                return Result(consumed = true, captured = target)
            }
            val mapping = MidiAction.entries.firstNotNullOfOrNull { action ->
                read(action)?.takeIf { it.type == type && it.channel == channel && it.data1 == data1 }?.let { action to it }
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
    fun consumesNoteOff(channel: Int, note: Int): Boolean = synchronized(lock) {
        MidiAction.entries.any { action ->
            read(action)?.let { it.type == MidiTriggerType.NOTE_ON && it.channel == channel && it.data1 == note } == true
        }
    }

    private fun key(action: MidiAction) = "midi_action_${action.name.lowercase()}"
    private fun read(action: MidiAction): ActionMidiMapping? {
        val raw = prefs.getString(key(action), null) ?: return null
        val pieces = raw.split(':')
        if (pieces.size != 3) return null
        return runCatching { ActionMidiMapping(MidiTriggerType.valueOf(pieces[0]), pieces[1].toInt(), pieces[2].toInt()) }.getOrNull()
    }
    private fun write(action: MidiAction, mapping: ActionMidiMapping) {
        prefs.edit().putString(key(action), "${mapping.type.name}:${mapping.channel}:${mapping.data1}").apply()
    }
    data class Result(val consumed: Boolean = false, val action: MidiAction? = null,
                      val captured: MidiAction? = null, val conflict: MidiAction? = null)
}
