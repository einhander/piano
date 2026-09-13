package com.piano.sequencer.midi

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Unit tests for [MultiChannelResolver]. */
class MultiChannelResolverTest {
    @Test fun disabledReturnsSameInstance() {
        val base = intArrayOf(3, 1)
        assertSame(base, MultiChannelResolver.resolve(base, 0xFFFF, false))
        assertArrayEquals(intArrayOf(3, 1), base)
    }
    @Test fun enabledEmptyMaskReturnsSameInstance() {
        val base = intArrayOf(3)
        assertSame(base, MultiChannelResolver.resolve(base, 0, true))
    }
    @Test fun maskAddsChannelsAscending() {
        assertArrayEquals(intArrayOf(1, 3, 5), MultiChannelResolver.resolve(intArrayOf(3), 0b100010, true))
    }
    @Test fun maskOverlapsBaseDeduplicates() {
        assertArrayEquals(intArrayOf(1, 3), MultiChannelResolver.resolve(intArrayOf(1, 3), 0b1010, true))
    }

    @Test fun maskSubsetOfBaseReturnsSameInstance() {
        val base = intArrayOf(1, 3)
        assertSame(base, MultiChannelResolver.resolve(base, 0b1010, true))
    }
    @Test fun allChannels() {
        assertArrayEquals((0..15).toList().toIntArray(), MultiChannelResolver.resolve(intArrayOf(7), 0xFFFF, true))
    }
    @Test fun emptyBaseUsesMask() {
        assertArrayEquals(intArrayOf(0, 4, 15), MultiChannelResolver.resolve(intArrayOf(), (1 shl 0) or (1 shl 4) or (1 shl 15), true))
    }
    @Test fun ignoresBitsAboveChannelFifteen() {
        assertArrayEquals(intArrayOf(2), MultiChannelResolver.resolve(intArrayOf(), 1 shl 16 or (1 shl 2), true))
    }
}
