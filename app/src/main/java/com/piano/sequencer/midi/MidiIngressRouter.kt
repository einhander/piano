package com.piano.sequencer.midi

import com.piano.sequencer.AppLogger

/** Shared note ingress: trigger/learn gets first refusal; only live events dispatch. */
class MidiIngressRouter(
    private val noteOnTrigger: (Int, Int, Int) -> Boolean,
    private val noteOffTrigger: (Int, Int, Int) -> Boolean,
    private val dispatch: (status: Int, d1: Int, d2: Int, channel: Int) -> Unit,
    private val beforeNoteOn: (channel: Int, note: Int, velocity: Int) -> Unit = { _, _, _ -> }
) {
    fun noteOn(channel: Int, note: Int, velocity: Int) {
        beforeNoteOn(channel, note, velocity)
        val consumed = noteOnTrigger(channel, note, velocity)
        AppLogger.info("MIDI", "NOTE ON ch=${channel + 1} note=$note velocity=$velocity outcome=${if (consumed) "consumed" else "not-consumed/forwarded"}")
        if (!consumed) dispatch(0x90, note, velocity, channel)
    }
    fun noteOff(channel: Int, note: Int, velocity: Int) {
        val consumed = noteOffTrigger(channel, note, velocity)
        AppLogger.info("MIDI", "NOTE OFF ch=${channel + 1} note=$note velocity=$velocity outcome=${if (consumed) "consumed" else "not-consumed/forwarded"}")
        if (!consumed) dispatch(0x80, note, velocity, channel)
    }
}
