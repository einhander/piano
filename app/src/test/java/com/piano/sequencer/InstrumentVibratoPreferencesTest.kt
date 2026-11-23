package com.piano.sequencer

import org.junit.Assert.*
import org.junit.Test

class InstrumentVibratoPreferencesTest {
    @Test fun identityKeysAndBoundsAreStable() {
        assertNotEquals(InstrumentVibratoPreferences.key("main", 0, "rate"), InstrumentVibratoPreferences.key("file-a", 0, "rate"))
        assertNotEquals(InstrumentVibratoPreferences.key("main", 0, "rate"), InstrumentVibratoPreferences.key("main", 1, "rate"))
        assertNotEquals(InstrumentVibratoPreferences.key("main", 0, "rate"), InstrumentVibratoPreferences.key("main", 0, "depth"))
        assertEquals("instrument_vibrato_main_0_rate", InstrumentVibratoPreferences.key("main", 0, "rate"))
        assertNotEquals(InstrumentVibratoPreferences.baseKey("main", 0), InstrumentVibratoPreferences.baseKey("file-a", 0))
    }

    @Test fun documentedDefaultsAndNativeBounds() {
        assertFalse(InstrumentVibratoPreferences.DEFAULT_ENABLED)
        assertEquals(5f, InstrumentVibratoPreferences.DEFAULT_RATE_HZ)
        assertEquals(25f, InstrumentVibratoPreferences.DEFAULT_DEPTH_CENTS)
        assertEquals(0f, InstrumentVibratoPreferences.DEFAULT_DELAY_MS)
        assertTrue(InstrumentVibratoPreferences.MIN_RATE_HZ > 0f)
        assertTrue(InstrumentVibratoPreferences.MAX_RATE_HZ > InstrumentVibratoPreferences.MIN_RATE_HZ)
        assertEquals(100f, InstrumentVibratoPreferences.MAX_DEPTH_CENTS)
        assertEquals(2000f, InstrumentVibratoPreferences.MAX_DELAY_MS)
        val valid = InstrumentVibratoPreferences.Vibrato(true, 5f, 25f, 0f)
        assertTrue(valid.isValid)
        assertFalse(InstrumentVibratoPreferences.Vibrato(true, 0.4f, 25f, 0f).isValid)
        assertFalse(InstrumentVibratoPreferences.Vibrato(true, 13f, 25f, 0f).isValid)
        assertFalse(InstrumentVibratoPreferences.Vibrato(true, 5f, 101f, 0f).isValid)
        assertFalse(InstrumentVibratoPreferences.Vibrato(true, 5f, 25f, 2001f).isValid)
        assertFalse(InstrumentVibratoPreferences.Vibrato(true, Float.NaN, 25f, 0f).isValid)
    }
}
