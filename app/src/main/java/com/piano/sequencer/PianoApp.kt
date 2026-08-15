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

    override fun onCreate() {
        super.onCreate()
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