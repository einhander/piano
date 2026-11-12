package com.piano.sequencer.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class Mp3PcmCacheTest {

    private fun decoded(frames: Int, rate: Int = 44100): DecodedMp3 {
        val buf = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            buf.putShort((i * 100).toShort())
            buf.putShort((-i * 100).toShort())
        }
        buf.position(0)
        buf.limit(frames * 4)
        return DecodedMp3(buf, frames.toLong(), rate, frames * 4)
    }

    private fun mp3(content: Byte): File =
        Files.createTempFile("mp3pcmtest", ".mp3").toFile().apply { writeBytes(byteArrayOf(content)) }

    private fun mutateHeader(f: File, fn: (ByteArray) -> Unit) {
        val h = ByteArray(32)
        f.inputStream().use { ins ->
            if (ins.read(h) != 32) throw IllegalStateException("short header")
        }
        fn(h)
        RandomAccessFile(f, "rw").use { it.write(h) }
    }

    /** File.truncate is not in the Android API surface; resize via setLength. */
    private fun resize(f: File, len: Long) {
        RandomAccessFile(f, "rw").use { it.setLength(len) }
    }

    @Test fun writeStoresReadableEntryKeyedByContent() {
        val root = Files.createTempDirectory("pcmdir").toFile()
        val a = mp3(1)
        val b = mp3(2)
        val wa = Mp3PcmCache.write(root, a, decoded(5))
        val wb = Mp3PcmCache.write(root, b, decoded(5))
        assertNotNull(wa)
        assertNotNull(wb)
        assertEquals(32 + 20, wa!!.length())
        assertEquals("mp3_pcm_cache", wa.parentFile?.name)
        // Identical content reuses the entry; different content gets its own.
        val aAgain = Files.createTempFile("mp3pcmtest", ".mp3").toFile().apply { writeBytes(byteArrayOf(1)) }
        assertEquals(wa.absolutePath, Mp3PcmCache.cacheFor(root, a).absolutePath)
        assertEquals(wa.absolutePath, Mp3PcmCache.cacheFor(root, aAgain).absolutePath)
        assertNotEquals(wa.absolutePath, Mp3PcmCache.cacheFor(root, b).absolutePath)
        assertEquals(wa.absolutePath, Mp3PcmCache.candidateFor(root, a)?.absolutePath)
        assertEquals(wb!!.absolutePath, Mp3PcmCache.candidateFor(root, b)?.absolutePath)
    }

    @Test fun candidateDetectsHeaderCorruptionAndHealsByRewrite() {
        val corrupts = mapOf(
            "magic" to { h: ByteArray -> h[0] = 'X'.code.toByte() },
            "version" to { h: ByteArray -> h[4] = 2 },
            "mono" to { h: ByteArray -> h[8] = 1 },
            "rate" to { h: ByteArray -> h[12] = 0; h[13] = 0; h[14] = 0; h[15] = 0 },
            "frames" to { h: ByteArray -> h[16] = 0; h[17] = 0; h[18] = 0; h[19] = 0 }
        )
        for ((name, fix) in corrupts) {
            val root = Files.createTempDirectory("pcmdir").toFile()
            val f = mp3(9)
            val w = Mp3PcmCache.write(root, f, decoded(5))!!
            assertNotNull(name, Mp3PcmCache.candidateFor(root, f))
            mutateHeader(w, fix)
            assertNull("$name corruption not detected", Mp3PcmCache.candidateFor(root, f))
        }
        val root = Files.createTempDirectory("pcmdir").toFile()
        val f = mp3(9)
        val w = Mp3PcmCache.write(root, f, decoded(5))!!
        resize(w, 32 + 4) // claims 5 frames, payload holds 1
        assertNull("short payload not detected", Mp3PcmCache.candidateFor(root, f))
        resize(w, 16) // header itself truncated
        assertNull("short header not detected", Mp3PcmCache.candidateFor(root, f))
        assertNotNull(Mp3PcmCache.write(root, f, decoded(5)))
        assertNotNull("rewrite must heal", Mp3PcmCache.candidateFor(root, f))
    }

    @Test fun trimEvictsOldestFirstToStayUnderCap() {
        val root = Files.createTempDirectory("pcmdir").toFile()
        val d = Mp3PcmCache.dir(root)
        val files = (1..4).map { i ->
            val f = mp3(i.toByte())
            val w = Mp3PcmCache.write(root, f, decoded(1))!!
            // Epoch-adjacent mtimes are clamped to 0 on some virtualized
            // filesystems; use current time + offsets (staggered by 1 s).
            w.setLastModified(System.currentTimeMillis() + 1000L * i)
            w
        }
        // 4 x 36 bytes = 144; cap 72 keeps the two newest (3000, 4000).
        Mp3PcmCache.trim(d, 2 * 36)
        val remaining = (d.listFiles() ?: emptyArray<File>()).map { it.name }
        // listFiles() order is unspecified; compare as sets.
        assertEquals(setOf(files[2].name, files[3].name), remaining.toSet())
    }

    @Test fun missingSourceIsAHandledFailure() {
        val root = Files.createTempDirectory("pcmdir").toFile()
        val ghost = File(root, "ghost.mp3")
        assertNull(Mp3PcmCache.write(root, ghost, decoded(2)))
        assertNull(Mp3PcmCache.candidateFor(root, ghost))
    }

    @Test fun sha256IsStableAndDistinct() {
        val a = mp3(1)
        val b = mp3(2)
        val sa = Mp3PcmCache.sha256(a)
        val sb = Mp3PcmCache.sha256(b)
        assertEquals(64, sa.length)
        assertEquals(sa, Mp3PcmCache.sha256(a))
        assertNotEquals(sa, sb)
    }
}
