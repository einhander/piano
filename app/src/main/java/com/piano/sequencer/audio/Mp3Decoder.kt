package com.piano.sequencer.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

data class DecodedMp3(val pcm: ByteBuffer, val frames: Long, val sourceRate: Int, val byteCount: Int)

/** Synchronous Android platform MP3 decoder. Call only from a worker thread. */
object Mp3Decoder {
    const val MAX_PCM_BYTES = 128 * 1024 * 1024
    private const val TIMEOUT_US = 10_000L
    private const val MAX_STALL_MS = 5_000L
    private const val MAX_DECODE_MS = 120_000L

    fun decode(file: File): DecodedMp3 {
        require(file.isFile) { "MP3 file does not exist" }
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == "audio/mpeg"
            } ?: throw IllegalArgumentException("File contains no audio/mpeg track")
            extractor.selectTrack(track)
            val input = extractor.getTrackFormat(track)
            codec = MediaCodec.createDecoderByType("audio/mpeg")
            codec.configure(input, null, null, 0)
            codec.start()
            var rate = input.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = input.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatOutput = false
            var formatReady = false
            var inputEos = false
            var outputEos = false
            var size = 0
            // Fixed-capacity buffer prevents old/new growth buffers from
            // exceeding the decoder's bounded PCM allowance during reallocation.
            // Sized from the container duration (1.5x margin, clamped to
            // [2 MiB, MAX]) so short clips do not reserve the full limit;
            // missing or lying durations fall back to the full MAX.
            val durationUs = if (input.containsKey(MediaFormat.KEY_DURATION)) input.getLong(MediaFormat.KEY_DURATION) else 0L
            val capacity = if (durationUs > 0L) {
                (durationUs / 1_000_000.0 * rate * 4.0 * 1.5).toLong()
                    .coerceIn(2L * 1024 * 1024, MAX_PCM_BYTES.toLong()).toInt()
            } else {
                MAX_PCM_BYTES
            }
            var data = ByteBuffer.allocateDirect(capacity).order(ByteOrder.LITTLE_ENDIAN)
            val info = MediaCodec.BufferInfo()
            val begin = System.nanoTime()
            var lastProgress = begin
            while (!outputEos) {
                val now = System.nanoTime()
                if (TimeUnit.NANOSECONDS.toMillis(now - begin) > MAX_DECODE_MS ||
                    TimeUnit.NANOSECONDS.toMillis(now - lastProgress) > MAX_STALL_MS) {
                    throw IllegalArgumentException("MP3 decode stalled or exceeded time limit")
                }
                if (!inputEos) {
                    val index = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buf = codec.getInputBuffer(index) ?: throw IllegalArgumentException("Decoder input unavailable")
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEos = true
                        } else {
                            codec.queueInputBuffer(index, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                            lastProgress = System.nanoTime()
                        }
                    }
                }
                when (val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        val newChannels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        val newRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        val encoding = if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) f.getInteger(MediaFormat.KEY_PCM_ENCODING) else android.media.AudioFormat.ENCODING_PCM_16BIT
                        val newFloat = encoding == android.media.AudioFormat.ENCODING_PCM_FLOAT
                        if (formatReady && (newRate != rate || newChannels != channels || newFloat != floatOutput)) throw IllegalArgumentException("Decoder PCM format changed during stream")
                        rate = newRate
                        channels = newChannels
                        floatOutput = newFloat
                        if (!floatOutput && encoding != android.media.AudioFormat.ENCODING_PCM_16BIT) throw IllegalArgumentException("Unsupported decoder PCM format")
                        if (channels !in 1..2 || rate !in 8000..192000) throw IllegalArgumentException("Unsupported decoder channel count or sample rate")
                        formatReady = true
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> if (index >= 0) {
                        try {
                            if (info.size > 0) {
                                if (!formatReady) throw IllegalArgumentException("Decoder produced PCM before output format")
                                val out = codec.getOutputBuffer(index) ?: throw IllegalArgumentException("Decoder output unavailable")
                                val unit = if (floatOutput) 4 else 2
                                if (info.offset < 0 || info.size < 0 || info.offset.toLong() + info.size > out.capacity() || info.size % (channels * unit) != 0)
                                    throw IllegalArgumentException("Invalid decoder PCM buffer")
                                val frames = info.size / (channels * unit)
                                val add = frames.toLong() * 4
                                if (add > capacity || size.toLong() + add > capacity)
                                    throw IllegalArgumentException("Decoded MP3 exceeds the 128 MiB PCM limit (about 12.7 min at 44.1 kHz)")
                                val required = size + add.toInt()
                                val src = out.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                                src.position(info.offset); src.limit(info.offset + info.size)
                                repeat(frames) {
                                    val l: Short
                                    val r: Short
                                    if (floatOutput) {
                                        fun sample(): Short { val x = src.float; return (if (x.isNaN()) 0f else x.coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
                                        val a = sample(); val b = if (channels == 1) a else sample(); l = a; r = b
                                    } else {
                                        val a = src.short; val b = if (channels == 1) a else src.short; l = a; r = b
                                    }
                                    data.putShort(l); data.putShort(r)
                                }
                                size = required
                                lastProgress = System.nanoTime()
                            }
                            outputEos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        } finally { codec.releaseOutputBuffer(index, false) }
                    }
                }
            }
            if (size == 0) throw IllegalArgumentException("MP3 decoded no audio")
            data.position(0); data.limit(size)
            return DecodedMp3(data, size.toLong() / 4, rate, size)
        } catch (e: IllegalArgumentException) { throw e }
        catch (e: Exception) { throw IllegalArgumentException("Unable to decode MP3: ${e.message ?: e.javaClass.simpleName}", e) }
        finally { try { codec?.stop() } catch (_: Exception) {}; try { codec?.release() } catch (_: Exception) {}; extractor.release() }
    }
}
