package com.piano.sequencer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.piano.sequencer.audio.AudioDiagnosticSnapshot
import com.piano.sequencer.service.PlaybackService
import java.util.concurrent.Executors

/** Diagnostic controls never initialize or reopen the engine. All service calls use one worker. */
class AudioDiagnosticsActivity : AppCompatActivity() {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "AudioDiagnosticsUI") }
    private var service: PlaybackService? = null
    private var bound = false
    private var destroyed = false
    private var busy = false
    private var resumed = false
    private val refreshHandler = Handler(Looper.getMainLooper())
    // UI diagnostics only: never used as a playback clock. Busy reads are skipped, not queued.
    private val liveRefresh = object : Runnable {
        override fun run() {
            if (!resumed || destroyed) return
            refreshSnapshot(automatic = true)
            refreshHandler.postDelayed(this, 1000L)
        }
    }
    private var manuallyClosed = false
    private var manuallyStopped = false
    private lateinit var stop: Button
    private lateinit var active: TextView
    private lateinit var underruns: TextView
    private lateinit var status: TextView
    private lateinit var close: Button
    private lateinit var refresh: Button

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
            if (destroyed) return
            service = (binder as PlaybackService.PlaybackBinder).getService()
            refreshSnapshot()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            underruns.text = "Underruns: unavailable"
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
        underruns = label("Underruns: waiting for service…", true)
        label("Updates about once a second while this screen is open. Counter is for the current audio stream.")
        active = label("Connecting to playback service…").apply { setTextIsSelectable(true) }
        refresh = button("Refresh current stream") { refreshSnapshot() }
        val prefs = getSharedPreferences("piano_prefs", Context.MODE_PRIVATE)
        label("MIDI devices", true)
        body.addView(CheckBox(this).apply {
            text = "Load MIDI devices only when opening the MIDI chooser"
            setTextColor(getColor(R.color.ui_text))
            minHeight = (48 * resources.displayMetrics.density).toInt()
            isChecked = prefs.getBoolean("midi_lazy_loading", false)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("midi_lazy_loading", checked).apply()
            }
        })
        label("When enabled, MIDI discovery is deferred until you open the MIDI chooser. Opening it enables subsequent hotplug handling and reconnects. Saved automatically; applies when the main screen next resumes or the app starts. Changing this does not disconnect devices already connected.")
        label("Experimental CPU settings", true)
        label("May worsen audio dropouts. FluidSynth multicore uses internal worker synchronization. Each of two synth slots creates cores − 1 workers (up to 6 workers total). Fast-core affinity requests the fast-core group; actual CPU status appears in the current stream report above.")
        var savedCpu = AudioCpuConfig.read(prefs)
        fun cpuSummary(config: AudioCpuConfig) =
            "Fast-core affinity: ${if (config.fastCores) "On" else "Off"} · Synth cores: ${config.synthCores}"
        val pending = label("").apply {
            accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        label("Fast-core group affinity")
        val affinity = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            body.addView(this)
        }
        fun option(group: RadioGroup, title: String) = RadioButton(this).apply {
            id = android.view.View.generateViewId()
            text = title
            setTextColor(getColor(R.color.ui_text))
            minHeight = (48 * resources.displayMetrics.density).toInt()
            group.addView(this)
        }
        val affinityOff = option(affinity, "Off")
        val affinityOn = option(affinity, "On")
        affinity.check(if (savedCpu.fastCores) affinityOn.id else affinityOff.id)
        label("FluidSynth cores per slot")
        val cores = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            body.addView(this)
        }
        val coreOptions = listOf(1, 2, 4).associateWith { option(cores, "$it") }
        cores.check(coreOptions.getValue(savedCpu.synthCores).id)
        fun selection() = AudioCpuConfig(
            fastCores = affinity.checkedRadioButtonId == affinityOn.id,
            synthCores = coreOptions.entries.first { it.value.id == cores.checkedRadioButtonId }.key
        )
        val saveCpu = button("Save for next process restart") {}
        fun updateCpuSelection() {
            val changed = selection() != savedCpu
            saveCpu.isEnabled = changed
            pending.text = "Saved for next full process restart\n${cpuSummary(savedCpu)}" +
                if (changed) "\nSelection not saved." else "\nRunning settings: see current stream report."
        }
        affinity.setOnCheckedChangeListener { _, _ -> updateCpuSelection() }
        cores.setOnCheckedChangeListener { _, _ -> updateCpuSelection() }
        saveCpu.setOnClickListener {
            selection().persist(prefs)
            savedCpu = AudioCpuConfig.read(prefs)
            updateCpuSelection()
            pending.append("\nNot applied to running audio.")
        }
        updateCpuSelection()
        label("A full PROCESS restart is required: use Exit on the main screen, then relaunch, or Android Settings → Force stop, then relaunch. Back, reopening this screen, Stop and Close do not apply these settings.")
        label("Stream stop / close tests", true)
        label("Lower your device volume before testing. Stop leaves the stream allocated; Close releases the stream. Both preserve loaded SoundFonts and channel settings and require a full process restart for audio. Audio focus is intentionally retained, if currently held.")
        stop = button("Stop our audio stream") { confirmAction(false) }
        close = button("Close our audio stream") { confirmAction(true) }
        label("Use Exit on the main screen, then relaunch, or Android Settings → Force stop, then relaunch. Back or reopening this screen is not a full restart. API and sample-rate requests are configured in Settings.")
        status = label("").apply { accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE }
        button("Back") { finish() }
        setContentView(ScrollView(this).apply { addView(body) })
        updateControls()
        // MainActivity owns startup. Binding here never calls nativeInit or opens a stream.
        bound = bindService(Intent(this, PlaybackService::class.java), connection, Context.BIND_AUTO_CREATE)
        if (!bound) active.text = "Playback service unavailable. Return to the main screen first."
    }

    private fun onUi(action: () -> Unit) = runOnUiThread { if (!destroyed && !isFinishing) action() }

    private fun updateControls() {
        close.isEnabled = !busy && service != null && !manuallyClosed
        refresh.isEnabled = !busy && service != null
        stop.isEnabled = !busy && service != null && !manuallyStopped && !manuallyClosed
    }

    private fun showSnapshot(snapshot: AudioDiagnosticSnapshot) {
        manuallyClosed = snapshot.manuallyClosed
        manuallyStopped = snapshot.manuallyStopped
        val state = when {
            snapshot.actualState == 12 -> "Closed (verified)"
            snapshot.manuallyClosed -> "Close requested, but closed state not verified"
            else -> "Native state: ${snapshot.actualState}"
        }
        active.text = "$state\nManually stopped: ${snapshot.manuallyStopped}\nManually closed: ${snapshot.manuallyClosed}\nAudio focus retained: ${snapshot.focusRetained}\n\n${snapshot.streamDiagnostics}"
        updateControls()
    }

    private fun refreshSnapshot(automatic: Boolean = false) {
        if (!resumed || busy || destroyed) return
        val target = service ?: return
        if (automatic) {
            runTask(null) {
                val count = target.getUnderrunCount()
                onUi {
                    if (resumed && service === target) {
                        underruns.text = if (count < 0) "Underruns: unavailable" else "Underruns: $count"
                    }
                }
            }
            return
        }
        runTask(if (automatic) null else "Reading current stream…") {
            val snapshot = target.getAudioDiagnosticStreamSnapshot()
            val count = target.getUnderrunCount()
            onUi {
                if (resumed && service === target) {
                    showSnapshot(snapshot)
                    underruns.text = if (count < 0) "Underruns: unavailable" else "Underruns: $count"
                    status.text = "Current stream report refreshed."
                }
            }
        }
    }

    private fun confirmAction(closeStream: Boolean) {
        if (busy || service == null) return
        AlertDialog.Builder(this)
            .setTitle(if (closeStream) "Close our audio stream?" else "Stop our audio stream?")
            .setMessage("Lower device volume first. No audio will play until a full process restart. Loaded SoundFonts and channel settings stay in memory. Audio focus is not released by this test. Recording must be stopped first. Nothing will exit automatically.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton(if (closeStream) "Close stream" else "Stop stream") { _, _ ->
                val target = service ?: return@setPositiveButton
                if (!busy) runTask("Waiting for stream operation result…") {
                    val result = if (closeStream) target.closeAudioStreamForDiagnostics()
                        else target.stopAudioStreamForDiagnostics()
                    onUi {
                        showSnapshot(result.snapshot)
                        status.text = result.message + if (result.snapshot.manuallyClosed || result.snapshot.manuallyStopped) "\nFull process restart required for audio." else "\nCheck the current stream report."
                    }
                }
            }.show()
    }

    private fun runTask(message: String?, task: () -> Unit) {
        if (busy || destroyed) return
        busy = true
        if (message != null) status.text = message
        updateControls()
        worker.execute {
            try { task() }
            catch (e: Exception) { onUi { status.text = "Operation failed: ${e.message}. Refresh to check current state." } }
            finally { onUi { busy = false; updateControls() } }
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        refreshHandler.post(liveRefresh)
    }

    override fun onPause() {
        resumed = false
        refreshHandler.removeCallbacks(liveRefresh)
        super.onPause()
    }

    override fun onDestroy() {
        destroyed = true
        refreshHandler.removeCallbacks(liveRefresh)
        if (bound) unbindService(connection)
        service = null
        // Accepted writes/close requests finish; late UI callbacks are discarded.
        worker.shutdown()
        super.onDestroy()
    }
}
