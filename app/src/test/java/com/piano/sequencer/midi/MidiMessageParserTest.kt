package com.piano.sequencer.midi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for [MidiMessageParser] — the MIDI byte-stream parser that
 * [MidiInputReceiver] delegates to. These run on the JVM (no Robolectric) because
 * the parser is Android-free.
 */
class MidiMessageParserTest {

    /** Records every event in arrival order as a string. */
    private class RecordingHandler : MidiMessageParser.Handler {
        val events = mutableListOf<String>()
        override fun onNoteOn(channel: Int, note: Int, velocity: Int) {
            events.add("noteOn:$channel:$note:$velocity")
        }
        override fun onNoteOff(channel: Int, note: Int, velocity: Int) {
            events.add("noteOff:$channel:$note:$velocity")
        }
        override fun onControlChange(channel: Int, controller: Int, value: Int) {
            events.add("cc:$channel:$controller:$value")
        }
        override fun onProgramChange(channel: Int, program: Int) {
            events.add("pc:$channel:$program")
        }
        override fun onPitchBend(channel: Int, value: Int) {
            events.add("pitch:$channel:$value")
        }
        override fun onChannelPressure(channel: Int, value: Int) {
            events.add("chPress:$channel:$value")
        }
    }

    /** Builds a byte array from the given 0..255 values and parses it. */
    private fun parse(vararg bytes: Int): List<String> {
        val data = ByteArray(bytes.size) { bytes[it].toByte() }
        val handler = RecordingHandler()
        MidiMessageParser.parse(data, 0, data.size, handler)
        return handler.events
    }

    private fun streamParse(vararg chunks: IntArray): List<String> {
        val handler = RecordingHandler()
        val parser = MidiMessageParser.StreamParser()
        chunks.forEach { chunk ->
            val data = ByteArray(chunk.size) { chunk[it].toByte() }
            parser.parse(data, 0, data.size, handler)
        }
        return handler.events
    }

    @Test
    fun noteOn() {
        assertEquals(listOf("noteOn:0:60:100"), parse(0x90, 60, 100))
    }

    @Test
    fun noteOnWithZeroVelocityIsNoteOff() {
        assertEquals(listOf("noteOff:0:60:0"), parse(0x90, 60, 0))
    }

    @Test
    fun noteOff() {
        assertEquals(listOf("noteOff:3:64:0"), parse(0x83, 64, 0))
    }

    @Test
    fun controlChange() {
        assertEquals(listOf("cc:0:7:127"), parse(0xB0, 7, 127))
    }

    @Test
    fun programChange() {
        assertEquals(listOf("pc:0:42"), parse(0xC0, 42))
    }

    @Test
    fun programChangesAndFollowingMessagesAdvanceCorrectly() {
        assertEquals(listOf("pc:2:10", "noteOn:2:60:100", "pc:2:11", "pc:2:12"),
            parse(0xC2, 10, 0x92, 60, 100, 0xC2, 11, 0xC2, 12))
    }

    @Test
    fun controlChangeFollowedByProgramChange() {
        assertEquals(listOf("cc:1:7:64", "pc:1:9"), parse(0xB1, 7, 64, 0xC1, 9))
    }

    @Test
    fun channelPressureFollowedByNote() {
        assertEquals(listOf("chPress:4:77", "noteOn:4:60:90"),
            parse(0xD4, 77, 0x94, 60, 90))
    }

    @Test
    fun pitchBendLowHigh() {
        // value = (hi shl 7) or lo
        assertEquals(listOf("pitch:0:16256"), parse(0xE0, 0x00, 0x7F)) // (0x7F shl 7) or 0
        assertEquals(listOf("pitch:0:16383"), parse(0xE0, 0x7F, 0x7F)) // max
        assertEquals(listOf("pitch:0:8192"), parse(0xE0, 0x00, 0x40))  // center
        assertEquals(listOf("pitch:0:0"), parse(0xE0, 0x00, 0x00))     // min
    }

    @Test
    fun channelPressure() {
        assertEquals(listOf("chPress:0:100"), parse(0xD0, 100))
    }

    @Test
    fun polyphonicAftertouchMapsToChannelPressure() {
        // 0xA0 note pressure: note=60 (ignored), pressure=90 -> onChannelPressure(90)
        assertEquals(listOf("chPress:0:90"), parse(0xA0, 60, 90))
    }

