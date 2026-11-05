package com.piano.sequencer

import android.content.SharedPreferences

/** Saved requests are latched by native code at first engine startup. */
data class AudioCpuConfig(val fastCores: Boolean = false, val synthCores: Int = 1) {
    fun persist(prefs: SharedPreferences) {
        prefs.edit().putBoolean(FAST_CORES, fastCores)
            .putInt(SYNTH_CORES, validatedCores(synthCores)).apply()
    }

    companion object {
        const val FAST_CORES = "audio_diag_fast_cores"
        const val SYNTH_CORES = "audio_diag_synth_cores"
        fun validatedCores(value: Int): Int = if (value in listOf(1, 2, 4)) value else 1
        fun read(prefs: SharedPreferences) = AudioCpuConfig(
            prefs.getBoolean(FAST_CORES, false), validatedCores(prefs.getInt(SYNTH_CORES, 1)))
    }
}
