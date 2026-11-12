package com.piano.sequencer.midi

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticPortModeTest {
    @Test fun defaultAndAllModesPreserveEveryPort() {
        assertTrue(DiagnosticPortMode.ALL.selects(0))
        assertTrue(DiagnosticPortMode.ALL.selects(1))
        assertTrue(DiagnosticPortMode.ALL.selects(2))
    }

    @Test fun noneAndExplicitZeroTwoModesAreExact() {
        assertFalse(DiagnosticPortMode.NONE.selects(0))
        assertFalse(DiagnosticPortMode.NONE.selects(2))
        assertTrue(DiagnosticPortMode.PORTS_0_2.selects(0))
        assertFalse(DiagnosticPortMode.PORTS_0_2.selects(1))
        assertTrue(DiagnosticPortMode.PORTS_0_2.selects(2))
    }
}