    @Test
    fun sysexProducesNoEvents() {
        assertTrue(parse(0xF0, 0x41, 0x00, 0x78, 0x00, 0xF7).isEmpty())
    }

    @Test
    fun sysexWithRealTimeBytesInsideProducesNoEvents() {
        assertTrue(parse(0xF0, 0x41, 0xF8, 0x00, 0xF7).isEmpty())
    }

    @Test
    fun realTimeBytesProduceNoEvents() {
        assertTrue(parse(0xF8).isEmpty())
        assertTrue(parse(0xFE).isEmpty())
    }

    @Test
    fun realtimeBeforeAndBetweenMessagesIsIgnored() {
        assertEquals(listOf("noteOn:0:60:100"), parse(0xF8, 0xFE, 0x90, 60, 100))
        assertEquals(listOf("noteOn:0:60:100", "noteOff:0:60:0"),
            parse(0x90, 60, 100, 0xF8, 0x80, 60, 0))
    }

    @Test
    fun sysexAndSystemCommonMessagesDoNotSwallowFollowingNote() {
        assertEquals(listOf("noteOn:0:60:100"), parse(0xF0, 1, 2, 0xF8, 3, 0xF7, 0x90, 60, 100))
        assertEquals(listOf("noteOn:0:60:100"), parse(0xF1, 1, 0x90, 60, 100))
        assertEquals(listOf("noteOn:0:60:100"), parse(0xF2, 1, 2, 0x90, 60, 100))
        assertEquals(listOf("noteOn:0:60:100"), parse(0xF3, 1, 0x90, 60, 100))
        assertEquals(listOf("noteOn:0:60:100"), parse(0xF6, 0x90, 60, 100))
    }

    @Test
    fun runningStatusSupportsNoteAndControlChangeAcrossBuffers() {
        assertEquals(listOf("noteOn:0:60:100", "noteOn:0:61:90"),
            streamParse(intArrayOf(0x90, 60, 100), intArrayOf(61, 90)))
        assertEquals(listOf("cc:0:7:64", "cc:0:10:32"),
            streamParse(intArrayOf(0xB0, 7, 64), intArrayOf(0xF8, 10, 32)))
    }

    @Test
    fun runningStatusCanSpanTruncatedMessageAndRealtime() {
        assertEquals(listOf("noteOn:0:60:100"),
            streamParse(intArrayOf(0x90, 60), intArrayOf(0xFE, 100)))
    }

    @Test
    fun truncatedSysexWithoutTerminatorNoCrashNoEvents() {
        // SysEx with no 0xF7 terminator — the skip loop must terminate at the
        // buffer end without crashing or emitting events.
        assertTrue(parse(0xF0, 0x41, 0x00).isEmpty())
    }

    @Test
    fun standaloneEndOfSysexNoEvents() {
        assertTrue(parse(0xF7).isEmpty())
    }

    @Test
    fun multipleMessagesParsedInOrder() {
        val events = parse(0x90, 60, 100, 0xB0, 7, 64, 0x80, 60, 0)
        assertEquals(listOf("noteOn:0:60:100", "cc:0:7:64", "noteOff:0:60:0"), events)
    }

    @Test
    fun truncatedNoteOnNoCrashNoEvent() {
        assertTrue(parse(0x90, 60).isEmpty())
    }

    @Test
    fun truncatedNoteOffNoCrashNoEvent() {
        assertTrue(parse(0x80, 60).isEmpty())
    }

    @Test
    fun emptyBufferNoEvents() {
        assertTrue(parse().isEmpty())
    }

    @Test
    fun channelExtractedFromStatusByte() {
        assertEquals(listOf("noteOn:5:60:100"), parse(0x95, 60, 100))
    }

    @Test
    fun nonZeroOffsetIsRespected() {
        // MidiReceiver.onSend can be called with a non-zero offset. Two leading
        // bytes must be skipped; only the note-on at offset 2 is parsed.
        val data = byteArrayOf(0x00, 0x00, 0x90.toByte(), 60, 100)
        val handler = RecordingHandler()
        MidiMessageParser.parse(data, 2, 3, handler)
        assertEquals(listOf("noteOn:0:60:100"), handler.events)
    }

    @Test
    fun unknownStatusByteProducesNoEvent() {
        // 0x70 (channel mode) is not handled -> no event
        assertTrue(parse(0x70, 1, 2).isEmpty())
    }
}
