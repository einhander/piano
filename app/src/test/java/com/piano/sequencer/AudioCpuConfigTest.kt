package com.piano.sequencer

import org.junit.Assert.assertEquals
import org.junit.Test
import android.content.SharedPreferences
import java.lang.reflect.Proxy

class AudioCpuConfigTest {
    @Test fun settingsPersistAndInvalidStoredCountFallsBack() {
        val values = mutableMapOf<String, Any>()
        lateinit var editor: SharedPreferences.Editor
        editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
            when (method.name) {
                "putBoolean", "putInt" -> { values[args!![0] as String] = args[1]!!; editor }
                "apply" -> null
                else -> error("Unexpected editor method: ${method.name}")
            }
        } as SharedPreferences.Editor
        val prefs = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "edit" -> editor
                "getBoolean", "getInt" -> values[args!![0]] ?: args[1]
                else -> error("Unexpected prefs method: ${method.name}")
            }
        } as SharedPreferences
        assertEquals(AudioCpuConfig(), AudioCpuConfig.read(prefs))
        AudioCpuConfig(true, 4).persist(prefs)
        assertEquals(AudioCpuConfig(true, 4), AudioCpuConfig.read(prefs))
        values[AudioCpuConfig.SYNTH_CORES] = 8
        assertEquals(AudioCpuConfig(true, 1), AudioCpuConfig.read(prefs))
    }

    @Test fun defaultsPreserveSingleThreadWithoutAffinity() {
        assertEquals(false, AudioCpuConfig().fastCores)
        assertEquals(1, AudioCpuConfig().synthCores)
    }
    @Test fun onlySupportedDiagnosticCoreCountsAreAccepted() {
        for (value in listOf(1, 2, 4)) assertEquals(value, AudioCpuConfig.validatedCores(value))
        for (value in listOf(-1, 0, 3, 8, Int.MAX_VALUE))
            assertEquals(1, AudioCpuConfig.validatedCores(value))
    }
}
