package com.piano.sequencer

import com.piano.sequencer.midi.DiagnosticPortMode
import com.piano.sequencer.midi.MidiConnectionDiagnosticState
import com.piano.sequencer.AppLogger
import java.lang.ref.WeakReference

/** App-scoped facade; live Activity remains sole owner of MidiDeviceManager. */
interface RuntimeDiagnosticsDelegate {
    fun diagnosticSnapshot(callback: (RuntimeDiagnosticsSnapshot) -> Unit)
    fun setPortMode(mode: DiagnosticPortMode, callback: (RuntimeDiagnosticsSnapshot) -> Unit)
    fun stopAudio(callback: (com.piano.sequencer.audio.AudioStreamCloseResult?) -> Unit)
    fun closeAudio(callback: (com.piano.sequencer.audio.AudioStreamCloseResult?) -> Unit)
    fun resumeAudio(callback: (Boolean, String) -> Unit)
}

data class RuntimeDiagnosticsSnapshot(
    val midi: MidiConnectionDiagnosticState,
    val audio: com.piano.sequencer.audio.AudioDiagnosticSnapshot?,
    val audioFocusGranted: Boolean,
    val startupPlan: StartupDiagnosticState? = null,
)

class RuntimeDiagnosticsCoordinator {
    @Volatile private var delegate = WeakReference<RuntimeDiagnosticsDelegate>(null)
    @Volatile var startupDiagnosticState: StartupDiagnosticState? = null
        private set
    fun updateStartupPlanState(state: StartupDiagnosticState?) { startupDiagnosticState = state }
    fun register(value: RuntimeDiagnosticsDelegate) { delegate = WeakReference(value) }
    fun unregister(value: RuntimeDiagnosticsDelegate) {
        if (delegate.get() === value) delegate.clear()
    }
    fun diagnosticSnapshot(callback: (RuntimeDiagnosticsSnapshot?) -> Unit) {
        val current = delegate.get()
        if (current == null) callback(null) else current.diagnosticSnapshot(callback)
    }
    fun setPortMode(mode: DiagnosticPortMode, callback: (RuntimeDiagnosticsSnapshot?) -> Unit) {
        val current = delegate.get()
        if (current == null) callback(null) else current.setPortMode(mode, callback)
    }
    fun stopAudio(callback: (com.piano.sequencer.audio.AudioStreamCloseResult?) -> Unit) = delegate.get()?.stopAudio(callback) ?: callback(null)
    fun closeAudio(callback: (com.piano.sequencer.audio.AudioStreamCloseResult?) -> Unit) = delegate.get()?.closeAudio(callback) ?: callback(null)
    fun resumeAudio(callback: (Boolean, String) -> Unit) = delegate.get()?.resumeAudio(callback) ?: callback(false, "MainActivity unavailable")
    fun diagnosticReport(callback: (String?) -> Unit) = diagnosticSnapshot { snapshot ->
        if (snapshot == null) { callback(null); return@diagnosticSnapshot }
        val audio = snapshot.audio
        val ports = snapshot.midi.ports.joinToString { p -> "${p.portNumber}:${p.name ?: "unnamed"}[selected=${p.selected},opened=${p.opened},connected=${p.connected},outcome=${p.outcome},ms=${p.elapsedMs}]" }
        val report = ("Runtime diagnostics: MIDI device=${snapshot.midi.deviceName ?: "none"} key=${snapshot.midi.deviceKey ?: "none"} " +
            "mode=${snapshot.midi.mode} handleOpen=${snapshot.midi.deviceHandleOpen} connected=${snapshot.midi.connected} ports=[$ports]; " +
            "audio=${audio?.actualState ?: "unavailable"} focus=${snapshot.audioFocusGranted} deviceId=${audio?.actualDeviceId ?: -1} " +
            "rate=${audio?.sampleRate ?: 0} capacity=${audio?.bufferCapacityFrames ?: 0} underruns=${audio?.underrunCount ?: -1} stream={${audio?.streamDiagnostics ?: "unavailable"}}; " +
            "Android device enumeration does not prove Telegram's active audio route; route attribution unavailable. P1 metrics not implemented.").take(4000)
        AppLogger.info("RuntimeDiagnostics", report)
        callback(report)
    }
}
