package com.piano.sequencer.midi

import com.piano.sequencer.AppLogger

/** Shared note ingress: trigger/learn gets first refusal; only live events dispatch. */
class MidiIngressRouter(
    private val noteOnTrigger: (Int, Int, Int, String?) -> Boolean,
    private val noteOffTrigger: (Int, Int, Int, String?) -> Boolean,
    private val dispatch: (status: Int, d1: Int, d2: Int, channel: Int) -> Unit,
    private val beforeNoteOn: (channel: Int, note: Int, velocity: Int) -> Unit = { _, _, _ -> }
) {
    constructor(
        noteOnTrigger: (Int, Int, Int) -> Boolean,
        noteOffTrigger: (Int, Int, Int) -> Boolean,
        dispatch: (Int, Int, Int, Int) -> Unit,
        beforeNoteOn: (Int, Int, Int) -> Unit = { _, _, _ -> }
    ) : this({ a, b, c, _ -> noteOnTrigger(a, b, c) }, { a, b, c, _ -> noteOffTrigger(a, b, c) }, dispatch, beforeNoteOn)
    fun noteOn(channel: Int, note: Int, velocity: Int, source: String? = null) {
        beforeNoteOn(channel, note, velocity)
        val consumed = noteOnTrigger(channel, note, velocity, source)
        AppLogger.info("MIDI", "NOTE ON ch=${channel + 1} note=$note velocity=$velocity outcome=${if (consumed) "consumed" else "not-consumed/forwarded"}")
        if (!consumed) dispatch(0x90, note, velocity, channel)
    }
    fun noteOff(channel: Int, note: Int, velocity: Int, source: String? = null) {
        val consumed = noteOffTrigger(channel, note, velocity, source)
        AppLogger.info("MIDI", "NOTE OFF ch=${channel + 1} note=$note velocity=$velocity outcome=${if (consumed) "consumed" else "not-consumed/forwarded"}")
        if (!consumed) dispatch(0x80, note, velocity, channel)
    }
}
