package com.piano.sequencer.midi

import org.junit.Assert.assertEquals
import org.junit.Test

class MidiInitialProgramsTest {
    private fun midi(vararg track: Int): ByteArray = ("MThd".toByteArray() + byteArrayOf(0,0,0,6,0,0,0,1,1,0xE0.toByte()) + "MTrk".toByteArray() + byteArrayOf(0,0,0,track.size.toByte()) + track.map { it.toByte() }.toByteArray())
    @Test fun bankProgramCapturedAtFirstNoteAndDrumsDefault() {
        val p=MidiInitialPrograms.parse(midi(0,0xB0,0,2,0,0xB0,32,5,0,0xC0,7,0,0x90,60,100))
        assertEquals(((2 * 128 + 5) shl 8) or 7,p[0]); assertEquals(128 shl 8,p[9])
    }
    @Test fun runningStatusAndMalformedInputSafe() {
        assertEquals(11,MidiInitialPrograms.parse(midi(0,0xC0,11,0,0x90,60,100))[0])
        assertEquals(MidiInitialPrograms.defaults(),MidiInitialPrograms.parse(byteArrayOf(1,2,3)))
    }

    @Test fun firstDrumNoteUsesDefaultDrumBank() {
        val bytes = midi(0, 0x99, 36, 100)
        assertEquals(128 shl 8, MidiInitialPrograms.parse(bytes)[9])
    }

    @Test fun typeOnePresetTrackPrecedesNotesTrackByAbsoluteTick() {
        val header = "MThd".toByteArray() + byteArrayOf(0,0,0,6,0,1,0,2,1,0xE0.toByte())
        fun track(vararg bytes: Int) = "MTrk".toByteArray() + byteArrayOf(0,0,0,bytes.size.toByte()) + bytes.map { it.toByte() }.toByteArray()
        val preset = track(0,0xB0,0,3,0,0xB0,32,4,0,0xC0,22,0,0xFF,0x2F,0)
        val notes = track(10,0x90,60,100,0,0xFF,0x2F,0)
        val result = MidiInitialPrograms.parse(header + preset + notes)
        assertEquals(((3 * 128 + 4) shl 8) or 22, result[0])
    }
}
