package com.piano.sequencer.audio

import android.content.SharedPreferences

enum class AudioApiChoice(val nativeValue: Int, val storedValue: String) {
    OBOE_DEFAULT(0, "oboe_default"), AAUDIO(1, "aaudio"), OPENSL_ES(2, "opensl_es");

    val displayName: String get() = when (this) {
        OBOE_DEFAULT -> "Oboe default (AAudio preferred, OpenSL ES fallback)"
        AAUDIO -> "AAudio"
        OPENSL_ES -> "OpenSL ES"
    }

    companion object {
        fun fromStored(value: String?): AudioApiChoice = entries.firstOrNull { it.storedValue == value } ?: OBOE_DEFAULT
    }
}

enum class SampleRateChoice(val nativeValue: Int, val storedValue: String) {
    HZ_48000(0, "48000"), HZ_44100(1, "44100"), SYSTEM(2, "system");

    val displayName: String get() = when (this) {
        HZ_48000 -> "48,000 Hz"
        HZ_44100 -> "44,100 Hz (fallback to system rate if unavailable)"
        SYSTEM -> "System rate"
    }

    companion object {
        fun fromStored(value: String?): SampleRateChoice = entries.firstOrNull { it.storedValue == value } ?: HZ_48000
    }
}

data class DiagnosticsProfile(
    val api: AudioApiChoice = AudioApiChoice.OBOE_DEFAULT,
    val sampleRate: SampleRateChoice = SampleRateChoice.HZ_48000,
) {
    fun persist(preferences: SharedPreferences): Boolean {
        return preferences.edit()
            .putString(KEY_API, api.storedValue)
            .putString(KEY_RATE, sampleRate.storedValue)
            .commit()
    }

    companion object {
        const val KEY_API = "diagnostic_audio_api"
        const val KEY_RATE = "diagnostic_sample_rate"

        fun load(preferences: SharedPreferences) = DiagnosticsProfile(
            AudioApiChoice.fromStored(preferences.getString(KEY_API, null)),
            SampleRateChoice.fromStored(preferences.getString(KEY_RATE, null)),
        )
    }
}

data class AudioDiagnosticSnapshot(
    val actualState: Int,
    val focusRetained: Boolean,
    val manuallyClosed: Boolean,
    val streamDiagnostics: String,
)

data class AudioStreamCloseResult(val closed: Boolean, val message: String, val snapshot: AudioDiagnosticSnapshot)

/** Process-local latch: profile applies once before first stream open; later requests cannot mutate active session. */
class ProfileActivation {
    private var applied = false
    private var activeProfile: DiagnosticsProfile? = null

    @Synchronized
    fun applyOnce(profile: DiagnosticsProfile, configure: () -> Boolean): ProfileApplyResult {
        if (applied) return ProfileApplyResult.ALREADY_LATCHED
        val accepted = configure()
        if (!accepted) return ProfileApplyResult.NATIVE_REJECTED
        applied = true
        activeProfile = profile
        return ProfileApplyResult.APPLIED
    }

    @Synchronized fun isApplied(): Boolean = applied
    @Synchronized fun activeProfile(): DiagnosticsProfile? = activeProfile
}

/** Manual stream close blocks all opens/starts in this process; process restart clears this state. */
class ManualStreamCloseState {
    @Volatile private var closedByUser = false
    fun isClosedByUser(): Boolean = closedByUser
    fun close() { closedByUser = true }
}

enum class ProfileApplyResult { APPLIED, ALREADY_LATCHED, STREAM_ALREADY_OPEN, NATIVE_REJECTED }

fun isProfileConfigurationAccepted(result: ProfileApplyResult): Boolean =
    result == ProfileApplyResult.APPLIED || result == ProfileApplyResult.ALREADY_LATCHED

fun applyProfileBeforeFirstOpen(
    activation: ProfileActivation,
    profile: DiagnosticsProfile,
    nativeConfigure: (api: Int, rate: Int) -> Boolean,
): ProfileApplyResult {
    return activation.applyOnce(profile) { nativeConfigure(profile.api.nativeValue, profile.sampleRate.nativeValue) }
}

data class ProfileLogSnapshot(
    val requested: DiagnosticsProfile,
    val active: DiagnosticsProfile?,
    val decision: ProfileApplyResult,
)

fun isProfileActuallyActive(decision: ProfileApplyResult): Boolean = decision == ProfileApplyResult.APPLIED

fun markManualCloseBeforeNativeClose(state: ManualStreamCloseState, stopOutput: () -> Unit, closeOutput: () -> Unit) {
    state.close()
    try {
        stopOutput()
    } finally {
        closeOutput()
    }
}

fun profileLogSnapshot(
    requested: DiagnosticsProfile,
    active: DiagnosticsProfile?,
    decision: ProfileApplyResult,
) = ProfileLogSnapshot(requested, active, decision)

fun persistDiagnosticsProfile(preferences: SharedPreferences, profile: DiagnosticsProfile): Boolean =
    profile.persist(preferences)

fun profilePersistenceMessage(saved: Boolean): String =
    if (saved) "Saved; apply after fully restarting app" else "Could not save audio diagnostic profile"

fun allowAudioOpen(manualClose: ManualStreamCloseState): Boolean = !manualClose.isClosedByUser()

fun allowRecovery(manualClose: ManualStreamCloseState, terminalBackendState: Boolean): Boolean =
    !manualClose.isClosedByUser() && terminalBackendState
