package com.piano.sequencer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.piano.sequencer.audio.AudioApiChoice
import com.piano.sequencer.audio.AudioDiagnosticSnapshot
import com.piano.sequencer.audio.DiagnosticsProfile
import com.piano.sequencer.audio.SampleRateChoice
import com.piano.sequencer.service.PlaybackService
import java.util.concurrent.Executors

/** Diagnostic controls never initialize or reopen the engine. All service calls use one worker. */
class AudioDiagnosticsActivity : AppCompatActivity() {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "AudioDiagnosticsUI") }
    private var service: PlaybackService? = null
    private var bound = false
    private var destroyed = false
    private var busy = false
    private var profileLoaded = false
    private var manuallyClosed = false
    private lateinit var active: TextView
    private lateinit var saved: TextView
    private lateinit var status: TextView
    private lateinit var api: Spinner
    private lateinit var rate: Spinner
    private lateinit var close: Button
    private lateinit var refresh: Button
    private lateinit var save: Button

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
            if (destroyed) return
            service = (binder as PlaybackService.PlaybackBinder).getService()
            refreshSnapshot()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            active.text = "Service disconnected. Current stream state unavailable."
            updateControls()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(getColor(R.color.ui_background))
        }
        fun label(value: String, heading: Boolean = false) = TextView(this).apply {
            text = value
            textSize = if (heading) 20f else 15f
            setTextColor(getColor(R.color.ui_text))
            setPadding(0, 12, 0, 12)
            if (heading) setTypeface(typeface, android.graphics.Typeface.BOLD)
            body.addView(this)
        }
        fun button(value: String, action: () -> Unit) = Button(this).apply {
            text = value
            isAllCaps = false
            com.piano.sequencer.ui.UiControlStyle.apply(this)
            setOnClickListener { action() }
            body.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        label("Audio diagnostics", true)
        label("Current process", true)
        active = label("Connecting to playback service…").apply { setTextIsSelectable(true) }
        refresh = button("Refresh current stream") { refreshSnapshot() }
        label("Lower your device volume before testing. Closing only our audio stream preserves loaded SoundFonts and channel settings. Audio focus is intentionally retained for this stream-only test, if currently held.")
        close = button("Close our audio stream") { confirmClose() }
        label("Next process start", true)
        saved = label("Loading saved profile…")
        label("Desired sample rate")
        rate = Spinner(this).apply {
            adapter = ArrayAdapter(this@AudioDiagnosticsActivity, android.R.layout.simple_spinner_dropdown_item,
                SampleRateChoice.entries.map { it.displayName })
            body.addView(this)
        }
        label("Requested audio API")
        api = Spinner(this).apply {
            adapter = ArrayAdapter(this@AudioDiagnosticsActivity, android.R.layout.simple_spinner_dropdown_item,
                AudioApiChoice.entries.map { it.displayName })
            body.addView(this)
        }
        label("Requests may fall back; the current stream report above shows the actual API and rate. Saving does not change this process. Use Exit on the main screen, then relaunch, or Android Settings → Force stop, then relaunch. Back or reopening this screen is not a full restart.")
        save = button("Save for next process start") { saveProfile() }
        status = label("").apply { accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE }
        button("Back") { finish() }
        setContentView(ScrollView(this).apply { addView(body) })
        updateControls()
        worker.execute {
            try {
                val profile = DiagnosticsProfile.load(getSharedPreferences("piano_prefs", MODE_PRIVATE))
                onUi {
                    api.setSelection(profile.api.ordinal)
                    rate.setSelection(profile.sampleRate.ordinal)
                    showSaved(profile)
                    profileLoaded = true
                    updateControls()
                }
            } catch (e: Exception) { onUi { status.text = "Could not load profile: ${e.message}" } }
        }
        // MainActivity owns startup. Binding here never calls nativeInit or opens a stream.
        bound = bindService(Intent(this, PlaybackService::class.java), connection, Context.BIND_AUTO_CREATE)
        if (!bound) active.text = "Playback service unavailable. Return to the main screen first."
    }

    private fun onUi(action: () -> Unit) = runOnUiThread { if (!destroyed && !isFinishing) action() }

    private fun updateControls() {
        close.isEnabled = !busy && service != null && !manuallyClosed
        refresh.isEnabled = !busy && service != null
        save.isEnabled = !busy && profileLoaded
        api.isEnabled = !busy && profileLoaded
        rate.isEnabled = !busy && profileLoaded
    }

    private fun showSaved(profile: DiagnosticsProfile) {
        saved.text = "Saved for next process start:\n${profile.api.displayName}\n${profile.sampleRate.displayName}"
    }

    private fun showSnapshot(snapshot: AudioDiagnosticSnapshot) {
        manuallyClosed = snapshot.manuallyClosed
        val state = when {
            snapshot.actualState == 12 -> "Closed (verified)"
            snapshot.manuallyClosed -> "Close requested, but closed state not verified"
            else -> "Native state: ${snapshot.actualState}"
        }
        active.text = "$state\nManual close latch: ${snapshot.manuallyClosed}\nAudio focus retained: ${snapshot.focusRetained}\n\n${snapshot.streamDiagnostics}"
        updateControls()
    }

    private fun refreshSnapshot() {
        val target = service ?: return
        runTask("Reading current stream…") {
            val snapshot = target.getAudioDiagnosticStreamSnapshot()
            onUi { showSnapshot(snapshot); status.text = "Current stream report refreshed." }
        }
    }

    private fun confirmClose() {
        if (busy || service == null) return
        AlertDialog.Builder(this)
            .setTitle("Close our audio stream?")
            .setMessage("Lower device volume first. No audio will play until a full process restart. Loaded SoundFonts and channel settings stay in memory. Audio focus is not released by this test. Recording must be stopped first. Nothing will exit automatically.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Close stream") { _, _ ->
                val target = service ?: return@setPositiveButton
                if (!busy) runTask("Closing stream; waiting for verified result…") {
                    val result = target.closeAudioStreamForDiagnostics()
                    onUi {
                        showSnapshot(result.snapshot)
                        status.text = result.message + if (result.closed) "\nFull process restart required for audio." else "\nCheck the current stream report."
                    }
                }
            }.show()
    }

    private fun saveProfile() {
        val profile = DiagnosticsProfile(AudioApiChoice.entries[api.selectedItemPosition],
            SampleRateChoice.entries[rate.selectedItemPosition])
        runTask("Saving next-start profile…") {
            check(profile.persist(getSharedPreferences("piano_prefs", MODE_PRIVATE))) {
                "Could not save audio diagnostic profile"
            }
            onUi { showSaved(profile); status.text = "Saved for next process start. Current process unchanged." }
        }
    }

    private fun runTask(message: String, task: () -> Unit) {
        if (busy || destroyed) return
        busy = true
        status.text = message
        updateControls()
        worker.execute {
            try { task() }
            catch (e: Exception) { onUi { status.text = "Operation failed: ${e.message}. Refresh to check current state." } }
            finally { onUi { busy = false; updateControls() } }
        }
    }

    override fun onDestroy() {
        destroyed = true
        if (bound) unbindService(connection)
        service = null
        // Accepted writes/close requests finish; late UI callbacks are discarded.
        worker.shutdown()
        super.onDestroy()
    }
}
