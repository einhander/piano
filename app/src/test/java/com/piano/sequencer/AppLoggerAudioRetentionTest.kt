package com.piano.sequencer

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors

class AppLoggerAudioRetentionTest {
    @Test fun audioDiagnosticsEvictMidiChatterFirst() {
        AppLogger.clear()
        AppLogger.warn("PlaybackService", "Audio stream state=12 error=-895")
        repeat(500) { AppLogger.info("MIDI", "NOTE ON n=$it") }

        val entries = AppLogger.getAll()
        assertTrue(entries.any { it.contains("Audio stream state=12") })
        assertTrue(entries.size <= 200)
        assertTrue(entries.count { it.contains(" MIDI:") } < 200)
        AppLogger.clear()
    }

    @Test fun concurrentLogAndClearKeepSnapshotsBounded() {
        AppLogger.clear()
        val pool = Executors.newFixedThreadPool(5)
        try {
            val writers = (0 until 4).map { writer ->
                pool.submit { repeat(300) { AppLogger.info("MIDI", "writer=$writer note=$it") } }
            }
            val clearer = pool.submit { repeat(100) { AppLogger.clear(); AppLogger.getAll() } }
            writers.forEach { it.get() }
            clearer.get()

            AppLogger.warn("PlaybackService", "Audio stream state=12 error=-895")
            repeat(500) { AppLogger.info("MIDI", "later note=$it") }
            val snapshot = AppLogger.getAll()
            assertTrue(snapshot.size <= 200)
            assertTrue(snapshot.any { it.contains("Audio stream state=12") })
        } finally {
            pool.shutdownNow()
            AppLogger.clear()
        }
    }
}
