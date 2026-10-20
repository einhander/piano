package com.piano.sequencer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

/** Shared destination; keeps the existing preference and persisted SAF grants. */
object RecordingFolder {
    private const val KEY = "recording_folder_uri"

    // Covers MainActivity's document creation/start/stop windows before native state changes.
    @Volatile var busy = false
        internal set

    fun get(context: Context): Uri? = context.getSharedPreferences("piano_prefs", Context.MODE_PRIVATE)
        .getString(KEY, null)?.let(Uri::parse)

    fun save(context: Context, uri: Uri) {
        context.contentResolver.takePersistableUriPermission(uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        context.getSharedPreferences("piano_prefs", Context.MODE_PRIVATE).edit()
            .putString(KEY, uri.toString()).apply()
    }

    fun displayName(uri: Uri): String = runCatching {
        DocumentsContract.getTreeDocumentId(uri).substringAfterLast(':').ifBlank { "Recording folder" }
    }.getOrDefault("Recording folder")
}
