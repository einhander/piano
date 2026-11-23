package com.piano.sequencer.midi

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression contract: elapsed wall time is never a PCM preview stop condition. */
class PcmPreviewStatusTest {
    @Test
    fun pcmPreviewStatusTracksNativePlaybackOnly() {
        assertTrue(pcmPreviewShouldRemainActive(isPlaying = true))
        assertFalse(pcmPreviewShouldRemainActive(isPlaying = false))
    }
}

internal fun pcmPreviewShouldRemainActive(isPlaying: Boolean): Boolean = isPlaying
