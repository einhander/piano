package com.piano.sequencer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelVolumePreferencesTest {
    @Test fun channelRangeAndDefaults() {
        assertEquals(100, ChannelVolumePreferences.DEFAULT)
        assertEquals("chan_volume_0", ChannelVolumePreferences.key(0))
        assertEquals("chan_volume_15", ChannelVolumePreferences.key(15))
    }

    @Test fun valuesClampToCc7Range() {
        assertEquals(0, (-2).coerceIn(ChannelVolumePreferences.MIN, ChannelVolumePreferences.MAX))
        assertEquals(127, 200.coerceIn(ChannelVolumePreferences.MIN, ChannelVolumePreferences.MAX))
        assertArrayEquals(IntArray(16) { 100 }, IntArray(16) { ChannelVolumePreferences.DEFAULT })
    }
}
