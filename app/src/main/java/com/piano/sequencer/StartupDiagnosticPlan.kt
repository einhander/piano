package com.piano.sequencer

import android.content.SharedPreferences
import com.piano.sequencer.midi.DiagnosticPortMode

enum class StartupOrder { AUDIO_THEN_MIDI, MIDI_THEN_AUDIO, MIDI_ONLY, AUDIO_ONLY }
data class StartupDiagnosticPlan(val order: StartupOrder, val ports: DiagnosticPortMode, val deviceKey: String? = null)
data class StartupDiagnosticState(val plan: StartupDiagnosticPlan, val phase: String = "pending", val streamCreated: Boolean = false)

/** Preferences boundary and synchronous policy shared by production and tests. */
object StartupDiagnosticPolicy {
    fun blocksMidiAutomation(state: StartupDiagnosticState?) = state != null
    fun allowsAudioAction(state: StartupDiagnosticState?) = state?.plan?.order != StartupOrder.MIDI_ONLY
    fun <T> selectMidiTarget(deviceKey: String?, devices: List<T>, stableKey: (T) -> String): T? =
        if (deviceKey == null) devices.firstOrNull() else devices.firstOrNull { stableKey(it) == deviceKey }
    fun startAudioInitially(plan: StartupDiagnosticPlan?) = plan == null || plan.order == StartupOrder.AUDIO_THEN_MIDI || plan.order == StartupOrder.AUDIO_ONLY
    fun startMidiInitially(plan: StartupDiagnosticPlan?) = plan?.order == StartupOrder.MIDI_ONLY || plan?.order == StartupOrder.MIDI_THEN_AUDIO
    fun onAudioCompleted(plan: StartupDiagnosticPlan?) = plan?.order == StartupOrder.AUDIO_THEN_MIDI
    fun onMidiCompleted(plan: StartupDiagnosticPlan?) = plan?.order == StartupOrder.MIDI_THEN_AUDIO
    fun startupClaim(plan: StartupDiagnosticPlan?, consumeSucceeded: Boolean, alreadyClaimed: Boolean): Boolean =
        plan != null && consumeSucceeded && !alreadyClaimed
    fun isSamePlan(state: StartupDiagnosticState?, plan: StartupDiagnosticPlan?) = state?.plan === plan
}
object StartupDiagnosticPlanStore {
    private const val ORDER = "startup_diagnostic_order"
    private const val PORTS = "startup_diagnostic_ports"
    private const val KEY = "startup_diagnostic_device_key"
    fun load(prefs: SharedPreferences): StartupDiagnosticPlan? {
        val order = prefs.getString(ORDER, null)?.let { runCatching { StartupOrder.valueOf(it) }.getOrNull() } ?: return null
        val ports = prefs.getString(PORTS, null)?.let { runCatching { DiagnosticPortMode.valueOf(it) }.getOrNull() } ?: DiagnosticPortMode.ALL
        return StartupDiagnosticPlan(order, if (order == StartupOrder.AUDIO_ONLY) DiagnosticPortMode.NONE else ports, prefs.getString(KEY, null))
    }
    fun schedule(prefs: SharedPreferences, plan: StartupDiagnosticPlan) = prefs.edit().putString(ORDER, plan.order.name).putString(PORTS, (if (plan.order == StartupOrder.AUDIO_ONLY) DiagnosticPortMode.NONE else plan.ports).name).putString(KEY, plan.deviceKey).commit()
    fun cancel(prefs: SharedPreferences): Boolean = prefs.edit().remove(ORDER).remove(PORTS).remove(KEY).commit()
}
