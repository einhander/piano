package com.piano.sequencer

import org.junit.Assert.*
import org.junit.Test

class InstrumentReleasePreferencesTest {
    @Test fun identityKeysAndBoundsAreStable() {
        assertNotEquals(InstrumentReleasePreferences.key("main", 0), InstrumentReleasePreferences.key("file-a", 0))
        assertTrue(InstrumentReleasePreferences.MIN_TIMECENTS < 0f)
        assertEquals(0f, InstrumentReleasePreferences.DEFAULT_TIMECENTS)
        assertTrue(InstrumentReleasePreferences.MAX_TIMECENTS > 0f)
    }
}
