package com.piano.sequencer.midi

object MidiMessageParser {
    interface Handler {
        fun onNoteOn(channel: Int, note: Int, velocity: Int)
        fun onNoteOff(channel: Int, note: Int, velocity: Int)
        fun onControlChange(channel: Int, controller: Int, value: Int)
        fun onProgramChange(channel: Int, program: Int)
        fun onPitchBend(channel: Int, value: Int)
        fun onChannelPressure(channel: Int, value: Int)
    }

    /** Stateful parser. One instance belongs to one MIDI input stream. */
    class StreamParser {
        private var runningStatus = -1
        private var pendingStatus = -1
        private val pending = ArrayList<Int>(2)
        private var inSysex = false

        fun parse(data: ByteArray, offset: Int, length: Int, handler: Handler) {
            val end = (offset + length).coerceAtMost(data.size)
            var pos = offset.coerceAtLeast(0)
            while (pos < end) {
                val b = data[pos++].toInt() and 0xff
                if (b >= 0xf8) continue // realtime never affects message state
                if (inSysex) {
                    if (b == 0xf7) inSysex = false
                    continue
                }
                if (b >= 0x80) {
                    if (b == 0xf0) {
                        inSysex = true
                        clearMessage(false)
                    } else if (b >= 0xf1) {
                        clearMessage(false)
                        pos = skipSystemCommon(b, data, pos, end)
                    } else {
                        startChannelMessage(b)
                    }
                    continue
                }
                if (pendingStatus < 0) {
                    if (runningStatus < 0) continue
                    pendingStatus = runningStatus
                }
                pending.add(b)
                if (pending.size == dataLength(pendingStatus)) {
                    emit(pendingStatus, pending, handler)
                    runningStatus = pendingStatus
                    pendingStatus = -1
                    pending.clear()
                }
            }
        }

        private fun startChannelMessage(status: Int) {
            if (status in 0x80..0xef) {
                runningStatus = status
                pendingStatus = status
                pending.clear()
            } else {
                clearMessage(false)
            }
        }

        private fun clearMessage(keepRunning: Boolean) {
            pendingStatus = -1
            pending.clear()
            if (!keepRunning) runningStatus = -1
        }

        private fun skipSystemCommon(status: Int, bytes: ByteArray, start: Int, end: Int): Int {
            val count = when (status) { 0xf1, 0xf3 -> 1; 0xf2 -> 2; else -> 0 }
            var pos = start
            var left = count
            while (pos < end && left > 0) {
                val b = bytes[pos++].toInt() and 0xff
                if (b >= 0x80) return pos - 1 // next status starts next message
                left--
            }
            return pos
        }

        private fun dataLength(status: Int) = if ((status and 0xf0) == 0xc0 ||
            (status and 0xf0) == 0xd0) 1 else 2

        private fun emit(status: Int, values: List<Int>, handler: Handler) {
            val channel = status and 0x0f
            when (status and 0xf0) {
                0x80 -> handler.onNoteOff(channel, values[0], values[1])
                0x90 -> if (values[1] == 0) handler.onNoteOff(channel, values[0], 0)
                         else handler.onNoteOn(channel, values[0], values[1])
                // Handler has no per-note pressure callback; never relabel
                // polyphonic aftertouch as channel pressure.
                0xa0 -> Unit
                0xb0 -> handler.onControlChange(channel, values[0], values[1])
                0xc0 -> handler.onProgramChange(channel, values[0])
                0xd0 -> handler.onChannelPressure(channel, values[0])
                0xe0 -> handler.onPitchBend(channel, (values[1] shl 7) or values[0])
            }
        }
    }

    fun parse(data: ByteArray, offset: Int, length: Int, handler: Handler) {
        StreamParser().parse(data, offset, length, handler)
    }
}
