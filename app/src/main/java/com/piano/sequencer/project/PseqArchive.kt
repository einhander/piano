package com.piano.sequencer.project

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * .pseq project archive: a ZIP containing
 *   - project.json         (settings, [PseqDocument] as UTF-8 JSON)
 *   - midi/<file>.mid      (copies of the sequencer's MIDI files)
 *
 * The SoundFont is NOT stored in the archive: [PseqDocument.soundFont] holds
 * the SF2 filename as a setting only.
 *
 * Pure JVM (java.io + java.util.zip + kotlinx.serialization) — unit-testable
 * without Android.
 */

class PseqFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

@Serializable
data class PseqCell(
    val id: Int,
    val note: Int = -1,
    val filePath: String = "",   // RELATIVE path inside archive, e.g. "midi/foo.mid"; "" = no file
    val loop: Boolean = false,
    val tempo: Double = 120.0,
    val channel: Int = -1
)

@Serializable
data class PseqDocument(
    val formatVersion: Int,
    val name: String,
    val createdAt: String,        // ISO-8601
    val bpm: Double = 120.0,
    val ppq: Int = 480,
    val numerator: Int = 4,
    val denominator: Int = 4,
    val masterGain: Float = 1.0f,
    val polyphony: Int = 128,
    val soundFont: String? = null, // filename under soundfonts/, null = none
    val channels: List<Int> = List(16) { 0 }, // bank<<8|program, 16 entries
    val cells: List<PseqCell> = emptyList()
)

object PseqArchive {

    const val FORMAT_VERSION = 1

    const val PROJECT_JSON = "project.json"
    const val SOUNDFONTS_DIR = "soundfonts/"
    const val MIDI_DIR = "midi/"

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        prettyPrint = true
    }

    /**
     * Writes the .pseq archive to [out] (the stream is closed on completion).
     *
     * @param midiFiles RELATIVE entry name ("midi/foo.mid") → local file to copy.
     *   Missing files are skipped silently.
     */
    fun write(out: OutputStream, doc: PseqDocument, midiFiles: Map<String, File>) {
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(PROJECT_JSON))
            zip.write(json.encodeToString(doc).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            for ((entryName, file) in midiFiles) {
                if (!file.exists()) continue
                zip.putNextEntry(ZipEntry(entryName))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /**
     * Reads and validates project.json from the archive.
     * @throws PseqFormatException if the zip is corrupt, project.json is missing,
     *   the JSON is invalid, or the format version is unsupported.
     */
    fun readDocument(input: InputStream): PseqDocument {
        val text = readEntry(input, PROJECT_JSON)
            ?: throw PseqFormatException("project.json not found")
        val doc = try {
            json.decodeFromString<PseqDocument>(text)
        } catch (e: Exception) {
            throw PseqFormatException("Invalid project.json: ${e.message}", e)
        }
        if (doc.formatVersion != FORMAT_VERSION) {
            throw PseqFormatException("unsupported format version ${doc.formatVersion}")
        }
        return doc
    }

    /**
     * Streams the named zip entry to [dest] (parent directories are created).
     * The entry is streamed in chunks, not loaded whole into memory.
     * @throws PseqFormatException if the entry is missing or the zip is corrupt.
     */
    fun extractEntry(input: InputStream, entryName: String, dest: File) {
        dest.parentFile?.mkdirs()
        try {
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == entryName) {
                        FileOutputStream(dest).use { zip.copyTo(it) }
                        zip.closeEntry()
                        return
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } catch (e: ZipException) {
            throw PseqFormatException("Corrupt zip archive: ${e.message}", e)
        } catch (e: IOException) {
            throw PseqFormatException("Failed to read zip archive: ${e.message}", e)
        }
        throw PseqFormatException("entry not found: $entryName")
    }

    /** All entry names in the archive (validation/debugging). */
    fun listEntries(input: InputStream): Set<String> {
        val names = mutableSetOf<String>()
        try {
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    names.add(entry.name)
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } catch (e: ZipException) {
            throw PseqFormatException("Corrupt zip archive: ${e.message}", e)
        } catch (e: IOException) {
            throw PseqFormatException("Failed to read zip archive: ${e.message}", e)
        }
        return names
    }

    /**
     * Collision dedupe for import destination names — same rule as
     * SequencerActivity.importMidiFile: "foo.mid" → "foo.mid" if free, else
     * "foo_1.mid", "foo_2.mid", ...
     */
    fun uniqueDestName(fileName: String, exists: (String) -> Boolean): String {
        if (!exists(fileName)) return fileName
        val base = fileName.removeSuffix(".mid")
        var counter = 1
        while (true) {
            val candidate = "${base}_$counter.mid"
            if (!exists(candidate)) return candidate
            counter++
        }
    }

    private fun readEntry(input: InputStream, entryName: String): String? {
        try {
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == entryName) {
                        val text = zip.readBytes().decodeToString() // UTF-8
                        zip.closeEntry()
                        return text
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } catch (e: ZipException) {
            throw PseqFormatException("Corrupt zip archive: ${e.message}", e)
        } catch (e: IOException) {
            throw PseqFormatException("Failed to read zip archive: ${e.message}", e)
        }
        return null
    }
}