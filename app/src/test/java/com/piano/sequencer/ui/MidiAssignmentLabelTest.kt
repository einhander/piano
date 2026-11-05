package com.piano.sequencer.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MidiAssignmentLabelTest {
    @Test fun showsDevicePortAndOneBasedChannelWithoutSerial() {
        assertEquals("MIDI assigned · SMK-37 Pro · Port 2 · Ch 8",
            midiAssignmentLabel("|SMK-37 Pro|BC20E91BFFFDFCED|port=2", 7))
    }

    @Test fun wildcardMappingUsesReadableFallbacks() {
        assertEquals("MIDI assigned · Any device · Port Any · Ch Any",
            midiAssignmentLabel(null, null))
    }
}
