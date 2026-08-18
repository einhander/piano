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
 * Archives are user-provided: entry names are validated against zip-slip /
 * path traversal ([isArchiveEntry]), and [PseqDocument] is structurally
 * validated on read.
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
    val soundFont: String? = null, // SF2 filename (file in app external files dir; NOT bundled in archive); null = none
    val channels: List<Int> = List(16) { 0 }, // bank<<8|program, 16 entries
    val cells: List<PseqCell> = emptyList()
)

object PseqArchive {

    const val FORMAT_VERSION = 1

    const val PROJECT_JSON = "project.json"
    const val MIDI_DIR = "midi/"

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        prettyPrint = true
    }

    /**
     * True iff [name] is a conforming archive entry: [PROJECT_JSON] or
     * "midi/<basename>" where the basename is non-empty and contains no path
     * separators, no "..", no leading "/" (zip-slip / traversal protection).
     */
    fun isArchiveEntry(name: String): Boolean = name == PROJECT_JSON || isMidiEntry(name)

    private fun isMidiEntry(name: String): Boolean {
        if (!name.startsWith(MIDI_DIR)) return false
        val base = name.removePrefix(MIDI_DIR)
        // Separator-free by construction (no '/' allowed); the only dangerous
        // segment is exactly ".." — "my..song.mid" is a legal name.
        return base.isNotEmpty() &&
            !base.startsWith('/') &&
            !base.contains('/') &&
            base != ".."
    }

    /**
     * Writes the .pseq archive to [out] (the stream is always closed (success or failure)).
     *
     * @param midiFiles RELATIVE entry name ("midi/foo.mid") → local file to copy.
     *   Missing files are skipped silently.
     * @throws PseqFormatException if [doc.formatVersion] is not [FORMAT_VERSION]
     *   or writing fails.
     */
    fun write(out: OutputStream, doc: PseqDocument, midiFiles: Map<String, File>) {
        if (doc.formatVersion != FORMAT_VERSION) {
            throw PseqFormatException("unsupported format version ${doc.formatVersion}")
        }
        try {
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry(PROJECT_JSON))
                zip.write(json.encodeToString(doc).toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                for ((entryName, file) in midiFiles) {
                    if (!isArchiveEntry(entryName)) {
                        throw PseqFormatException("invalid entry name: $entryName")
                    }
                    if (!file.exists()) continue
                    zip.putNextEntry(ZipEntry(entryName))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        } catch (e: ZipException) {
            throw PseqFormatException("Failed to write zip archive: ${e.message}", e)
        } catch (e: IOException) {
            throw PseqFormatException("Failed to write zip archive: ${e.message}", e)
        }
    }

    /**
     * Reads and validates project.json from the archive.
     * @throws PseqFormatException if the zip is corrupt, project.json is missing,
     *   the JSON is invalid, the format version is unsupported, or the document
     *   fails structural validation.
     */
    fun readDocument(input: InputStream): PseqDocument {
        val text = readEntry(input, PROJECT_JSON)
            ?: throw PseqFormatException("project.json not found")
        val decoded = try {
            json.decodeFromString<PseqDocument>(text)
        } catch (e: Exception) {
            throw PseqFormatException("Invalid project.json: ${e.message}", e)
        }
        val doc = migrate(decoded)
        validateDocument(doc)
        return doc
    }

    // Migration hook for future format versions (v2 → add a branch here).
    private fun migrate(doc: PseqDocument): PseqDocument = when (doc.formatVersion) {
        FORMAT_VERSION -> doc
        else -> throw PseqFormatException("unsupported format version ${doc.formatVersion}")
    }

    private fun validateDocument(doc: PseqDocument) {
        if (doc.channels.size != 16) {
            throw PseqFormatException(
                "Invalid document: channels must have 16 entries, got ${doc.channels.size}"
            )
        }
        val seenIds = HashSet<Int>()
        for (cell in doc.cells) {
            if (!seenIds.add(cell.id)) {
                throw PseqFormatException("Invalid document: duplicate cell id ${cell.id}")
            }
            if (cell.filePath.isNotEmpty() && !isMidiEntry(cell.filePath)) {
                throw PseqFormatException(
                    "Invalid document: cell ${cell.id} has invalid midi path '${cell.filePath}'"
                )
            }
        }
    }

    /**
     * Streams the named zip entry to [dest] (parent directories are created).
     * The entry is streamed in chunks, not loaded whole into memory.
     * @throws PseqFormatException if [entryName] (or the matching archive entry)
     *   is not a conforming entry name, the entry is missing, or the zip is corrupt.
     */
    fun extractEntry(input: InputStream, entryName: String, dest: File) {
        if (!isArchiveEntry(entryName)) {
            throw PseqFormatException("invalid entry name: $entryName")
        }
        dest.parentFile?.mkdirs()
        try {
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == entryName) {
                        if (!isArchiveEntry(entry.name)) {
                            throw PseqFormatException("invalid entry name in archive: ${entry.name}")
                        }
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

    /** All conforming entry names in the archive (non-conforming names are filtered out). */
    fun listEntries(input: InputStream): Set<String> {
        val names = mutableSetOf<String>()
        try {
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (isArchiveEntry(entry.name)) names.add(entry.name)
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