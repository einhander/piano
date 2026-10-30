package com.piano.sequencer

import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.json.JSONObject
import java.util.concurrent.Executors

/** Offline legal documents only; never shares the audio or project workers. */
internal class LicenseDialogs(private val activity: AppCompatActivity) {
    private val assets = activity.applicationContext.assets
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "LicenseReader").apply { isDaemon = true }
    }
    private val dialogs = mutableSetOf<AlertDialog>()
    private var closed = false
    private val observer = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_DESTROY) close()
    }

    private data class Component(
        val name: String, val detail: String, val files: List<String>
    )

    fun show() {
        activity.lifecycle.addObserver(observer)
        val body = column()
        val status = label(body, "Loading licenses…")
        val dialog = dialog("Licenses", body)
        load(dialog, status, {
            val known = assets.list("licenses").orEmpty().toSet()
            fun checked(file: String): String {
                require(isSafeName(file) && file in known) { "Missing or invalid document: $file" }
                return file
            }
            val root = JSONObject(read(checked("LICENSES.json")))
            require(root.getInt("schemaVersion") == 1) { "Unsupported license index version" }
            val components = root.getJSONArray("components")
            val entries = (0 until components.length()).map { index ->
                val item = components.getJSONObject(index)
                val files = item.getJSONArray("licenseFiles")
                Component(item.getString("name"), buildString {
                    append(item.getString("license"))
                    item.optString("version").takeIf { it.isNotBlank() }?.let { append("\nVersion: $it") }
                    item.optString("source").takeIf { it.isNotBlank() }?.let { append("\nSource: $it") }
                    item.optString("scopeNote").takeIf { it.isNotBlank() }?.let { append("\n$it") }
                    item.optJSONArray("coordinates")?.let { coordinates ->
                        append("\n\nDependencies:")
                        for (i in 0 until coordinates.length()) append("\n${coordinates.getString(i)}")
                    }
                }, (0 until files.length()).map { checked(files.getString(it)) })
            }
            Triple(entries, checked(root.getString("notice")), checked("SOURCE.txt"))
        }) { (entries, notice, source) ->
            body.removeAllViews()
            label(body, "Piano Sequencer\nOriginal project code: GPL-3.0-only\n\nThe original project code is free software under the GNU General Public License version 3 only. It comes WITHOUT ANY WARRANTY, including the implied warranties of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the full license for details. Third-party components retain their respective license terms below.")
            button(body, "App license — GPL-3.0-only") { showText("GPL-3.0.txt", "GPL-3.0.txt") }
            button(body, "Notices") { showText("Notices", notice) }
            button(body, "Source code and rebuild instructions") { showText("Source and rebuild instructions", source) }
            label(body, "Included software and licenses")
            entries.forEach { entry ->
                button(body, entry.name) {
                    val details = column()
                    label(details, entry.detail)
                    entry.files.forEach { file -> button(details, file) { showText(file, file) } }
                    dialog(entry.name, details)
                }
            }
        }
    }

    private fun showText(title: String, file: String) {
        val body = column()
        val loading = label(body, "Loading document…")
        val dialog = dialog(title, body)
        load(dialog, loading, {
            require(isSafeName(file) && file in assets.list("licenses").orEmpty()) {
                "Missing or invalid document: $file"
            }
            // Page on the worker too: laying out multi-megabyte notices in one TextView
            // can stall the main thread. Every character remains available offline.
            documentPages(read(file))
        }) { pages ->
            body.removeAllViews()
            val controls = LinearLayout(activity)
            body.addView(controls)
            val previous = Button(activity).apply { text = "Previous" }
            val next = Button(activity).apply { text = "Next" }
            controls.addView(previous, LinearLayout.LayoutParams(0, -2, 1f))
            controls.addView(next, LinearLayout.LayoutParams(0, -2, 1f))
            val position = label(body, "")
            val document = label(body, "")
            var page = 0
            fun render() {
                position.text = "Page ${page + 1} of ${pages.size}"
                document.text = pages[page]
                previous.isEnabled = page > 0
                next.isEnabled = page < pages.lastIndex
                (body.parent as? ScrollView)?.scrollTo(0, 0)
            }
            previous.setOnClickListener { page--; render() }
            next.setOnClickListener { page++; render() }
            render()
        }
    }

    private fun <T> load(dialog: AlertDialog, status: TextView, read: () -> T, display: (T) -> Unit) {
        worker.execute {
            val result = runCatching(read)
            activity.runOnUiThread {
                if (!closed && !activity.isFinishing && !activity.isDestroyed && dialog.isShowing) {
                    result.fold(display) { error ->
                        AppLogger.error("Licenses", error.message ?: "Could not read bundled document")
                        status.text = "Could not open this bundled document. Close this view and try again. Details: ${error.message ?: "Read failed"}"
                    }
                }
            }
        }
    }

    private fun read(file: String): String =
        assets.open("licenses/$file").bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun column() = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        val padding = (20 * resources.displayMetrics.density).toInt()
        setPadding(padding, padding / 2, padding, padding)
    }

    private fun label(body: LinearLayout, value: String) = TextView(activity).apply {
        text = value
        textSize = 16f
        setTextIsSelectable(true)
        setPadding(0, 8, 0, 16)
        body.addView(this)
    }

    private fun button(body: LinearLayout, title: String, action: () -> Unit) {
        body.addView(Button(activity).apply {
            text = title
            isAllCaps = false
            setOnClickListener { action() }
        })
    }

    private fun dialog(title: String, body: LinearLayout): AlertDialog {
        val scroll = ScrollView(activity).apply { addView(body) }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton(if (dialogs.isEmpty()) "Close" else "Back", null)
            .create()
        dialogs.add(dialog)
        dialog.setOnDismissListener {
            dialogs.remove(dialog)
            if (dialogs.isEmpty()) close()
        }
        dialog.show()
        return dialog
    }

    private fun close() {
        if (closed) return
        closed = true
        activity.lifecycle.removeObserver(observer)
        dialogs.toList().forEach { it.dismiss() }
        dialogs.clear()
        worker.shutdownNow()
    }

    companion object {
        internal fun isSafeName(file: String): Boolean =
            file.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]*\\.(txt|json)")) && !file.contains("..")

        /** Bounded TextView input, preserving UTF-16 pairs and all document characters. */
        internal fun documentPages(full: String): List<String> = buildList {
            var start = 0
            while (start < full.length) {
                var end = minOf(start + 16000, full.length)
                if (end < full.length && full[end - 1].isHighSurrogate()) end--
                add(full.substring(start, end))
                start = end
            }
            if (isEmpty()) add("This document is empty.")
        }
    }
}
