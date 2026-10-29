package com.piano.sequencer.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import android.content.SharedPreferences

class DiagnosticsProfileTest {
    @Test fun profileChoicesRoundTripFromStableKeys() {
        assertEquals(AudioApiChoice.AAUDIO, AudioApiChoice.fromStored("aaudio"))
        assertEquals(AudioApiChoice.OPENSL_ES, AudioApiChoice.fromStored("opensl_es"))
        assertEquals(AudioApiChoice.OBOE_DEFAULT, AudioApiChoice.fromStored("unknown"))
        assertEquals(SampleRateChoice.HZ_44100, SampleRateChoice.fromStored("44100"))
        assertEquals(SampleRateChoice.SYSTEM, SampleRateChoice.fromStored("system"))
        assertEquals(SampleRateChoice.HZ_48000, SampleRateChoice.fromStored(null))
    }

    @Test fun profilePersistsChoicesUnderDedicatedPreferenceKeys() {
        val values = mutableMapOf<String, String>()
        val editor = object : SharedPreferences.Editor {
            override fun putString(key: String, value: String?): SharedPreferences.Editor { values[key] = value!!; return this }
            override fun apply() {}
            override fun commit() = true
            override fun clear() = this
            override fun remove(key: String) = this
            override fun putStringSet(key: String, value: MutableSet<String>?) = this
            override fun putInt(key: String, value: Int) = this
            override fun putLong(key: String, value: Long) = this
            override fun putFloat(key: String, value: Float) = this
            override fun putBoolean(key: String, value: Boolean) = this
        }
        val prefs = object : SharedPreferences {
            override fun edit() = editor
            override fun getString(key: String, defValue: String?) = values[key] ?: defValue
            override fun getAll(): MutableMap<String, *> = values
            override fun getStringSet(key: String, defValues: MutableSet<String>?) = null
            override fun getInt(key: String, defValue: Int) = defValue
            override fun getLong(key: String, defValue: Long) = defValue
            override fun getFloat(key: String, defValue: Float) = defValue
            override fun getBoolean(key: String, defValue: Boolean) = defValue
            override fun contains(key: String) = values.containsKey(key)
            override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
            override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        }
        assertTrue(persistDiagnosticsProfile(prefs, DiagnosticsProfile(AudioApiChoice.AAUDIO, SampleRateChoice.HZ_44100)))
        assertEquals("aaudio", values[DiagnosticsProfile.KEY_API])
        assertEquals("44100", values[DiagnosticsProfile.KEY_RATE])
        assertEquals(DiagnosticsProfile(AudioApiChoice.AAUDIO, SampleRateChoice.HZ_44100), DiagnosticsProfile.load(prefs))
        assertEquals("Saved; apply after fully restarting app", profilePersistenceMessage(true))
        assertEquals("Could not save audio diagnostic profile", profilePersistenceMessage(false))
    }

    @Test fun profilePersistenceFailureIsReported() {
        val editor = object : SharedPreferences.Editor {
            override fun putString(key: String, value: String?): SharedPreferences.Editor = this
            override fun apply() {}
            override fun commit() = false
            override fun clear() = this
            override fun remove(key: String) = this
            override fun putStringSet(key: String, value: MutableSet<String>?) = this
            override fun putInt(key: String, value: Int) = this
            override fun putLong(key: String, value: Long) = this
            override fun putFloat(key: String, value: Float) = this
            override fun putBoolean(key: String, value: Boolean) = this
        }
        val prefs = object : SharedPreferences by failingPreferences(editor) {}
        assertFalse(persistDiagnosticsProfile(prefs, DiagnosticsProfile(AudioApiChoice.OPENSL_ES, SampleRateChoice.SYSTEM)))
        assertEquals("Could not save audio diagnostic profile", profilePersistenceMessage(false))
    }

    @Test fun rejectedNativeConfigurationDoesNotPretendProfileIsActive() {
        val requested = DiagnosticsProfile(AudioApiChoice.AAUDIO, SampleRateChoice.HZ_44100)
        val active = DiagnosticsProfile()
        val rejected = profileLogSnapshot(requested, active, ProfileApplyResult.NATIVE_REJECTED)
        assertEquals(requested, rejected.requested)
        assertEquals(active, rejected.active)
        assertFalse(isProfileActuallyActive(rejected.decision))
        val alreadyActive = profileLogSnapshot(requested, active, ProfileApplyResult.ALREADY_LATCHED)
        assertEquals(active, alreadyActive.active)
        assertFalse(isProfileActuallyActive(alreadyActive.decision))
    }

