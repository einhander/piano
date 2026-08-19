package com.piano.sequencer.project

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [PseqArchive] — the .pseq ZIP archive core.
 *
 * Runs on the JVM (no Robolectric): only java.io / java.util.zip are used.
 * The SoundFont is not stored in the archive — PseqDocument.soundFont is a
 * filename setting only.
 */
class PseqArchiveTest {

    private val tempFiles = mutableListOf<File>()

    private fun tempFile(prefix: String, suffix: String): File {
        val file = File.createTempFile(prefix, suffix)
        tempFiles.add(file)
        return file
    }

    @After
    fun cleanup() {
        tempFiles.forEach { it.delete() }
    }

    private fun baseDoc(): PseqDocument = PseqDocument(
        formatVersion = PseqArchive.FORMAT_VERSION,
        name = "Test",
        createdAt = "2026-08-19T12:00:00Z"
    )

    /** Builds a zip archive containing a hand-written project.json. */
    private fun writeJsonZip(pseq: File, jsonText: String) {
        ZipOutputStream(pseq.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("project.json"))
            zip.write(jsonText.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }

    // ── write / read round-trip ──

    @Test
    fun roundTrip() {
        val channels = List(16) { 0 }.toMutableList().also { it[3] = 0x0100 or 42 } // bank 1, program 42
        val doc = baseDoc().copy(
            name = "Round Trip",
            bpm = 123.5,
            ppq = 96,
            numerator = 4,
            denominator = 4,
            masterGain = 0.8f,
            polyphony = 64,
            soundFont = "test.sf2",
            channels = channels,
            cells = listOf(
                PseqCell(id = 1, note = 60, filePath = "midi/foo.mid", loop = true, tempo = 100.0, channel = 0),
                PseqCell(id = 2, note = 48, filePath = "midi/bar.mid", loop = false, tempo = 140.0, channel = 5)
            )
        )

        val foo = tempFile("pseq_foo_", ".mid")
        val fooBytes = byteArrayOf(1, 2, 3)
        foo.writeBytes(fooBytes)
        val bar = tempFile("pseq_bar_", ".mid")
        bar.writeBytes(byteArrayOf(4, 5, 6))

        val pseq = tempFile("pseq_roundtrip_", ".pseq")
        PseqArchive.write(pseq.outputStream(), doc, mapOf("midi/foo.mid" to foo, "midi/bar.mid" to bar))

        val back = PseqArchive.readDocument(pseq.inputStream())
        assertEquals(doc, back)
        assertEquals("test.sf2", back.soundFont)

        // No soundfonts/ entry — the SF2 is a filename setting only.
        val entries = PseqArchive.listEntries(pseq.inputStream())
        assertEquals(setOf("project.json", "midi/foo.mid", "midi/bar.mid"), entries)

        val extracted = tempFile("pseq_extracted_", ".mid")
        PseqArchive.extractEntry(pseq.inputStream(), "midi/foo.mid", extracted)
        assertArrayEquals(fooBytes, extracted.readBytes())
    }

    @Test
    fun noSoundFont() {
        val doc = baseDoc().copy(name = "No SF2")
        val pseq = tempFile("pseq_nosf2_", ".pseq")
        PseqArchive.write(pseq.outputStream(), doc, emptyMap())

        val back = PseqArchive.readDocument(pseq.inputStream())
        assertNull(back.soundFont)

        val entries = PseqArchive.listEntries(pseq.inputStream())
        assertEquals(setOf("project.json"), entries)
        assertTrue(entries.none { it.startsWith("soundfonts/") })
    }

    @Test
    fun missingMidiFileSkipped() {
        val doc = baseDoc().copy(
            cells = listOf(PseqCell(id = 1, note = 60, filePath = "midi/gone.mid"))
        )
        val missing = tempFile("pseq_gone_", ".mid")
        missing.delete()

        val pseq = tempFile("pseq_missing_", ".pseq")
        PseqArchive.write(pseq.outputStream(), doc, mapOf("midi/gone.mid" to missing))

        val entries = PseqArchive.listEntries(pseq.inputStream())
        assertEquals(setOf("project.json"), entries)

        val back = PseqArchive.readDocument(pseq.inputStream())
        assertEquals("midi/gone.mid", back.cells[0].filePath)
    }

    // ── B4: trigger types (CC / pitch bend) in .pseq ──

    @Test
    fun roundTripPreservesCcTrigger() {
        val doc = baseDoc().copy(
            cells = listOf(
                PseqCell(id = 1, note = -1, filePath = "midi/cc.mid", loop = true, tempo = 100.0, channel = 3, triggerType = "CC", ccNumber = 7),
                PseqCell(id = 2, note = 60, filePath = "midi/note.mid")
            )
        )
        val cc = tempFile("pseq_cc_", ".mid")
        cc.writeBytes(byteArrayOf(1, 2, 3))
        val note = tempFile("pseq_note_", ".mid")
        note.writeBytes(byteArrayOf(4, 5, 6))

        val pseq = tempFile("pseq_ccrt_", ".pseq")
        PseqArchive.write(pseq.outputStream(), doc, mapOf("midi/cc.mid" to cc, "midi/note.mid" to note))

        val back = PseqArchive.readDocument(pseq.inputStream())
        assertEquals(doc, back)
        assertEquals("CC", back.cells[0].triggerType)
        assertEquals(7, back.cells[0].ccNumber)
        assertEquals(-1, back.cells[0].note)
        // NOTE cell: defaults preserved
        assertEquals("NOTE", back.cells[1].triggerType)
        assertNull(back.cells[1].ccNumber)
    }

    @Test
    fun roundTripPreservesPitchBendTrigger() {
        val doc = baseDoc().copy(
            cells = listOf(
                PseqCell(id = 1, note = -1, filePath = "midi/pb.mid", loop = false, tempo = 140.0, channel = -1, triggerType = "PITCH_BEND")
            )
        )
        val pb = tempFile("pseq_pb_", ".mid")
        pb.writeBytes(byteArrayOf(7, 8, 9))

        val pseq = tempFile("pseq_pb_", ".pseq")
        PseqArchive.write(pseq.outputStream(), doc, mapOf("midi/pb.mid" to pb))

        val back = PseqArchive.readDocument(pseq.inputStream())
        assertEquals(doc, back)
        assertEquals("PITCH_BEND", back.cells[0].triggerType)
        assertNull(back.cells[0].ccNumber)
        assertEquals(-1, back.cells[0].note)
    }

    @Test
    fun oldPseqCellWithoutTriggerFieldsDeserializesAsNote() {
        // project.json written by an older app version: cells lack triggerType/ccNumber
        val pseq = tempFile("pseq_oldcell_", ".pseq")
        val jsonText = """{"formatVersion":1,"name":"Old","createdAt":"2026-08-19T12:00:00Z","cells":[{"id":1,"note":48,"filePath":"midi/t.mid","loop":true,"tempo":90.0,"channel":5}]}"""
        writeJsonZip(pseq, jsonText)

        val doc = PseqArchive.readDocument(pseq.inputStream())
        assertEquals(48, doc.cells[0].note)
        assertEquals("NOTE", doc.cells[0].triggerType)
        assertNull(doc.cells[0].ccNumber)
    }

    // ── malformed archives ──

    @Test
    fun missingProjectJson() {
        val pseq = tempFile("pseq_noproj_", ".pseq")
        ZipOutputStream(pseq.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("midi/foo.mid"))
            zip.write(byteArrayOf(1, 2, 3))
            zip.closeEntry()
        }

        val e = assertThrows(PseqFormatException::class.java) {
            PseqArchive.readDocument(pseq.inputStream())
        }
        assertEquals("project.json not found", e.message)
    }

