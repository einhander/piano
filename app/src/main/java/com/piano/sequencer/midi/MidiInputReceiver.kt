package com.piano.sequencer.midi

import android.media.midi.MidiReceiver
import android.os.SystemClock
import com.piano.sequencer.AppLogger

class MidiInputReceiver : MidiReceiver() {
    private val parser = MidiMessageParser.StreamParser()
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
    private var lastEmptyTraceMs = 0L

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
            AppLogger.error("MidiInputReceiver", "MIDI callback failed: ${e.stackTraceToString()}")
        }
    }

    override fun onSend(data: ByteArray, offset: Int, length: Int, timestamp: Long) {
        val cb = callback
        if (cb == null) {
            AppLogger.warn("MidiInputReceiver", "MIDI input received with no callback")
            return
        }
        var parsedEvents = 0
        try {
            parser.parse(data, offset, length, object : MidiMessageParser.Handler {
                override fun onNoteOn(channel: Int, note: Int, velocity: Int) {
                    parsedEvents++
                    AppLogger.info("MIDI", "Parsed NOTE ON ch=${channel + 1} data=$note,$velocity")
                    safe { cb.onNoteOn(channel, note, velocity) }
                }
                override fun onNoteOff(channel: Int, note: Int, velocity: Int) {
                    parsedEvents++
                    AppLogger.info("MIDI", "Parsed NOTE OFF ch=${channel + 1} data=$note,$velocity")
                    safe { cb.onNoteOff(channel, note, velocity) }
                }
                override fun onControlChange(channel: Int, controller: Int, value: Int) {
                    parsedEvents++
                    safe { cb.onControlChange(channel, controller, value) }
                }
                override fun onProgramChange(channel: Int, program: Int) {
                    parsedEvents++
                    AppLogger.info("MIDI", "Parsed PROGRAM ch=${channel + 1} data=$program")
                    safe { cb.onProgramChange(channel, program) }
                }
                override fun onPitchBend(channel: Int, value: Int) {
                    parsedEvents++
                    safe { cb.onPitchBend(channel, value) }
                }
                override fun onChannelPressure(channel: Int, value: Int) {
                    parsedEvents++
                    safe { cb.onChannelPressure(channel, value) }
                }
            })
        } catch (e: Exception) {
            // Backstop: never let an exception escape onSend into the MIDI chain
            AppLogger.error("MidiInputReceiver", "MIDI parser failed: ${e.stackTraceToString()}")
        }
        if (parsedEvents == 0) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastEmptyTraceMs >= 1000) {
                lastEmptyTraceMs = now
                val traceStart = offset.coerceIn(0, data.size)
                val requestedEnd = offset.toLong() + length.toLong()
                val end = requestedEnd.coerceIn(traceStart.toLong(), data.size.toLong()).toInt()
                val traceEnd = minOf(end.toLong(), traceStart.toLong() + 16L).toInt()
                val hex = data.copyOfRange(traceStart, traceEnd)
                    .joinToString(" ") { "%02X".format(it.toInt() and 255) }
                AppLogger.warn(
                    "MidiInputReceiver",
                    "MIDI buffer produced no supported event bytes=[$hex] length=$length"
                )
            }
        }
    }
}
