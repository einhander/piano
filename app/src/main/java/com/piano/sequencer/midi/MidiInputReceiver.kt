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

    override fun onSend(data: ByteArray, offset: Int, length: Int, timestamp: Long) {
        val end = offset + length
        var pos = offset
        while (pos < end) {
            val statusByte = data[pos].toInt() and 0xFF
            val channel = statusByte and 0x0F
            val messageType = statusByte and 0xF0
            pos++

            callback?.let { cb ->
                try {
                    when (messageType) {
                        0x90 -> { // Note On
                            if (pos < end) {
                                val data1 = data[pos].toInt() and 0xFF
                                pos++
                                if (pos < end) {
                                    val data2 = data[pos].toInt() and 0xFF
                                    pos++
                                    if (data2 > 0) {
                                        cb.onNoteOn(channel, data1, data2)
                                    } else {
                                        cb.onNoteOff(channel, data1, data2)
                                    }
                                }
                            }
                        }
                        0x80 -> { // Note Off
                            if (pos < end) {
                                val data1 = data[pos].toInt() and 0xFF
                                pos++
                                if (pos < end) {
                                    val data2 = data[pos].toInt() and 0xFF
                                    cb.onNoteOff(channel, data1, data2)
                                }
                            }
                        }
                        0xB0 -> { // Control Change
                            if (pos < end) {
                                val data1 = data[pos].toInt() and 0xFF
                                pos++
                                if (pos < end) {
                                    val data2 = data[pos].toInt() and 0xFF
                                    cb.onControlChange(channel, data1, data2)
                                }
                            }
                        }
                        0xC0 -> { // Program Change
                            if (pos < end) {
                                val data1 = data[pos].toInt() and 0xFF
                                cb.onProgramChange(channel, data1)
                            }
                        }
                        0xE0 -> { // Pitch Bend
                            if (pos < end) {
                                val data1 = data[pos].toInt() and 0xFF
                                pos++
                                if (pos < end) {
                                    val data2 = data[pos].toInt() and 0xFF
                                    cb.onPitchBend(channel, (data2 shl 7) or data1)
                                }
                            }
                        }
                        0xD0 -> { // Channel Pressure
                            if (pos < end) {
                                val data1 = data[pos].toInt() and 0xFF
                                cb.onChannelPressure(channel, data1)
                            }
                        }
                        0xF0 -> { // System Exclusive / Real-Time — skip
                            if (messageType == 0xF0) {
                                // Skip until 0xF7
                                while (pos < end) {
                                    if (data[pos].toInt() and 0xFF == 0xF7) {
                                        pos++
                                        break
                                    }
                                    pos++
                                }
                            }
                        }
                        else -> {
                            // Unknown message, skip remaining data bytes for this status
                        }
                    }
                } catch (e: Exception) {
                    // Prevent callback exception from crashing MIDI callback chain
                }
            }
        }
        }
}