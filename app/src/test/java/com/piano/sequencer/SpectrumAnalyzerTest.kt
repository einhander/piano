package com.piano.sequencer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SpectrumAnalyzerTest {
    @Test fun sinePeakLandsInExpectedLogBin() {
        val n = 2048; val rate = 48000
        val samples = FloatArray(n) { sin(2.0 * PI * 1000 * it / rate).toFloat() }
        val bins = FloatArray(96)
        SpectrumAnalyzer(n).spectrum(samples, rate, bins)
        val peak = bins.indices.maxBy { bins[it] }
        val hz = 20.0 * kotlin.math.exp(kotlin.math.ln(24000.0 / 20.0) * (peak + 0.5) / bins.size)
        assertTrue("peak frequency=$hz", hz in 850.0..1180.0)
        assertTrue(bins[peak] > -6f)
    }

    @Test fun silenceIsFiniteAtFloor() {
        val bins = FloatArray(24)
        SpectrumAnalyzer(1024).spectrum(FloatArray(1024), 48000, bins)
        assertTrue(bins.all { it.isFinite() && it == -120f })
    }

    @Test fun nyquistAndInvalidSamplesRemainBounded() {
        val n = 1024; val samples = FloatArray(n) { if (it % 2 == 0) 1f else -1f }
        samples[7] = Float.NaN
        val bins = FloatArray(48)
        SpectrumAnalyzer(n).spectrum(samples, 48000, bins, 100f, 24000f)
        assertTrue(bins.all { it.isFinite() && it in -120f..0f })
        assertTrue(bins.first() <= -40f)
    }
}
