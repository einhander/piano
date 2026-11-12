package com.piano.sequencer.audio

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * On-disk cache of the normalized interleaved stereo PCM16 that MP3 assets
 * decode to. Import decodes each MP3 exactly once (for validation) and
 * persists the result; later slot loads (first trigger, startup prewarm,
 * test-play) read the file natively
 * (NativeEngineBridge.nativeLoadPcmSlotFromDisk) instead of re-running
 * MediaCodec, so the first press does not pay the decode cost.
 *
 * Entries are CONTENT-ADDRESSED: the cache name is the SHA-256 of the MP3
 * file, stored in `<filesDir>/mp3_pcm_cache/`. An in-place-modified asset can
 * never serve a stale buffer, and a re-import of identical content reuses
 * the entry. Cache files are plain data; the C++ reader is the authoritative
 * validator.
 *
 * Header layout (32 bytes, little-endian). Keep in sync with
 * `PcmCacheHeader` in `app/src/main/cpp/audio/PcmSlotPlayer.h`:
 *   0  magic    "PCMC"
 *   4  version  u32 (1)
 *   8  channels u32 (2, interleaved stereo)
 *   12 rate     u32
 *   16 frames   u64
 *   24 reserved u64 (0)
 *
 * Writes are atomic (tmp file + rename) and the directory is trimmed back to
 * a size cap after each write. Callers must tolerate failure: a missing
 * cache is transparently served by live decode, which re-caches.
 */
object Mp3PcmCache {
    const val DIR_NAME = "mp3_pcm_cache"
    const val MAX_DIR_BYTES: Long = 512L * 1024 * 1024
    private const val HEADER_SIZE = 32
    private const val VERSION = 1
    private const val CHUNK = 64 * 1024

    private val magic = byteArrayOf('P'.code.toByte(), 'C'.code.toByte(), 'M'.code.toByte(), 'C'.code.toByte())

    fun dir(filesDir: File): File = File(filesDir, DIR_NAME)

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(CHUNK)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun cacheFor(filesDir: File, mp3: File): File = File(dir(filesDir), "${sha256(mp3)}.pcm")

    /**
     * Writes [decoded] into the cache keyed by [mp3]'s content. Returns the
     * written file, or null on failure (tmp file removed). Never throws.
     * Trims the directory back to [MAX_DIR_BYTES] afterwards.
     */
    fun write(filesDir: File, mp3: File, decoded: DecodedMp3): File? {
        val d = dir(filesDir)
        if (!d.exists() && !d.mkdirs()) return null
        val sha = try {
            sha256(mp3)
        } catch (e: Exception) {
            return null
        }
        val target = File(d, "$sha.pcm")
        val tmp = File(d, "$sha.tmp-${System.nanoTime()}")
        try {
            val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            header.put(magic)
            header.putInt(VERSION)
            header.putInt(2)
            header.putInt(decoded.sourceRate)
            header.putLong(decoded.frames)
            header.putLong(0L)
            tmp.outputStream().buffered().use { out ->
                out.write(header.array())
                val src = decoded.pcm.duplicate()
                val chunk = ByteArray(CHUNK)
                while (src.hasRemaining()) {
                    val n = minOf(chunk.size, src.remaining())
                    src.get(chunk, 0, n)
                    out.write(chunk, 0, n)
                }
            }
            if (tmp.length() != HEADER_SIZE + decoded.byteCount.toLong()) throw IOException("PCM cache size mismatch")
            if (target.exists() && !target.delete()) throw IOException("Unable to replace PCM cache entry")
            if (!tmp.renameTo(target)) throw IOException("Unable to finalize PCM cache entry")
            trim(d)
            return target
        } catch (e: Exception) {
            tmp.delete()
            return null
        }
    }

    /**
     * Cheap pre-check before handing a cache file to the native reader.
     * Returns null when the cache is missing or obviously stale; the C++
     * reader re-validates everything authoritatively (magic/version/channels/
     * rate/frames/exact payload size).
     */
    fun candidateFor(filesDir: File, mp3: File): File? {
        val f = try {
            cacheFor(filesDir, mp3)
        } catch (e: Exception) {
            return null
        }
        if (!f.isFile || f.length() < HEADER_SIZE + 4) return null
        return try {
            f.inputStream().buffered().use { ins ->
                val h = ByteArray(HEADER_SIZE)
                if (ins.read(h) != HEADER_SIZE) return null
                if (h[0] != magic[0] || h[1] != magic[1] || h[2] != magic[2] || h[3] != magic[3]) return null
                if (u32(h, 4) != VERSION.toLong()) return null
                if (u32(h, 8) != 2L) return null
                val rate = u32(h, 12)
                if (rate !in 1L..768000L) return null
                val frames = u64(h, 16)
                if (frames <= 0) return null
                if (f.length() != HEADER_SIZE.toLong() + frames * 4L) return null
                f
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Best-effort size cap: delete oldest entries until under [maxBytes]. */
    fun trim(d: File, maxBytes: Long = MAX_DIR_BYTES) {
        val files = d.listFiles { f -> f.isFile && !f.name.endsWith(".tmp") } ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= maxBytes) break
            // Measure BEFORE deleting: File.length() reports 0 once gone.
            val sz = f.length()
            if (f.delete()) total -= sz
        }
    }

    private fun u32(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xff) or ((b[off + 1].toLong() and 0xff) shl 8) or
        ((b[off + 2].toLong() and 0xff) shl 16) or ((b[off + 3].toLong() and 0xff) shl 24)

    private fun u64(b: ByteArray, off: Int): Long = u32(b, off) or (u32(b, off + 4) shl 32)
}
