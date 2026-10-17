package com.piano.sequencer

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sin

/** Allocation-free, in-place radix-2 spectrum transform. Values returned are dBFS. */
internal class SpectrumAnalyzer(private val size: Int) {
    private val real = FloatArray(size)
    private val imag = FloatArray(size)
    private val window = FloatArray(size) { i ->
        if (size <= 1) 1f else (0.5 - 0.5 * cos(2.0 * PI * i / (size - 1))).toFloat()
    }

    init { require(size >= 2 && size and (size - 1) == 0) }

    /** [samples] must contain at least [size] finite-or-not samples. */
    fun spectrum(samples: FloatArray, sampleRate: Int, bins: FloatArray, lowHz: Float = 20f, highHz: Float = sampleRate / 2f) {
        require(samples.size >= size && bins.isNotEmpty())
        real.fill(0f); imag.fill(0f)
        var sumWindow = 0.0
        for (i in 0 until size) {
            val v = samples[i].takeIf { it.isFinite() } ?: 0f
            real[i] = v * window[i]
            sumWindow += window[i]
        }
        var j = 0
        for (i in 1 until size) {
            var bit = size shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val t = real[i]; real[i] = real[j]; real[j] = t }
        }
        var length = 2
        while (length <= size) {
            val half = length shr 1
            val angle = -2.0 * PI / length
            val stepR = cos(angle).toFloat(); val stepI = sin(angle).toFloat()
            var base = 0
            while (base < size) {
                var wr = 1f; var wi = 0f
                for (k in 0 until half) {
                    val even = base + k; val odd = even + half
                    val tr = wr * real[odd] - wi * imag[odd]
                    val ti = wr * imag[odd] + wi * real[odd]
                    real[odd] = real[even] - tr; imag[odd] = imag[even] - ti
                    real[even] += tr; imag[even] += ti
                    val next = wr * stepR - wi * stepI; wi = wr * stepI + wi * stepR; wr = next
                }
                base += length
            }
            length = length shl 1
        }
        val nyquist = sampleRate / 2f
        val lo = lowHz.coerceAtLeast(sampleRate.toFloat() / size)
        val hi = highHz.coerceIn(lo, nyquist)
        val minLog = ln(lo.toDouble()); val logSpan = (ln(hi.toDouble()) - minLog).coerceAtLeast(1e-9)
        for (b in bins.indices) {
            val f0 = lo * kotlin.math.exp(logSpan * b / bins.size).toFloat()
            val f1 = lo * kotlin.math.exp(logSpan * (b + 1) / bins.size).toFloat()
            val first = (f0 * size / sampleRate).toInt().coerceIn(1, size / 2)
            val last = (f1 * size / sampleRate).toInt().coerceIn(first, size / 2)
            var peak = 0.0
            for (k in first..last) {
                val scale = if (k == size / 2) 1.0 else 2.0
                peak = maxOf(peak, hypot(real[k].toDouble(), imag[k].toDouble()) * scale / sumWindow.coerceAtLeast(1.0))
            }
            bins[b] = (20.0 * log10(peak.coerceAtLeast(1e-6))).toFloat().coerceIn(-120f, 0f)
        }
    }
}