    @Test
    fun badVersion() {
        val pseq = tempFile("pseq_badver_", ".pseq")
        writeJsonZip(pseq, """{"formatVersion":99,"name":"Bad","createdAt":"2026-08-19T12:00:00Z"}""")

        val e = assertThrows(PseqFormatException::class.java) {
            PseqArchive.readDocument(pseq.inputStream())
        }
        assertTrue(e.message!!.contains("99"))
    }

    @Test
    fun corruptZip() {
        val pseq = tempFile("pseq_corrupt_", ".pseq")
        pseq.writeBytes(byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11))

        assertThrows(PseqFormatException::class.java) {
            PseqArchive.readDocument(pseq.inputStream())
        }
    }

    // ── document validation ──

    @Test
    fun readDocumentAbsoluteCellPath() {
        val pseq = tempFile("pseq_abs_", ".pseq")
        val jsonText = """{"formatVersion":1,"name":"X","createdAt":"2026-08-19T12:00:00Z","cells":[{"id":1,"filePath":"/abs/path"}]}"""
        writeJsonZip(pseq, jsonText)

        assertThrows(PseqFormatException::class.java) {
            PseqArchive.readDocument(pseq.inputStream())
        }
    }

    @Test
    fun readDocumentBadChannelCount() {
        val pseq = tempFile("pseq_chans_", ".pseq")
        val channels = (0 until 15).joinToString(",") { "0" }
        val jsonText = """{"formatVersion":1,"name":"X","createdAt":"2026-08-19T12:00:00Z","channels":[$channels]}"""
        writeJsonZip(pseq, jsonText)

        assertThrows(PseqFormatException::class.java) {
            PseqArchive.readDocument(pseq.inputStream())
        }
    }

    @Test
    fun readDocumentDuplicateCellIds() {
        val pseq = tempFile("pseq_dupid_", ".pseq")
        val jsonText = """{"formatVersion":1,"name":"X","createdAt":"2026-08-19T12:00:00Z","cells":[{"id":1,"filePath":"midi/a.mid"},{"id":1,"filePath":"midi/b.mid"}]}"""
        writeJsonZip(pseq, jsonText)

        assertThrows(PseqFormatException::class.java) {
            PseqArchive.readDocument(pseq.inputStream())
        }
    }

    // ── entry name validation (zip-slip) ──

    @Test
    fun isArchiveEntry() {
        assertTrue(PseqArchive.isArchiveEntry("project.json"))
        assertTrue(PseqArchive.isArchiveEntry("midi/foo.mid"))
        assertFalse(PseqArchive.isArchiveEntry("midi/"))
        assertFalse(PseqArchive.isArchiveEntry("midi//foo.mid"))
        assertFalse(PseqArchive.isArchiveEntry("midi/sub/foo.mid"))
        assertFalse(PseqArchive.isArchiveEntry("midi/../../evil.mid"))
        assertFalse(PseqArchive.isArchiveEntry("foo.mid"))
        assertFalse(PseqArchive.isArchiveEntry("soundfonts/x.sf2"))
        // "my..song.mid" is a legal name; only the exact segment ".." is traversal
        assertTrue(PseqArchive.isArchiveEntry("midi/my..song.mid"))
        assertFalse(PseqArchive.isArchiveEntry("midi/.."))
    }

    @Test
    fun extractEntryTraversalName() {
        val pseq = tempFile("pseq_traversal_", ".pseq")
        ZipOutputStream(pseq.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("midi/../../evil.mid"))
            zip.write(byteArrayOf(1, 2, 3))
            zip.closeEntry()
        }
        val dest = tempFile("pseq_evil_", ".mid")

        assertThrows(PseqFormatException::class.java) {
            PseqArchive.extractEntry(pseq.inputStream(), "midi/../../evil.mid", dest)
        }
    }

    @Test
    fun extractEntryMissingEntry() {
        val pseq = tempFile("pseq_noentry_", ".pseq")
        PseqArchive.write(pseq.outputStream(), baseDoc(), emptyMap())
        val dest = tempFile("pseq_dest_", ".mid")

        assertThrows(PseqFormatException::class.java) {
            PseqArchive.extractEntry(pseq.inputStream(), "midi/nope.mid", dest)
        }
    }

    // ── uniqueDestName ──

    @Test
    fun uniqueDestName() {
        val taken = mutableSetOf("foo.mid")

        // Free name unchanged
        assertEquals("bar.mid", PseqArchive.uniqueDestName("bar.mid") { it in taken })
        // First collision → _1
        assertEquals("foo_1.mid", PseqArchive.uniqueDestName("foo.mid") { it in taken })
        taken.add("foo_1.mid")
        // Further collisions → _2, _3
        assertEquals("foo_2.mid", PseqArchive.uniqueDestName("foo.mid") { it in taken })
        taken.add("foo_2.mid")
        assertEquals("foo_3.mid", PseqArchive.uniqueDestName("foo.mid") { it in taken })
    }

    // ── JSON tolerance ──

    @Test
    fun unknownKeysTolerated() {
        val pseq = tempFile("pseq_unknown_", ".pseq")
        writeJsonZip(pseq, """{"formatVersion":1,"name":"X","createdAt":"2026-08-19T12:00:00Z","futureField":42}""")

        val doc = PseqArchive.readDocument(pseq.inputStream())
        assertEquals("X", doc.name)
    }
}