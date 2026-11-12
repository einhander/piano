package com.piano.sequencer

import android.app.Application
import android.provider.DocumentsContract
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Application entry point. Installs a global uncaught-exception handler that
 * persists the full stack trace to crash.log and mirrors it into AppLogger
 * (visible in the in-app log viewer on next launch), so crashes can be
 * diagnosed on device without adb/logcat.
 *
 * crash.log location: the user-selected folder (SAF tree URI, see
 * LogFolder / MainActivity's logFolderLauncher) when configured, otherwise
 * the app's private filesDir.
 */
class PianoApp : Application() {
    @Volatile var activeStartupDiagnosticPlan: StartupDiagnosticState? = null
        private set
    data class StartupClaim(val state: StartupDiagnosticState?, val executeActions: Boolean)
    @Volatile private var startupPlanConsumed = false
    @Volatile private var startupClaimed = false
    private val startupWorker by lazy { java.util.concurrent.Executors.newSingleThreadExecutor() }
    fun consumeStartupDiagnosticPlan(callback: (StartupClaim) -> Unit) {
        synchronized(this) {
            if (startupPlanConsumed) {
                startupWorker.execute { android.os.Handler(android.os.Looper.getMainLooper()).post { callback(StartupClaim(activeStartupDiagnosticPlan, false)) } }
                return
            }
            startupWorker.execute {
                synchronized(this) {
                    if (startupPlanConsumed) {
                        android.os.Handler(android.os.Looper.getMainLooper()).post { callback(StartupClaim(activeStartupDiagnosticPlan, false)) }
                        return@execute
                    }
                }
                val prefs = getSharedPreferences("piano_prefs", MODE_PRIVATE)
                val plan = StartupDiagnosticPlanStore.load(prefs)
                val success = plan == null || runCatching { StartupDiagnosticPlanStore.cancel(prefs) }.getOrDefault(false)
                val state = when {
                    plan == null -> activeStartupDiagnosticPlan
                    !success -> StartupDiagnosticState(plan, phase = "store-consume-failed")
                    activeStartupDiagnosticPlan != null -> activeStartupDiagnosticPlan!!.copy(phase = "diagnostic-recreated")
                    else -> StartupDiagnosticState(plan)
                }
                synchronized(this) {
                    val firstClaim = !startupClaimed && plan != null && success
                    startupPlanConsumed = true
                    if (firstClaim) startupClaimed = true
                    if (state != null) activeStartupDiagnosticPlan = state
                    runtimeDiagnosticsCoordinator.updateStartupPlanState(state)
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        callback(StartupClaim(state, firstClaim))
                    }
                }
            }
        }
    }
    @Synchronized fun updateStartupDiagnosticState(phase: String, streamCreated: Boolean) {
        activeStartupDiagnosticPlan?.let { activeStartupDiagnosticPlan = it.copy(phase = phase, streamCreated = it.streamCreated || streamCreated) }
        runtimeDiagnosticsCoordinator.updateStartupPlanState(activeStartupDiagnosticPlan)
    }
    val runtimeDiagnosticsCoordinator = RuntimeDiagnosticsCoordinator()
    lateinit var actionMidiMapper: com.piano.sequencer.midi.ActionMidiMapper
        private set
    @Volatile private var actionMidiLearnListener: ((com.piano.sequencer.midi.ActionMidiMapper.Result) -> Unit)? = null
    fun setActionMidiLearnListener(listener: ((com.piano.sequencer.midi.ActionMidiMapper.Result) -> Unit)?) { actionMidiLearnListener = listener }
    fun notifyActionMidiLearn(result: com.piano.sequencer.midi.ActionMidiMapper.Result) { actionMidiLearnListener?.invoke(result) }
    lateinit var instrumentEffectCcController: com.piano.sequencer.midi.InstrumentEffectCcController
        private set
    @Volatile private var playbackServiceAlive = false
    @Volatile private var playbackService: com.piano.sequencer.service.PlaybackService? = null
    @Volatile private var exitAfterServiceStop: (() -> Unit)? = null

    @Synchronized fun playbackServiceCreated(service: com.piano.sequencer.service.PlaybackService) {
        playbackServiceAlive = true
        playbackService = service
    }
    @Synchronized fun playbackServiceDestroyed() {
        playbackServiceAlive = false
        playbackService = null
        exitAfterServiceStop?.also { exitAfterServiceStop = null }?.invoke()
    }
    @Synchronized fun stopServiceThen(onStopped: () -> Unit) {
        if (!playbackServiceAlive) { onStopped(); return }
        exitAfterServiceStop = onStopped
        val service = playbackService
        if (service == null) {
            exitAfterServiceStop = null
            onStopped()
            return
        }
        service.prepareForExplicitExit {
            stopService(android.content.Intent(this, com.piano.sequencer.service.PlaybackService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        instrumentEffectCcController = com.piano.sequencer.midi.InstrumentEffectCcController(this)
        actionMidiMapper = com.piano.sequencer.midi.ActionMidiMapper(getSharedPreferences("piano_prefs", MODE_PRIVATE))
        com.piano.sequencer.midi.MidiFileLearnState.onStarting = { instrumentEffectCcController.cancelLearnWithResult() }
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sb = StringBuilder()
                sb.append("[")
                    .append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date()))
                    .append("] FATAL on thread ").append(thread.name).append("\n")
                appendThrowable(sb, throwable)
                writeCrashLog(sb.toString())
                // Full trace into the in-app log viewer (no adb needed to read it)
                AppLogger.error("CrashHandler", sb.toString())
            } catch (_: Exception) {
                // Never let the handler itself crash
            }
            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, throwable)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }

    private fun writeCrashLog(trace: String) {
        // SharedPreferences reads are thread-safe; no startup cache needed.
        LogFolder.get(this)?.let { treeUri ->
            try {
                // crash.log inside the user-selected folder; "w" mode
                // (API 26+) creates the file if missing, truncates if present.
                val docId = DocumentsContract.getTreeDocumentId(treeUri) + "/crash.log"
                val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                contentResolver.openOutputStream(docUri, "w")?.use {
                    it.write(trace.toByteArray())
                }
                return
            } catch (_: Exception) {
                // Permission revoked or provider failure — fall back below
            }
        }
        File(filesDir, "crash.log").writeText(trace)
    }

    private fun appendThrowable(sb: StringBuilder, throwable: Throwable) {
        var current: Throwable? = throwable
        while (current != null) {
            if (current !== throwable) sb.append("Caused by: ")
            val sw = StringWriter()
            current.printStackTrace(PrintWriter(sw))
            sb.append(sw)
            current = current.cause
        }
    }
}
