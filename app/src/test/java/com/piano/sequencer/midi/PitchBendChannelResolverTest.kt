package com.piano.sequencer.midi

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [PitchBendChannelResolver] — decides which channels pitch
 * bend / mod / breath are sent to. Runs on the JVM (no Robolectric) because
 * the resolver is Android-free.
 */
class PitchBendChannelResolverTest {

    @Test
    fun lastNoteChannelWinsOverMask() {
        assertEquals(listOf(5), PitchBendChannelResolver.resolve(5, 0).toList())
        assertEquals(listOf(5), PitchBendChannelResolver.resolve(5, 0xFFFF).toList())
    }

    @Test
    fun lastNoteChannelZero() {
        assertEquals(listOf(0), PitchBendChannelResolver.resolve(0, 0xFFFF).toList())
    }

    @Test
    fun lastNoteChannelFifteen() {
        assertEquals(listOf(15), PitchBendChannelResolver.resolve(15, 0).toList())
    }

    @Test
    fun noNoteSingleChannelMask() {
        assertEquals(listOf(0), PitchBendChannelResolver.resolve(-1, 1).toList())
    }

    @Test
    fun noNoteMultiChannelMaskAscending() {
        assertEquals(listOf(0, 2), PitchBendChannelResolver.resolve(-1, 0b101).toList())
    }

    @Test
    fun noNoteEmptyMaskFallsBackToChannelOne() {
        assertEquals(listOf(0), PitchBendChannelResolver.resolve(-1, 0).toList())
    }

    @Test
    fun noNoteAllChannelsMask() {
        assertEquals((0..15).toList(), PitchBendChannelResolver.resolve(-1, 0xFFFF).toList())
    }

    @Test
    fun outOfRangeLastNoteChannelFallsBackToMask() {
        assertEquals(listOf(0), PitchBendChannelResolver.resolve(16, 1).toList())
        assertEquals(listOf(0, 2), PitchBendChannelResolver.resolve(-2, 0b101).toList())
    }
}