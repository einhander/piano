package com.piano.sequencer.midi

import android.content.Context
import android.net.Uri
import com.piano.sequencer.audio.Mp3Decoder
import java.io.File
import java.io.InputStream
import java.util.Locale

data class PreparedSequencerAsset(val file: File, val assetType: String)

/** Apply only after validation succeeded; trigger and loop settings are retained. */
fun applyImportedAsset(cell: SequencerCell, asset: PreparedSequencerAsset): SequencerCell = cell.copy(
    filePath = asset.file.absolutePath, assetType = asset.assetType, mode = MODE_FILE,
    chordNotes = emptyList(),
    selectedTracks = if (asset.assetType == ASSET_MP3 || cell.isMp3) null else cell.selectedTracks,
    trackChannels = if (asset.assetType == ASSET_MP3 || cell.isMp3) emptyMap() else cell.trackChannels,
    trackInstrumentSets = if (asset.assetType == ASSET_MP3 || cell.isMp3) emptyMap() else cell.trackInstrumentSets,
    instrumentSetId = if (asset.assetType == ASSET_MP3 || cell.isMp3) null else cell.instrumentSetId,
    channel = if (asset.assetType == ASSET_MP3 || cell.isMp3) -1 else cell.channel,
)

/** Copies and validates external sequencer assets before they can be assigned to a cell. */
object SequencerAssetImporter {
    const val MAX_INPUT_BYTES = 256L * 1024 * 1024

    fun import(context: Context, uri: Uri): PreparedSequencerAsset {
        val resolver = context.contentResolver
        val displayName = resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            .orEmpty()
        val mime = resolver.getType(uri)
        val input = resolver.openInputStream(uri) ?: throw IllegalArgumentException("Unable to open selected file")
        val directory = File(context.filesDir, "midi_files")
        return importStream(displayName, mime, input, directory) { file, type ->
            if (type == ASSET_MP3) Mp3Decoder.decode(file)
        }
    }

    internal fun importStream(
        displayName: String,
        mimeType: String?,
        input: InputStream,
        directory: File,
        validateMp3: (File, String) -> Unit
    ): PreparedSequencerAsset {
        input.use { source ->
            val hint = resolveType(displayName, mimeType)
            if (!directory.exists() && !directory.mkdirs()) throw IllegalStateException("Unable to create asset directory")
            val safeName = displayName.substringAfterLast('/').substringAfterLast('\\')
                .replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.').ifBlank { "asset" }.take(80)
            val extension = if (hint == ASSET_MP3) ".mp3" else ".mid"
            var target: File
            do { target = File(directory, "${safeName.substringBeforeLast('.', safeName)}_${java.util.UUID.randomUUID()}$extension") }
            while (!target.createNewFile())
            try {
                target.outputStream().buffered().use { out ->
                    val buffer = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_INPUT_BYTES) { "Selected file exceeds 256 MiB limit" }
                        out.write(buffer, 0, count)
                    }
                }
                val actual = detectType(target) ?: throw IllegalArgumentException("Selected file is not a supported MIDI or MP3 file")
                require(actual == hint) { "Selected file content does not match its file type" }
                if (actual == ASSET_MIDI) validateMidi(target) else validateMp3(target, actual)
                return PreparedSequencerAsset(target, actual)
            } catch (failure: Throwable) {
                target.delete()
                throw failure
            }
        }
    }

    private fun resolveType(name: String, mime: String?): String {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when {
            mime.equals("audio/mpeg", true) || ext == "mp3" -> ASSET_MP3
            mime in listOf("audio/midi", "audio/x-midi", "application/x-midi") || ext in listOf("mid", "midi") -> ASSET_MIDI
            else -> throw IllegalArgumentException("Choose a MIDI (.mid) or MP3 (.mp3) file")
        }
    }

    private fun detectType(file: File): String? = file.inputStream().buffered().use { input ->
        val h = ByteArray(14)
        val n = input.read(h)
        if (n >= 14 && String(h, 0, 4, Charsets.US_ASCII) == "MThd") ASSET_MIDI
        else if (n >= 3 && h[0] == 'I'.code.toByte() && h[1] == 'D'.code.toByte() && h[2] == '3'.code.toByte() ||
            n >= 2 && h[0] == 0xff.toByte() && h[1].toInt() and 0xe0 == 0xe0) ASSET_MP3 else null
    }

    private fun validateMidi(file: File) {
        file.inputStream().buffered().use { input ->
            fun readByte(): Int = input.read().also { require(it >= 0) { "Truncated MIDI file" } }
            fun u16() = (readByte() shl 8) or readByte()
            fun u32() = (readByte().toLong() shl 24) or (readByte().toLong() shl 16) or (readByte().toLong() shl 8) or readByte().toLong()
            require(byteArrayOf(readByte().toByte(), readByte().toByte(), readByte().toByte(), readByte().toByte()).contentEquals("MThd".toByteArray())) { "Invalid MIDI header" }
            val headerLength = u32()
            require(headerLength == 6L) { "Invalid MIDI header length" }
            val format = u16(); val tracks = u16(); val division = u16()
            require(format in 0..2 && tracks > 0 && (format != 0 || tracks == 1) && division != 0) { "Invalid MIDI format" }
            repeat(tracks) {
                require(byteArrayOf(readByte().toByte(), readByte().toByte(), readByte().toByte(), readByte().toByte()).contentEquals("MTrk".toByteArray())) { "Invalid MIDI track" }
                var remaining = u32()
                require(remaining <= MAX_INPUT_BYTES) { "Invalid MIDI track length" }
                val buffer = ByteArray(1024)
                while (remaining > 0) {
                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    require(count > 0) { "Truncated MIDI track" }
                    remaining -= count
                }
            }
            require(input.read() == -1) { "Unexpected data after MIDI tracks" }
        }
    }
}
