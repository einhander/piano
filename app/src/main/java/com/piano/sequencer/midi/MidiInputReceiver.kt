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

    override fun onReceive(status: Byte, data1: Byte, data2: Byte, timestamp: Long): Boolean {
        val statusByte = status.toInt() and 0xFF
        val data1Int = data1.toInt() and 0xFF
        val data2Int = data2.toInt() and 0xFF
        val channel = statusByte and 0x0F
        val messageType = statusByte and 0xF0

        callback?.let { cb ->
            try {
                when (messageType) {
                    0x90 -> { // Note On
                        if (data2Int > 0) {
                            cb.onNoteOn(channel, data1Int, data2Int)
                        } else {
                            cb.onNoteOff(channel, data1Int, data2Int)
                        }
                    }
                    0x80 -> cb.onNoteOff(channel, data1Int, data2Int)
                    0xB0 -> cb.onControlChange(channel, data1Int, data2Int)
                    0xC0 -> cb.onProgramChange(channel, data1Int)
                    0xE0 -> cb.onPitchBend(channel, (data2Int shl 7) or data1Int)
                    0xD0 -> cb.onChannelPressure(channel, data1Int)
                }
            } catch (e: Exception) {
                // Prevent callback exception from crashing MIDI callback chain
            }
        }
        return true
    }
}