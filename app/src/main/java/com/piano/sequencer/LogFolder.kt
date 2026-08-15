package com.piano.sequencer

import android.content.Context
import android.net.Uri

/**
 * Persists the user-selected folder (SAF tree URI) where crash.log is
 * written. The URI is stored as a string in private app prefs; the actual
 * read/write permission is held by the system via
 * takePersistableUriPermission (see MainActivity's logFolderLauncher).
 */
object LogFolder {

    private const val PREFS = "piano_prefs"
    private const val KEY_URI = "log_folder_uri"

    fun get(context: Context): Uri? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_URI, null)
            ?.let { Uri.parse(it) }

    fun set(context: Context, uri: Uri?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .apply {
                if (uri == null) remove(KEY_URI) else putString(KEY_URI, uri.toString())
            }
            .apply()
    }
}