    private fun failingPreferences(editor: SharedPreferences.Editor): SharedPreferences = object : SharedPreferences {
        override fun edit() = editor
        override fun getAll(): MutableMap<String, *> = mutableMapOf<String, Any>()
        override fun getString(key: String, defValue: String?) = defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?) = defValues
        override fun getInt(key: String, defValue: Int) = defValue
        override fun getLong(key: String, defValue: Long) = defValue
        override fun getFloat(key: String, defValue: Float) = defValue
        override fun getBoolean(key: String, defValue: Boolean) = defValue
        override fun contains(key: String) = false
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    @Test fun profileClaimsOncePerProcessAndManualCloseBlocksUntilProcessReset() {
        var configureCalls = 0
        val beforeOpen = ProfileActivation()
        assertEquals(ProfileApplyResult.APPLIED, applyProfileBeforeFirstOpen(beforeOpen, DiagnosticsProfile()) { _, _ -> configureCalls++; true })
        assertEquals(ProfileApplyResult.ALREADY_LATCHED, applyProfileBeforeFirstOpen(beforeOpen, DiagnosticsProfile(AudioApiChoice.AAUDIO, SampleRateChoice.HZ_44100)) { _, _ -> configureCalls++; true })
        assertTrue(isProfileConfigurationAccepted(ProfileApplyResult.ALREADY_LATCHED))
        assertFalse(isProfileConfigurationAccepted(ProfileApplyResult.NATIVE_REJECTED))
        assertEquals(1, configureCalls)
        assertEquals(DiagnosticsProfile(), beforeOpen.activeProfile())

        val retryAfterReject = ProfileActivation()
        assertEquals(ProfileApplyResult.NATIVE_REJECTED, applyProfileBeforeFirstOpen(retryAfterReject, DiagnosticsProfile(AudioApiChoice.AAUDIO, SampleRateChoice.HZ_44100)) { _, _ -> false })
        assertFalse(retryAfterReject.isApplied())
        assertEquals(ProfileApplyResult.APPLIED, applyProfileBeforeFirstOpen(retryAfterReject, DiagnosticsProfile(AudioApiChoice.OPENSL_ES, SampleRateChoice.SYSTEM)) { api, rate -> api == AudioApiChoice.OPENSL_ES.nativeValue && rate == SampleRateChoice.SYSTEM.nativeValue })
        assertEquals(DiagnosticsProfile(AudioApiChoice.OPENSL_ES, SampleRateChoice.SYSTEM), retryAfterReject.activeProfile())

        val nativeAlreadyOpen = ProfileActivation()
        assertEquals(ProfileApplyResult.NATIVE_REJECTED, applyProfileBeforeFirstOpen(nativeAlreadyOpen, DiagnosticsProfile(AudioApiChoice.AAUDIO, SampleRateChoice.HZ_44100)) { _, _ -> false })
        assertFalse(nativeAlreadyOpen.isApplied())
        val close = ManualStreamCloseState()
        assertFalse(close.isClosedByUser())
        assertTrue(allowAudioOpen(close))
        assertTrue(allowRecovery(close, terminalBackendState = true))
        assertFalse(allowRecovery(close, terminalBackendState = false))
        close.close()
        assertTrue(close.isClosedByUser())
        assertFalse(allowAudioOpen(close))
        assertFalse(allowRecovery(close, terminalBackendState = true))
        val closeOrder = mutableListOf<String>()
        val closeState = ManualStreamCloseState()
        markManualCloseBeforeNativeClose(closeState, { assertTrue(closeState.isClosedByUser()); closeOrder += "stop" }, { assertTrue(closeState.isClosedByUser()); closeOrder += "close" })
        assertEquals(listOf("stop", "close"), closeOrder)
        assertEquals(ProfileApplyResult.APPLIED, applyProfileBeforeFirstOpen(ProfileActivation(), DiagnosticsProfile()) { _, _ -> true }) // new process state
    }
}
