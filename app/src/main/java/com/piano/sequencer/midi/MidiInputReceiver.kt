package com.piano.sequencer.midi

import android.media.midi.MidiReceiver

class MidiInputReceiver : MidiReceiver() {
    interface Callback {
        fun onNoteOn(channel: Int, note: Int, velocity: Int)
        fun onNoteOff(channel: Int, note: Int, velocity: Int)
        fun onControlChange(channel: Int, controller: Int, value: Int)
        fun onProgramChange(channel: Int, program: Int)
        fun onPitchBend(channel: Int, value: Int)
        fun onChannelPressure(channel: Int, value: Int)
    }

    @Volatile
    private var callback: Callback? = null

    fun setCallback(callback: Callback?) {
        this.callback = callback
    }

    // Swallows a callback exception so a single bad message does not abort
    // parsing of the rest of the buffer (matches the original per-message
    // try/catch behavior) and never crashes the MIDI callback chain.
    private inline fun safe(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            // Prevent a callback exception from crashing the MIDI callback chain
        }
    }

    override fun onSend(data: ByteArray, offset: Int, length: Int, timestamp: Long) {
        val cb = callback ?: return
        try {
            MidiMessageParser.parse(data, offset, length, object : MidiMessageParser.Handler {
                override fun onNoteOn(channel: Int, note: Int, velocity: Int) {
                    safe { cb.onNoteOn(channel, note, velocity) }
                }
                override fun onNoteOff(channel: Int, note: Int, velocity: Int) {
                    safe { cb.onNoteOff(channel, note, velocity) }
                }
                override fun onControlChange(channel: Int, controller: Int, value: Int) {
                    safe { cb.onControlChange(channel, controller, value) }
                }
                override fun onProgramChange(channel: Int, program: Int) {
                    safe { cb.onProgramChange(channel, program) }
                }
                override fun onPitchBend(channel: Int, value: Int) {
                    safe { cb.onPitchBend(channel, value) }
                }
                override fun onChannelPressure(channel: Int, value: Int) {
                    safe { cb.onChannelPressure(channel, value) }
                }
            })
        } catch (e: Exception) {
            // Backstop: never let an exception escape onSend into the MIDI chain
        }
    }
}