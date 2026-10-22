package com.piano.sequencer.midi

import org.junit.Assert.assertEquals
import org.junit.Test

class MidiFileRouteTest {
    @Test fun fileDefaultsDoNotDependOnMainPrograms() {
        // Initial FILE presets use deterministic GM defaults, independent of MAIN prefs.
        assertEquals(MidiInitialPrograms.defaults(), FileInstrumentSetStore.initialPrograms())
        assertEquals(128 shl 8, FileInstrumentSetStore.initialPrograms()[9])
    }
    @Test fun preserveMainSourceChannel() { assertEquals(-1, resolveTrackRoute(null, false, null)) }
    @Test fun fileSourceEncodesBaseOffset() { assertEquals(272, resolveTrackRoute(null, true, 16)) }
    @Test fun explicitChannelsBecomeAbsolute() {
        assertEquals(2, resolveTrackRoute(2, false, null))
        assertEquals(18, resolveTrackRoute(2, true, 16))
    }
    @Test fun missingFileSetFailsClosed() { assertEquals(-2, resolveTrackRoute(null, true, null)) }
    @Test fun legacyNullSetMustNotResolveAsMainByDefault() {
        val legacy = SequencerCell(id = 91, filePath = "/legacy.mid")
        assertEquals(-1, resolveCellTrackChannel(0, legacy, 0, emptyList()))
    }
}
