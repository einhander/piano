package com.piano.sequencer.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticReopenDecisionTest {
    @Test fun rejectsUninitializedEngine() {
        assertFalse(diagnosticReopenDecision(false, 48000, 48000, 2048, 512).allowed)
    }

    @Test fun rejectsChangedRate() {
        assertFalse(diagnosticReopenDecision(true, 48000, 44100, 2048, 512).allowed)
    }

    @Test fun acceptsSameRateCapacityFit() {
        assertTrue(diagnosticReopenDecision(true, 48000, 48000, 2048, 2048).allowed)
    }

    @Test fun rejectsCapacityOverflow() {
        assertFalse(diagnosticReopenDecision(true, 48000, 48000, 2048, 2049).allowed)
    }

    @Test fun rejectsInvalidCapacity() {
        assertFalse(diagnosticReopenDecision(true, 48000, 48000, 0, 512).allowed)
    }
}
