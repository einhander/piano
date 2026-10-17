package com.piano.sequencer

import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticNoteInputTest {
    @Test fun touchSendsOneOnAndOneOff() {
        val events = mutableListOf<String>()
        val input = DiagnosticNoteInput({ events += "on:$it" }, { events += "off:$it" })
        input.touchDown(60)
        input.touchUp(60)
        input.click(60)
        assertEquals(listOf("on:60", "off:60"), events)
    }

    @Test fun cancelAndPauseReleaseOnlyHeldNotesOnce() {
        val events = mutableListOf<String>()
        val input = DiagnosticNoteInput({ events += "on:$it" }, { events += "off:$it" })
        input.touchCancel(62)
        input.releaseHeld()
        input.touchDown(60)
        input.releaseHeld()
        input.releaseHeld()
        assertEquals(listOf("on:60", "off:60"), events)
    }

    @Test fun accessibilityClickIsBriefPairWithoutTouchRelease() {
        val events = mutableListOf<String>()
        val input = DiagnosticNoteInput({ events += "on:$it" }, { events += "off:$it" })
        input.click(64)
        input.releaseHeld()
        assertEquals(listOf("on:64", "off:64"), events)
    }
}
