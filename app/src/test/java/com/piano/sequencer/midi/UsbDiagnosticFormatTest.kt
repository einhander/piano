package com.piano.sequencer.midi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbDiagnosticFormatTest {
    @Test fun boundedTraversalConsumesOnlyLimitPlusOneAndMarksTruncation() {
        var visited = 0
        val result = UsbDiagnosticFormat.boundedList(sequence { repeat(100) { visited++; yield(it) } }, 3, "devices-truncated") { it.toString() }
        assertEquals(4, visited)
        assertEquals(listOf("0", "1", "2+devices-truncated"), result.lines)
        assertTrue(result.truncated)
    }

    @Test fun sanitizeCapsAndEscapesUnstructuredText() {
        assertEquals("serial_numberabc", UsbDiagnosticFormat.sanitize("serial_number=abc\n", 20))
        assertEquals(4, UsbDiagnosticFormat.sanitize("abcdef", 4).length)
    }

    @Test fun outputLineHasHardCharacterBudgetAndNotice() {
        val line = UsbDiagnosticFormat.boundedLine("x".repeat(10_000))
        assertEquals(UsbDiagnosticFormat.MAX_LINE_CHARS, line.length)
        assertTrue(line.endsWith("...[line-truncated]"))
        assertFalse(UsbDiagnosticFormat.boundedLine("ok").contains("truncated"))
    }

    @Test fun requiredHeaderAndAudioRecordSurviveDescriptorOverflow() {
        val lines = UsbDiagnosticFormat.boundedRecords(
            (1..100).asSequence().map { "usb:$it".repeat(100) },
            listOf("snapshot-id=9 phase=connect", "build=Build.DISPLAY and focus=unknown", "outputAdvertised=48000:not-active-route"),
            5
        )
        assertEquals(5, lines.size)
        assertTrue(lines[0].contains("snapshot-id=9"))
        assertTrue(lines[1].contains("Build.DISPLAY"))
        assertTrue(lines[2].contains("outputAdvertised"))
        assertTrue(lines.last().contains("records-truncated"))
    }
}
