package com.piano.sequencer.midi

import org.junit.Assert.assertEquals
import org.junit.Test

class MidiFileRouteTest {
    @Test fun preserveMainSourceChannel() { assertEquals(-1, resolveTrackRoute(null, false, null)) }
    @Test fun fileSourceEncodesBaseOffset() { assertEquals(272, resolveTrackRoute(null, true, 16)) }
    @Test fun explicitChannelsBecomeAbsolute() {
        assertEquals(2, resolveTrackRoute(2, false, null))
        assertEquals(18, resolveTrackRoute(2, true, 16))
    }
    @Test fun missingFileSetFailsClosed() { assertEquals(-2, resolveTrackRoute(null, true, null)) }
}
