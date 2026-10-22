package com.piano.sequencer.midi

import java.io.File

/** Initial presets packed as (bank << 8) | program, matching app persistence. */
object MidiInitialPrograms {
    fun defaults(): List<Int> = List(16) { if (it == 9) 128 shl 8 else 0 }

    fun fromFile(file: File): List<Int> = try { parse(file.readBytes()) } catch (_: Exception) { defaults() }

    private data class Event(val tick: Long, val track: Int, val order: Long, val kind: Int, val channel: Int, val a: Int, val b: Int)

    fun parse(data: ByteArray): List<Int> {
        val defaults = defaults()
        if (data.size < 14 || !ascii(data, 0, "MThd")) return defaults
        val headerLength = u32(data, 4) ?: return defaults
        if (headerLength < 6 || headerLength > data.size - 8) return defaults
        val trackCount = u16(data, 10)
        var pos = 8 + headerLength
        val events = mutableListOf<Event>()
        var order = 0L
        repeat(trackCount) { track ->
            if (pos + 8 > data.size || !ascii(data, pos, "MTrk")) return defaults
            val length = u32(data, pos + 4) ?: return defaults
            if (length > data.size - pos - 8) return defaults
            val end = pos + 8 + length
            pos += 8
            var runningStatus = 0
            var tick = 0L
            while (pos < end) {
                val delta = readVlq(data, pos, end) ?: return defaults
                pos = delta.second
                tick += delta.first
                if (pos >= end) return defaults
                var status = data[pos].toInt() and 0xff
                if (status < 0x80) {
                    if (runningStatus == 0) return defaults
                    status = runningStatus
                } else {
                    pos++
                    runningStatus = if (status < 0xf0) status else 0
                }
                if (status == 0xff) {
                    if (pos >= end) return defaults
                    pos++
                    val metaLength = readVlq(data, pos, end) ?: return defaults
                    pos = metaLength.second
                    if (metaLength.first > end - pos) return defaults
                    pos += metaLength.first
                    continue
                }
                if (status == 0xf0 || status == 0xf7) {
                    val sysexLength = readVlq(data, pos, end) ?: return defaults
                    pos = sysexLength.second
                    if (sysexLength.first > end - pos) return defaults
                    pos += sysexLength.first
                    continue
                }
                val kind = status and 0xf0
                if (kind !in setOf(0x80, 0x90, 0xa0, 0xb0, 0xc0, 0xd0, 0xe0)) return defaults
                val channel = status and 0x0f
                val count = if (kind == 0xc0 || kind == 0xd0) 1 else 2
                if (pos + count > end) return defaults
                val a = data[pos++].toInt() and 0xff
                val b = if (count == 2) data[pos++].toInt() and 0xff else 0
                if (a > 127 || b > 127) return defaults
                events += Event(tick, track, order++, kind, channel, a, b)
            }
            pos = end
        }
        val bankMsb = IntArray(16)
        val bankLsb = IntArray(16)
        val bankSelected = BooleanArray(16)
        val programs = IntArray(16)
        val captured = BooleanArray(16)
        val capturedPrograms = IntArray(16)
        for (event in events.sortedWith(compareBy<Event> { it.tick }.thenBy { it.track }.thenBy { it.order })) {
            val ch = event.channel
            when {
                event.kind == 0xb0 && event.a == 0 -> { bankMsb[ch] = event.b; bankSelected[ch] = true }
                event.kind == 0xb0 && event.a == 32 -> { bankLsb[ch] = event.b; bankSelected[ch] = true }
                event.kind == 0xc0 -> programs[ch] = event.a
                event.kind == 0x90 && event.b > 0 && !captured[ch] -> {
                    val bank = if (ch == 9 && !bankSelected[ch]) 128 else bankMsb[ch] * 128 + bankLsb[ch]
                    capturedPrograms[ch] = (bank shl 8) or programs[ch]
                    captured[ch] = true
                }
            }
        }
        val result = defaults.toMutableList()
        for (ch in 0..15) if (captured[ch]) result[ch] = capturedPrograms[ch]
        return result
    }

    private fun ascii(data: ByteArray, offset: Int, value: String): Boolean =
        offset + value.length <= data.size && value.indices.all { data[offset + it].toInt().toChar() == value[it] }
    private fun u16(data: ByteArray, offset: Int): Int = ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)
    private fun u32(data: ByteArray, offset: Int): Int? {
        val value = ((data[offset].toLong() and 0xff) shl 24) or ((data[offset + 1].toLong() and 0xff) shl 16) or
            ((data[offset + 2].toLong() and 0xff) shl 8) or (data[offset + 3].toLong() and 0xff)
        return value.takeIf { it <= Int.MAX_VALUE }?.toInt()
    }
    private fun readVlq(data: ByteArray, start: Int, end: Int): Pair<Int, Int>? {
        var position = start
        var value = 0
        repeat(4) {
            if (position >= end) return null
            val byte = data[position++].toInt() and 0xff
            value = (value shl 7) or (byte and 0x7f)
            if (byte < 0x80) return value to position
        }
        return null
    }
}
