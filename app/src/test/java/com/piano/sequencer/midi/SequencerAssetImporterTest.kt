package com.piano.sequencer.midi

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files

class SequencerAssetImporterTest {
    private fun midi() = byteArrayOf(0x4d,0x54,0x68,0x64,0,0,0,6,0,0,0,1,0,96,0x4d,0x54,0x72,0x6b,0,0,0,4,0,0xff.toByte(),0x2f,0)

    @Test fun importsMidiAndSanitizesName() {
        val dir = Files.createTempDirectory("assets").toFile()
        val result = SequencerAssetImporter.importStream("../../song.mid", "audio/midi", ByteArrayInputStream(midi()), dir) { _, _ -> fail("not MP3") }
        assertEquals(ASSET_MIDI, result.assetType)
        assertTrue(result.file.name.endsWith(".mid"))
        assertFalse(result.file.name.contains('/'))
    }

    @Test fun invalidMp3DeletesPartialOutput() {
        val dir = Files.createTempDirectory("assets").toFile()
        val bytes = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 1)
        try {
            SequencerAssetImporter.importStream("x.mp3", "audio/mpeg", ByteArrayInputStream(bytes), dir) { _, _ -> error("invalid MP3") }
            fail("expected failure")
        } catch (_: IllegalStateException) { }
        assertEquals(0, dir.listFiles()?.size)
    }

    @Test fun rejectsUnsupportedAndMismatchedContent() {
        val dir = Files.createTempDirectory("assets").toFile()
        try { SequencerAssetImporter.importStream("x.aac", "audio/aac", ByteArrayInputStream(byteArrayOf(1)), dir) { _, _ -> } ; fail() }
        catch (_: IllegalArgumentException) { }
        try { SequencerAssetImporter.importStream("x.mp3", "audio/mpeg", ByteArrayInputStream(midi()), dir) { _, _ -> } ; fail() }
        catch (_: IllegalArgumentException) { }
        assertEquals(0, dir.listFiles()?.size)
    }

    @Test fun applyingImportedAssetPreservesTriggerAndLoopAndClearsMp3Routing() {
        val cell = SequencerCell(7, note = 60, filePath = "old.mid", loop = true, tempo = 99.0,
            selectedTracks = listOf(2), trackChannels = mapOf(2 to 3), instrumentSetId = "set")
        val mp3 = Files.createTempFile("track", ".mp3").toFile()
        val replaced = applyImportedAsset(cell, PreparedSequencerAsset(mp3, ASSET_MP3))
        assertEquals(60, replaced.note)
        assertTrue(replaced.loop)
        assertEquals(99.0, replaced.tempo, 0.0)
        assertNull(replaced.selectedTracks)
        assertTrue(replaced.trackChannels.isEmpty())
        assertNull(replaced.instrumentSetId)
        assertEquals(ASSET_MP3, replaced.assetType)
    }
}
