package com.piano.sequencer.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import android.content.SharedPreferences

class DiagnosticsProfileTest {
    @Test fun resumeRequiresKnownSameRateAndCapacityFit() {
        assertTrue(diagnosticResumeDecision(48000, 48000, 1024, 512).allowed)
        assertFalse(diagnosticResumeDecision(48000, 44100, 1024, 512).allowed)
        assertFalse(diagnosticResumeDecision(48000, 48000, 512, 1024).allowed)
        assertFalse(diagnosticResumeDecision(0, 48000, 1024, 512).allowed)
    }
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
        val ints = mutableMapOf<String, Int>()
        val editor = object : SharedPreferences.Editor {
            override fun putString(key: String, value: String?): SharedPreferences.Editor { values[key] = value!!; return this }
            override fun putInt(key: String, value: Int): SharedPreferences.Editor { ints[key] = value; return this }
            override fun apply() {}
            override fun commit() = true
            override fun clear() = this
            override fun remove(key: String) = this
            override fun putStringSet(key: String, value: MutableSet<String>?) = this
            override fun putLong(key: String, value: Long) = this
            override fun putFloat(key: String, value: Float) = this
            override fun putBoolean(key: String, value: Boolean) = this
        }
        val prefs = object : SharedPreferences {
            override fun edit() = editor
            override fun getString(key: String, defValue: String?) = values[key] ?: defValue
            override fun getAll(): MutableMap<String, *> = values
            override fun getStringSet(key: String, defValues: MutableSet<String>?) = null
            override fun getInt(key: String, defValue: Int) = ints[key] ?: defValue
            override fun getLong(key: String, defValue: Long) = defValue
            override fun getFloat(key: String, defValue: Float) = defValue
            override fun getBoolean(key: String, defValue: Boolean) = defValue
            override fun contains(key: String) = values.containsKey(key)
            override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
            override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        }
        assertTrue(persistDiagnosticsProfile(prefs, DiagnosticsProfile(AudioApiChoice.AAUDIO, SampleRateChoice.HZ_44100, 192)))
        assertEquals("aaudio", values[DiagnosticsProfile.KEY_API])
        assertEquals("44100", values[DiagnosticsProfile.KEY_RATE])
        assertEquals(192, ints[DiagnosticsProfile.KEY_CALLBACK_FRAMES])
        assertEquals(DiagnosticsProfile(AudioApiChoice.AAUDIO, SampleRateChoice.HZ_44100, 192), DiagnosticsProfile.load(prefs))
        assertEquals("Saved; apply after fully restarting app", profilePersistenceMessage(true))
        assertEquals("Could not save audio diagnostic profile", profilePersistenceMessage(false))
    }

    @Test fun callbackBlockDefaultsRoundTripsChoicesAndNormalizesInvalidValues() {
        assertEquals(64, DiagnosticsProfile().callbackFrames)
        assertEquals(listOf(0, 64, 128, 192, 256, 512), listOf(0, 64, 128, 192, 256, 512).map(::normalizeCallbackFrames))
        assertEquals(64, normalizeCallbackFrames(-1))
        assertEquals(64, normalizeCallbackFrames(65))
    }

    @Test fun settingsOutputSaveRetainsCallbackChoiceChangedElsewhere() {
        val latest = DiagnosticsProfile(AudioApiChoice.OBOE_DEFAULT, SampleRateChoice.HZ_48000, 192)
        assertEquals(DiagnosticsProfile(AudioApiChoice.AAUDIO, SampleRateChoice.HZ_44100, 192),
            withSelectedOutput(latest, AudioApiChoice.AAUDIO, SampleRateChoice.HZ_44100))
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
        assertEquals(ProfileApplyResult.APPLIED, applyProfileBeforeFirstOpen(beforeOpen, DiagnosticsProfile(callbackFrames = 128)) { _, _, frames -> configureCalls++; assertEquals(128, frames); true })
        assertEquals(ProfileApplyResult.ALREADY_LATCHED, applyProfileBeforeFirstOpen(beforeOpen, DiagnosticsProfile(AudioApiChoice.AAUDIO, SampleRateChoice.HZ_44100, 512)) { _, _, _ -> configureCalls++; true })
        assertTrue(isProfileConfigurationAccepted(ProfileApplyResult.ALREADY_LATCHED))
        assertFalse(isProfileConfigurationAccepted(ProfileApplyResult.NATIVE_REJECTED))
        assertEquals(1, configureCalls)
        assertEquals(DiagnosticsProfile(callbackFrames = 128), beforeOpen.activeProfile())

        val retryAfterReject = ProfileActivation()
        assertEquals(ProfileApplyResult.NATIVE_REJECTED, applyProfileBeforeFirstOpen(retryAfterReject, DiagnosticsProfile(AudioApiChoice.AAUDIO, SampleRateChoice.HZ_44100)) { _, _, _ -> false })
        assertFalse(retryAfterReject.isApplied())
        assertEquals(ProfileApplyResult.APPLIED, applyProfileBeforeFirstOpen(retryAfterReject, DiagnosticsProfile(AudioApiChoice.OPENSL_ES, SampleRateChoice.SYSTEM)) { api, rate, _ -> api == AudioApiChoice.OPENSL_ES.nativeValue && rate == SampleRateChoice.SYSTEM.nativeValue })
        assertEquals(DiagnosticsProfile(AudioApiChoice.OPENSL_ES, SampleRateChoice.SYSTEM), retryAfterReject.activeProfile())

        val nativeAlreadyOpen = ProfileActivation()
        assertEquals(ProfileApplyResult.NATIVE_REJECTED, applyProfileBeforeFirstOpen(nativeAlreadyOpen, DiagnosticsProfile(AudioApiChoice.AAUDIO, SampleRateChoice.HZ_44100)) { _, _, _ -> false })
        assertFalse(nativeAlreadyOpen.isApplied())
        val close = ManualStreamCloseState()
        assertFalse(close.isClosedByUser())
        assertTrue(allowAudioOpen(close))
        assertTrue(allowAudioStart(close))
        assertTrue(allowRecovery(close, terminalBackendState = true))
        assertFalse(allowRecovery(close, terminalBackendState = false))
        close.close()
        assertTrue(close.isClosedByUser())
        assertFalse(allowAudioOpen(close))
        assertFalse(allowAudioStart(close))
        assertFalse(allowRecovery(close, terminalBackendState = true))
        val closeOrder = mutableListOf<String>()
        val closeState = ManualStreamCloseState()
        markManualCloseBeforeNativeClose(closeState, { assertTrue(closeState.isClosedByUser()); closeOrder += "stop" }, { assertTrue(closeState.isClosedByUser()); closeOrder += "close" })
        assertEquals(listOf("stop", "close"), closeOrder)
        val stoppedState = ManualStreamCloseState()
        var nativeStopCalled = false
        markManualStopBeforeNativeStop(stoppedState) {
            assertTrue(stoppedState.isStoppedByUser())
            nativeStopCalled = true
        }
        assertTrue(nativeStopCalled)
        assertTrue(stoppedState.isStoppedByUser())
        assertFalse(stoppedState.isClosedByUser())
        assertFalse(allowAudioStart(stoppedState))
        assertTrue(allowAudioOpen(stoppedState)) // STOP retains open stream; CLOSE remains available
        assertFalse(allowRecovery(stoppedState, terminalBackendState = true))
        assertTrue(canStopOrCloseForDiagnostics(audioRecordingActive = false))
        assertFalse(canStopOrCloseForDiagnostics(audioRecordingActive = true))
        assertEquals(ProfileApplyResult.APPLIED, applyProfileBeforeFirstOpen(ProfileActivation(), DiagnosticsProfile()) { _, _, _ -> true }) // new process state
    }
}
