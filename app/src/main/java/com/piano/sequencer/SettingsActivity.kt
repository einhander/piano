package com.piano.sequencer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.piano.sequencer.service.PlaybackService
import com.piano.sequencer.audio.AudioApiChoice
import com.piano.sequencer.audio.DiagnosticsProfile
import com.piano.sequencer.audio.SampleRateChoice
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

import android.widget.EditText
import android.widget.ScrollView
import android.text.InputType
import androidx.appcompat.app.AlertDialog
import com.piano.sequencer.midi.*
import java.lang.ref.WeakReference

class SettingsActivity : AppCompatActivity() {


    private val ccController get() = (application as PianoApp).instrumentEffectCcController
    private var ownsLearn = false
    private var learnUiActive = false
    private var learnDialog: AlertDialog? = null
    private val learnedSources = mutableMapOf<Pair<String, String>, String>()
    private val parameters = mutableMapOf<Pair<String, String>, EffectParameter>()
    private val reverbMappingRows = mutableMapOf<String, LinearLayout>()

    override fun onPause() {
        learnUiActive = false
        if (ownsLearn) ccController.cancelLearn()
        ownsLearn = false
        learnDialog?.dismiss()
        learnDialog = null
        super.onPause()
    }

    private fun refresh() {
        reverbMappingRows.forEach { (id, row) ->
            row.removeAllViews()
            val p = parameters["fluidsynth.reverb" to id] ?: return@forEach
            ccController.mappings().filter { it.scope == EffectCcScope.GLOBAL &&
                it.effectId == "fluidsynth.reverb" && it.parameterId == id }.forEach { m ->
                row.addView(label(mappingDescription(m)))
                row.addView(Button(this).apply {
                    text = "Edit / Unlearn mapping"
                    com.piano.sequencer.ui.UiControlStyle.apply(this)
                    setOnClickListener { editMapping(m, p) }
                })
            }
            if (row.childCount == 0) row.addView(label("No MIDI CC assigned · Shared across all instrument sets"))
        }
    }

    private fun addReverbLearnRow(controls: LinearLayout, mappings: LinearLayout, p: EffectParameter) {
        parameters["fluidsynth.reverb" to p.stableId] = p
        controls.addView(Button(this).apply {
            text = "MIDI Learn"
            com.piano.sequencer.ui.UiControlStyle.apply(this)
            contentDescription = "MIDI Learn: Global Reverb ${p.displayName}"
            minWidth = 0
            minimumWidth = 0
            maxWidth = (112 * resources.displayMetrics.density).toInt()
            minimumHeight = (48 * resources.displayMetrics.density).toInt()
            setOnClickListener { learn(p) }
        }, LinearLayout.LayoutParams(-2, -2))
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        reverbMappingRows[p.stableId] = row
        mappings.addView(row)
    }

    private lateinit var btnBrowse: Button
    private lateinit var tvRecordingFolder: TextView
    private lateinit var btnRecordingFolder: Button
    private var recordingFolderPickerOpen = false
    private var recordingFolderCheckPending = false
    private lateinit var btnUnload: Button
    private lateinit var llSf2List: LinearLayout
    private lateinit var tvSf2Empty: TextView
    private lateinit var tvSf2Count: TextView
    // Loaded SF2s: each row {id, name, path}. Backed by the native list.
    private data class LoadedSf2(val id: Int, val name: String, val path: String)
    private val loadedSf2s = mutableListOf<LoadedSf2>()
    private lateinit var tvPolyphony: TextView
    private lateinit var seekBarPolyphony: SeekBar
    private lateinit var tvMasterGain: TextView
    private lateinit var seekBarMasterGain: SeekBar
    private lateinit var tvReverb: TextView
    private lateinit var switchReverb: SwitchCompat
    private lateinit var tvChorus: TextView
    private lateinit var switchChorus: SwitchCompat
    private lateinit var tvInterps: TextView
    private lateinit var spinnerInterps: Spinner
    private lateinit var tvBufferSize: TextView
    private lateinit var seekBarBufferSize: SeekBar
    private lateinit var switchAutoTune: SwitchCompat
    private lateinit var btnPitchBendChannels: Button

    // Interpolation options (Spinner position → FluidSynth interp method).
    private val interpsValues = intArrayOf(0, 1, 4)

    private var service: PlaybackService? = null
    // m3: true while loadCurrentValues() restores the switch state — suppresses
    // the switch listener so a programmatic state change doesn't re-apply the
    // (already-current) auto-tune setting.
    private var isRestoringState = false
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
            // Can be delivered after onDestroy (cold start, user closed the
            // screen during service startup) — the executor is already shut down.
            if (isFinishing || isDestroyed) return
            service = (binder as PlaybackService.PlaybackBinder).getService()
            ccController.attach(this@SettingsActivity, service!!)
            refreshRecordingFolder()
            CompletableFuture.runAsync({
            loadCurrentValues()
        }, mainExecutor)
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service?.let { ccController.detach(this@SettingsActivity, it) }
            service = null
            refreshRecordingFolder()
        }
    }

    private val mainExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "SettingsMain").apply { isDaemon = true }
    }

    private val sf2Picker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { pickSf2File(it) }
    }

    private val recordingFolderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        recordingFolderPickerOpen = false
        if (uri == null) refreshRecordingFolder()
        else withRecordingIdle {
            try {
                RecordingFolder.save(this, uri)
            } catch (e: Exception) {
                Toast.makeText(this, "Could not save recording folder: ${e.message}", Toast.LENGTH_LONG).show()
            }
            refreshRecordingFolder()
        }
    }

    private fun refreshRecordingFolder() {
        if (!::btnRecordingFolder.isInitialized) return
        val uri = RecordingFolder.get(this)
        tvRecordingFolder.text = uri?.let(RecordingFolder::displayName)
            ?: getString(R.string.settings_recording_folder_unset)
        btnRecordingFolder.setText(if (uri == null) R.string.settings_recording_folder_choose
            else R.string.settings_recording_folder_change)
        btnRecordingFolder.isEnabled = service != null && !RecordingFolder.busy &&
            !recordingFolderPickerOpen && !recordingFolderCheckPending
    }

    // Native state reads stay off main. Recheck on picker return as recording may
    // have started while the system picker was in front of this activity.
    private fun withRecordingIdle(action: () -> Unit) {
        val svc = service ?: return
        if (recordingFolderCheckPending) return
        recordingFolderCheckPending = true
        refreshRecordingFolder()
        CompletableFuture.supplyAsync({ svc.audioRecordingState() }, mainExecutor)
            .whenComplete { state, error ->
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    recordingFolderCheckPending = false
                    if (error == null && service === svc && !RecordingFolder.busy && state != 1 && state != 2) {
                        action()
                    } else {
                        Toast.makeText(this, R.string.settings_recording_folder_busy, Toast.LENGTH_SHORT).show()
                    }
                    refreshRecordingFolder()
                }
            }
    }

    override fun onResume() {
        super.onResume()
        learnUiActive = true
        refreshRecordingFolder()
        if (service != null) CompletableFuture.runAsync({ loadCurrentValues() }, mainExecutor)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        addAudioOutputSection()

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }

        btnBrowse = findViewById(R.id.btnBrowse)
        tvRecordingFolder = findViewById(R.id.tvRecordingFolder)
        btnRecordingFolder = findViewById(R.id.btnRecordingFolder)
        btnRecordingFolder.setOnClickListener {
            withRecordingIdle {
                try {
                    recordingFolderPickerOpen = true
                    recordingFolderPicker.launch(RecordingFolder.get(this))
                } catch (e: Exception) {
                    recordingFolderPickerOpen = false
                    Toast.makeText(this, "Could not open recording folder picker: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
        refreshRecordingFolder()
        btnUnload = findViewById(R.id.btnUnload)
        llSf2List = findViewById(R.id.llSf2List)
        tvSf2Empty = findViewById(R.id.tvSf2Empty)
        tvSf2Count = findViewById(R.id.tvSf2Count)
        tvPolyphony = findViewById(R.id.tvPolyphony)
        seekBarPolyphony = findViewById(R.id.seekBarPolyphony)
        tvMasterGain = findViewById(R.id.tvMasterGain)
        seekBarMasterGain = findViewById(R.id.seekBarMasterGain)
        btnPitchBendChannels = findViewById(R.id.btnPitchBendChannels)
        tvReverb = findViewById(R.id.tvReverb)
        switchReverb = findViewById(R.id.switchReverb)
        tvChorus = findViewById(R.id.tvChorus)
        switchChorus = findViewById(R.id.switchChorus)
        tvInterps = findViewById(R.id.tvInterps)
        spinnerInterps = findViewById(R.id.spinnerInterps)
        tvBufferSize = findViewById(R.id.tvBufferSize)
        seekBarBufferSize = findViewById(R.id.seekBarBufferSize)
        switchAutoTune = findViewById(R.id.switchAutoTune)

        // Interpolation spinner (None / Linear / 4th Order → 0 / 1 / 4).
        val interpsLabels = arrayOf(
            getString(R.string.settings_interps_none),
            getString(R.string.settings_interps_linear),
            getString(R.string.settings_interps_4th)
        )
        spinnerInterps.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, interpsLabels)
        spinnerInterps.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val method = interpsValues[position]
                tvInterps.text = getString(R.string.settings_interps_value, interpsLabels[position])
                if (isFinishing || isDestroyed) return
                val svc = service ?: return
                CompletableFuture.runAsync({ svc.setInterps(method) }, mainExecutor)
                    .whenComplete { _, ex ->
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            if (ex == null) {
                                getSharedPreferences("piano_prefs", MODE_PRIVATE).edit()
                                    .putInt("interps", method).apply()
                            }
                        }
                    }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // Reverb / Chorus switches (Fix #10-11).
        var updatingReverb = false
        switchReverb.setOnCheckedChangeListener { _, checked ->
            if (updatingReverb || isRestoringState) return@setOnCheckedChangeListener
            tvReverb.text = getString(R.string.settings_reverb_value,
                if (checked) getString(R.string.settings_on) else getString(R.string.settings_off))
            if (isFinishing || isDestroyed) return@setOnCheckedChangeListener
            val svc = service ?: return@setOnCheckedChangeListener
            val on = checked
            switchReverb.isEnabled = false
            CompletableFuture.supplyAsync({
                val raw = if (on) 1f else 0f
                val success = svc.setSharedFxParameter("fluidsynth.reverb", 0, raw)
                if (success) {
                    EffectStateRestore.persistSharedParameter(this, "fluidsynth.reverb", 0, raw)
                    (application as PianoApp).instrumentEffectCcController.manualParameterChanged(0, "fluidsynth.reverb", "0", raw, com.piano.sequencer.midi.EffectCcScope.GLOBAL)
                } else AppLogger.error("SettingsActivity", "GLOBAL FluidSynth Reverb enable rejected requested=$on")
                success
            }, mainExecutor)
                .whenComplete { success, ex ->
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        switchReverb.isEnabled = true
                         if (ex != null || success != true) {
                             AppLogger.error("SettingsActivity", "GLOBAL FluidSynth Reverb enable failed requested=$on error=${ex?.message}")
                            updatingReverb = true
                            switchReverb.isChecked = !on
                            updatingReverb = false
                            tvReverb.text = "Reverb change failed · reopen Settings to refresh"
                        }
                    }
                }
        }
        switchChorus.setOnCheckedChangeListener { _, checked ->
            tvChorus.text = getString(R.string.settings_chorus_value,
                if (checked) getString(R.string.settings_on) else getString(R.string.settings_off))
            if (isFinishing || isDestroyed) return@setOnCheckedChangeListener
            val svc = service ?: return@setOnCheckedChangeListener
            val on = checked
            CompletableFuture.runAsync({ svc.setChorus(on) }, mainExecutor)
                .whenComplete { _, ex ->
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        if (ex == null) {
                            getSharedPreferences("piano_prefs", MODE_PRIVATE).edit()
                                .putInt("chorus", if (on) 1 else 0).apply()
                        }
                    }
                }
        }

        // Auto buffer size (m3): when ON, the Oboe LatencyTuner manages the buffer
        // size (2×burst..8×burst) and the seekbar below is disabled. When OFF,
        // the seekbar sets a fixed buffer size.
        switchAutoTune.setOnCheckedChangeListener { _, checked ->
            if (isRestoringState) return@setOnCheckedChangeListener
            if (isFinishing || isDestroyed) return@setOnCheckedChangeListener
            val svc = service ?: return@setOnCheckedChangeListener
            if (checked) {
                // Auto-tune ON: the LatencyTuner manages the buffer size.
                CompletableFuture.runAsync({ svc.setAutoTune(true) }, mainExecutor)
                    .whenComplete { _, ex ->
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            seekBarBufferSize.isEnabled = false
                            if (ex == null) {
                                getSharedPreferences("piano_prefs", MODE_PRIVATE).edit()
                                    .putInt("auto_tune", 1).apply()
                            }
                        }
                    }
            } else {
                // Auto-tune OFF: use the fixed buffer size from the seekbar.
                val frames = 128 + (seekBarBufferSize.progress) * 128
                CompletableFuture.runAsync({
                    svc.setAutoTune(false)
                    svc.setBufferSizeInFrames(frames)
                }, mainExecutor)
                    .whenComplete { _, ex ->
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            seekBarBufferSize.isEnabled = true
                            if (ex == null) {
                                getSharedPreferences("piano_prefs", MODE_PRIVATE).edit()
                                    .putInt("auto_tune", 0)
                                    .putInt("buffer_size", frames).apply()
                            }
                        }
                    }
            }
        }

        // Buffer size (Fix #4): 128 + progress*128 frames (128..2048). Dragging
        // the seekbar implies a manual buffer size — turn auto-tune off.
        seekBarBufferSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val frames = 128 + progress * 128
                tvBufferSize.text = getString(R.string.settings_buffer_size_value, frames)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                if (isFinishing || isDestroyed) return
                val frames = 128 + (sb?.progress ?: 0) * 128
                val svc = service ?: return@onStopTrackingTouch
                // Dragging the seekbar implies manual buffer size — turn
                // auto-tune off (the switch listener applies the new size).
                if (switchAutoTune.isChecked) {
                    switchAutoTune.isChecked = false
                    return@onStopTrackingTouch
                }
                CompletableFuture.supplyAsync({
                    svc.setBufferSizeInFrames(frames)
                }, mainExecutor)
                    .whenComplete { _, ex ->
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            if (ex == null) {
                                getSharedPreferences("piano_prefs", MODE_PRIVATE).edit()
                                    .putInt("buffer_size", frames).apply()
                            }
                        }
                    }
            }
        })

        btnBrowse.setOnClickListener { sf2Picker.launch("*/*") }

        btnUnload.setOnClickListener { unloadAllSoundFonts() }

        btnPitchBendChannels.setOnClickListener { showPitchBendChannelsDialog() }

        // Plain pref read (no service call) — the mask is a UI-side setting.
        btnPitchBendChannels.text = formatPitchBendChannels(
            getSharedPreferences("piano_prefs", MODE_PRIVATE).getInt("pitch_bend_channels", 1)
        )

        seekBarPolyphony.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                tvPolyphony.text = getString(R.string.settings_polyphony_value, progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                if (isFinishing || isDestroyed) return
                val p = sb?.progress ?: return
                val svc = service ?: return@onStopTrackingTouch
                CompletableFuture.supplyAsync({ svc.setPolyphony(p) }, mainExecutor)
                    .whenComplete { _, ex ->
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            if (ex == null) {
                                Toast.makeText(this@SettingsActivity,
                                    getString(R.string.settings_polyphony_value, p), Toast.LENGTH_SHORT).show()
                                getSharedPreferences("piano_prefs", MODE_PRIVATE).edit()
                                    .putInt("polyphony", p)
                                    .apply()
                            } else {
                                Toast.makeText(this@SettingsActivity,
                                    "Failed to set polyphony", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
            }
        })

        seekBarMasterGain.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val gain = progress / 1000f
                tvMasterGain.text = getString(R.string.settings_master_gain_value, gain)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                if (isFinishing || isDestroyed) return
                val gain = (sb?.progress ?: 0) / 1000f
                val svc = service ?: return@onStopTrackingTouch
                CompletableFuture.supplyAsync({ svc.setMasterGain(gain) }, mainExecutor)
                    .whenComplete { _, ex ->
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            if (ex == null) {
                                Toast.makeText(this@SettingsActivity,
                                    getString(R.string.settings_master_gain_value, gain), Toast.LENGTH_SHORT).show()
                                getSharedPreferences("piano_prefs", MODE_PRIVATE).edit()
                                    .putFloat("master_gain", gain)
                                    .apply()
                            } else {
                                Toast.makeText(this@SettingsActivity,
                                    "Failed to set master gain", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
            }
        })

        Intent(this, PlaybackService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onDestroy() {
        service?.let { ccController.detach(this, it) }
        mainExecutor.shutdown()
        super.onDestroy()
        try {
            unbindService(serviceConnection)
        } catch (_: Exception) {
            // ignore if not bound
        }
    }

    private fun addAudioOutputSection() {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.card_frame)
            val padding = (12 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        fun label(value: String) = TextView(this).apply {
            text = value
            setPadding(0, 8, 0, 8)
            section.addView(this)
        }
        label("Audio output").apply {
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        label("Next full process start only. Saving does not change the current stream. Requests may fall back; Audio diagnostics shows the current requested and actual configuration.")
        val saved = label("Loading saved audio output…")
        label("Desired sample rate")
        val rate = Spinner(this).apply {
            adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item,
                SampleRateChoice.entries.map { it.displayName })
            isEnabled = false
            section.addView(this)
        }
        label("Requested audio API")
        val api = Spinner(this).apply {
            adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item,
                AudioApiChoice.entries.map { it.displayName })
            isEnabled = false
            section.addView(this)
        }
        val status = label("").apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        var savedProfile: DiagnosticsProfile? = null
        fun showSaved(profile: DiagnosticsProfile) {
            savedProfile = profile
            saved.text = "Saved for next full process start:\n${profile.api.displayName}\n${profile.sampleRate.displayName}"
        }
        val selectionListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val previous = savedProfile ?: return
                val selected = DiagnosticsProfile(AudioApiChoice.entries[api.selectedItemPosition],
                    SampleRateChoice.entries[rate.selectedItemPosition])
                if (selected != previous) status.text = "Unsaved selection. Press Save to use it at the next full process start."
            }
        }
        api.onItemSelectedListener = selectionListener
        rate.onItemSelectedListener = selectionListener
        val save = Button(this).apply {
            text = "Save for next process start"
            isAllCaps = false
            isEnabled = false
            com.piano.sequencer.ui.UiControlStyle.apply(this)
            section.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        save.setOnClickListener {
            val profile = DiagnosticsProfile(AudioApiChoice.entries[api.selectedItemPosition],
                SampleRateChoice.entries[rate.selectedItemPosition])
            save.isEnabled = false
            api.isEnabled = false
            rate.isEnabled = false
            status.text = "Saving…"
            mainExecutor.execute {
                val error = runCatching {
                    check(profile.persist(getSharedPreferences("piano_prefs", MODE_PRIVATE))) {
                        "Preference write failed"
                    }
                }.exceptionOrNull()
                runOnUiThread {
                    if (isDestroyed || isFinishing) return@runOnUiThread
                    if (error == null) {
                        showSaved(profile)
                        status.text = "Saved for next process start. Current stream unchanged."
                    } else {
                        status.text = "Could not save audio output: ${error.message}. Retry before restarting."
                    }
                    save.isEnabled = true
                    api.isEnabled = true
                    rate.isEnabled = true
                }
            }
        }
        label("Use Exit on the main screen, then relaunch, or Android Settings → Force stop, then relaunch. Back or reopening Settings is not a full process restart.")
        section.addView(Button(this).apply {
            text = "Audio diagnostics"
            isAllCaps = false
            com.piano.sequencer.ui.UiControlStyle.apply(this)
            setOnClickListener { startActivity(Intent(this@SettingsActivity, AudioDiagnosticsActivity::class.java)) }
        }, LinearLayout.LayoutParams(-1, -2))
        findViewById<LinearLayout>(R.id.settingsSections).addView(section, 3)
        mainExecutor.execute {
            val result = runCatching { DiagnosticsProfile.load(getSharedPreferences("piano_prefs", MODE_PRIVATE)) }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                result.onSuccess { profile ->
                    api.setSelection(profile.api.ordinal)
                    rate.setSelection(profile.sampleRate.ordinal)
                    showSaved(profile)
                    api.isEnabled = true
                    rate.isEnabled = true
                    save.isEnabled = true
                }.onFailure { status.text = "Could not load saved audio output: ${it.message}" }
            }
        }
    }

    private fun loadCurrentValues() {
        val svc = service ?: run {
            runOnUiThread {
                Toast.makeText(this, "Service not connected", Toast.LENGTH_SHORT).show()
            }
            return
        }

        if (!svc.isEngineInitialized()) {
            runOnUiThread {
                Toast.makeText(this, "Engine not initialized", Toast.LENGTH_SHORT).show()
            }
            return
        }

        val polyphony = svc.getPolyphony()
        val gain = svc.getMasterGain()
        val gainProgress = (gain * 1000f).toInt().coerceIn(0, 2000)
        val sf2List = parseLoadedSoundFonts(svc.getLoadedSoundFonts())
        val sf2Count = svc.getSoundFontCount()
        val reverbAvailable = svc.isSharedFxAvailable("fluidsynth.reverb")
        val reverb = svc.getSharedFxParameter("fluidsynth.reverb", 0)
        val reverbControls = if (!reverbAvailable) emptyList() else (0..4).mapNotNull { id ->
            svc.getSharedFxParamInfo("fluidsynth.reverb", id)?.let { info ->
                com.piano.sequencer.midi.sharedFxParameterFromNativeInfo("fluidsynth.reverb", id, info,
                    svc.getSharedFxParameter("fluidsynth.reverb", id))?.let { sharedPresentation("fluidsynth.reverb", it) }
            }
        }
        val chorus = svc.getChorus()
        val interps = svc.getInterps()
        val bufferSize = svc.getBufferSizeInFrames()
        // m3: auto-tune is a UI-side pref (default ON = 1).
        val autoTune = getSharedPreferences("piano_prefs", MODE_PRIVATE).getInt("auto_tune", 1)

        runOnUiThread {
            seekBarPolyphony.progress = polyphony.coerceIn(1, 2048)
            tvPolyphony.text = getString(R.string.settings_polyphony_value, polyphony)

            seekBarMasterGain.progress = gainProgress
            tvMasterGain.text = getString(R.string.settings_master_gain_value, gain)

            // Reverb / Chorus (Fix #10-11).
            val reverbOn = reverb >= .5f
            isRestoringState = true
            switchReverb.isChecked = reverbOn
            isRestoringState = false
            switchReverb.isEnabled = reverbAvailable
            renderGlobalReverb(reverbControls)
            tvReverb.text = getString(R.string.settings_reverb_value,
                if (reverbOn) getString(R.string.settings_on) else getString(R.string.settings_off))
            val chorusOn = chorus != 0
            switchChorus.isChecked = chorusOn
            tvChorus.text = getString(R.string.settings_chorus_value,
                if (chorusOn) getString(R.string.settings_on) else getString(R.string.settings_off))

            // Interpolation (Fix #12): map the method (0/1/4) to a spinner position.
            val interpsPos = interpsValues.indexOf(interps).coerceAtLeast(0)
            spinnerInterps.setSelection(interpsPos)
            tvInterps.text = getString(R.string.settings_interps_value,
                arrayOf(
                    getString(R.string.settings_interps_none),
                    getString(R.string.settings_interps_linear),
                    getString(R.string.settings_interps_4th)
                )[interpsPos])

            // Buffer size (Fix #4): map frames (128..2048) to a seekbar progress.
            val bufProgress = ((bufferSize - 128) / 128).coerceIn(0, 15)
            seekBarBufferSize.progress = bufProgress
            tvBufferSize.text = getString(R.string.settings_buffer_size_value,
                128 + bufProgress * 128)

            // m3: restore the auto-tune switch + seekbar enabled state
            // (suppress the listener so the programmatic change doesn't
            // re-apply the already-current setting).
            isRestoringState = true
            switchAutoTune.isChecked = (autoTune != 0)
            seekBarBufferSize.isEnabled = (autoTune == 0)
            isRestoringState = false

            updateSf2List(sf2List, sf2Count)
        }
    }

    private fun renderGlobalReverb(parameters: List<EffectParameter>) {
        val controls = findViewById<LinearLayout>(R.id.globalReverbControls)
        controls.removeAllViews()
        val enableRow = findViewById<LinearLayout>(R.id.globalReverbEnableRow)
        // Preserve the XML state label and switch; replace only the Learn button.
        while (enableRow.childCount > 2) enableRow.removeViewAt(2)
        val enableMappings = findViewById<LinearLayout>(R.id.globalReverbEnableMappings)
        enableMappings.removeAllViews()
        reverbMappingRows.clear()
        this.parameters.clear()
        parameters.forEach { p ->
            if (p.stableId == "0") {
                addReverbLearnRow(enableRow, enableMappings, p)
                return@forEach
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val setting = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            controls.addView(row, LinearLayout.LayoutParams(-1, -2))
            row.addView(setting, LinearLayout.LayoutParams(0, -2, 1f))
            val label = TextView(this).apply { text = "${p.displayName}: ${instrumentValueLabel(p, p.value.rawValue)}" }
            setting.addView(label)
            setting.addView(SeekBar(this).apply {
                minimumHeight = (48 * resources.displayMetrics.density).toInt()
                contentDescription = "Global Reverb ${p.displayName}"
                max = 1000
                progress = instrumentParameterProgress(p, p.value.rawValue)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    private var tracking = false
                    private fun save(progress: Int) {
                        val svc = service ?: return
                        val raw = instrumentParameterValue(p, progress)
                        isEnabled = false
                        CompletableFuture.supplyAsync({
                            val ok = svc.setSharedFxParameter("fluidsynth.reverb", p.stableId.toInt(), raw)
                            if (ok) {
                                EffectStateRestore.persistSharedParameter(this@SettingsActivity, "fluidsynth.reverb", p.stableId.toInt(), raw)
                                (application as PianoApp).instrumentEffectCcController.manualParameterChanged(0, "fluidsynth.reverb", p.stableId, raw, com.piano.sequencer.midi.EffectCcScope.GLOBAL)
                            } else AppLogger.error("SettingsActivity", "GLOBAL FluidSynth Reverb rejected parameter=${p.stableId} value=$raw")
                            ok
                        }, mainExecutor).whenComplete { ok, error ->
                            runOnUiThread {
                                if (isFinishing || isDestroyed) return@runOnUiThread
                                isEnabled = true
                                if (ok != true || error != null) {
                                    tvReverb.text = "GLOBAL Reverb change failed · copy App Log"
                                    CompletableFuture.runAsync({ loadCurrentValues() }, mainExecutor)
                                    Toast.makeText(this@SettingsActivity, "Global Reverb change failed. Copy App Log.", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                    override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                        label.text = "${p.displayName}: ${instrumentValueLabel(p, instrumentParameterValue(p, progress))}"
                        if (fromUser && !tracking) save(progress)
                    }
                    override fun onStartTrackingTouch(bar: SeekBar?) { tracking = true }
                    override fun onStopTrackingTouch(bar: SeekBar?) { tracking = false; save(bar?.progress ?: 0) }
                })
            })
            addReverbLearnRow(row, controls, p)
        }
        refresh()
    }

    private fun label(value: String) = TextView(this).apply { text = value; textSize = 14f }
    private fun mappingDescription(m: EffectCcMapping): String {
        val name = parameters[m.effectId to m.parameterId]?.displayName ?: "Parameter ${m.parameterId}"
        return "${m.source ?: "Any source"} · MIDI ch ${m.inputChannel?.plus(1) ?: "Any"} · CC ${m.controller}\nShared · all instrument sets · Reverb · $name\nRaw ${m.minRaw}–${m.maxRaw}${if (m.invert) " · Inverted" else ""} · Soft takeover · ${if (m.disposition == CcDisposition.PASS) "Pass-through" else "Consume"}"
    }

    private fun learn(p: EffectParameter) {
        if (ccController.isLearning()) {
            Toast.makeText(this, "Finish current MIDI Learn first.", Toast.LENGTH_SHORT).show()
            return
        }
        val owner = WeakReference(this)
        val started = ccController.startLearn(globalReverbLearnTemplate(p)) { result ->
            owner.get()?.runOnUiThread {
                owner.get()?.takeUnless { it.isFinishing || it.isDestroyed || !it.learnUiActive }?.onLearnResult(result)
            }
        }
        if (!started) {
            AlertDialog.Builder(this).setMessage("Finish MIDI-file trigger learning first.").setPositiveButton("OK", null).show()
            return
        }
        ownsLearn = true
        learnDialog?.dismiss()
        learnDialog = AlertDialog.Builder(this).setTitle(p.displayName)
            .setMessage("Waiting for MIDI CC…\nMove a controller within 10 seconds.")
            .setNegativeButton("Cancel") { _, _ -> ownsLearn = false; ccController.cancelLearn() }
            .setOnCancelListener { ownsLearn = false; ccController.cancelLearn() }.show()
    }

    private fun onLearnResult(result: EffectCcLearnResult) {
        ownsLearn = false
        learnDialog?.dismiss()
        learnDialog = null
        val mapping = result.mapping
        mapping?.source?.let { learnedSources[mapping.effectId to mapping.parameterId] = it }
        if (mapping != null && triggerConflicts(mapping).isNotEmpty()) {
            // Capture auto-adds when no effect conflict exists. Roll it back until
            // the user explicitly approves deleting overlapping trigger cells.
            if (result.conflict == null) ccController.remove(mapping)
            saveMapping(mapping)
            refresh()
            return
        }
        when {
            result.timedOut -> AlertDialog.Builder(this).setMessage("No MIDI CC received. Learning timed out.").setPositiveButton("OK", null).show()
            mapping != null && result.conflict != null -> confirmConflict(mapping, result.conflict.existing)
            mapping != null -> {
                refresh()
                AlertDialog.Builder(this).setTitle("MIDI CC captured").setMessage(mappingDescription(mapping) + ccWarning(mapping.controller))
                    .setPositiveButton("Edit mapping") { _, _ -> editMapping(mapping, parameters[mapping.effectId to mapping.parameterId]) }
                    .setNegativeButton("Done", null).show()
            }
            else -> AlertDialog.Builder(this).setMessage("Effect MIDI Learn cancelled.").setPositiveButton("OK", null).show()
        }
    }

    private fun confirmConflict(incoming: EffectCcMapping, existing: EffectCcMapping, old: EffectCcMapping? = null) {
        AlertDialog.Builder(this).setTitle("MIDI CC already mapped")
            .setMessage("New mapping:\n${mappingDescription(incoming)}\n\nExisting mapping:\n${mappingDescription(existing)}\n\nReplace the existing mapping?${ccWarning(incoming.controller)}")
            .setNegativeButton("Cancel", null).setPositiveButton("Replace") { _, _ ->
                saveMapping(incoming, old, existing)
            }.show()
    }

    private fun saveMapping(mapping: EffectCcMapping, old: EffectCcMapping? = null, replacing: EffectCcMapping? = null,
                            approvedTriggers: List<SequencerCell>? = null) {
        // Check collisions before removing an edited mapping; Cancel preserves it.
        val conflict = ccController.mappings().firstOrNull { it != old && it != replacing && it.controller == mapping.controller &&
            (it.source == null || mapping.source == null || it.source == mapping.source) &&
            (it.inputChannel == null || mapping.inputChannel == null || it.inputChannel == mapping.inputChannel) }
        if (conflict != null) { confirmConflict(mapping, conflict, old); return }
        val triggers = triggerConflicts(mapping)
        if (triggers.isNotEmpty() && (approvedTriggers == null || triggers.any { it !in approvedTriggers })) {
            AlertDialog.Builder(this).setTitle("CC assigned to sequencer triggers")
                .setMessage("Effect mapping:\n${mappingDescription(mapping)}\n\nOverlapping trigger cells:\n" +
                    triggers.joinToString("\n") { cell ->
                        val content = if (cell.mode == "CHORD") "Chord" else cell.filePath.substringAfterLast('/').ifEmpty { "Empty cell" }
                        "Cell ${cell.id} · $content · CC ${cell.ccNumber} · ${cell.triggerSource ?: "Any source"} · MIDI ch ${if (cell.triggerChannel < 0) "Any" else cell.triggerChannel + 1}"
                    } + "\n\nReplace deletes ALL listed sequencer cells and their trigger assignments, then saves this effect mapping. Cancel keeps existing trigger cells and any old edited effect mapping." + ccWarning(mapping.controller))
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Replace") { _, _ -> saveMapping(mapping, old, replacing, triggers) }.show()
            return
        }
        val raced = if (replacing == null) ccController.replace(mapping, old)
            else synchronized(ccController) {
                ccController.replace(ccController.mappings().filter { it != old && it != replacing } + mapping)
            }
        if (raced != null) {
            confirmConflict(mapping, raced.existing, old)
        } else {
            // Never delete cells before the effect-to-effect transaction succeeds.
            val store = MidiFileMappingStore.get(this)
            triggers.filter { it in approvedTriggers.orEmpty() }.forEach { cell ->
                // Do not remove a cell edited since the confirmation snapshot.
                if (store.get(cell.id) == cell) store.remove(cell.id)
            }
        }
        refresh()
    }

    private fun triggerConflicts(mapping: EffectCcMapping): List<SequencerCell> =
        MidiFileMappingStore.get(this).all().filter { cell ->
            cell.triggerType == TRIGGER_CC && cell.ccNumber == mapping.controller &&
                (cell.triggerSource == null || mapping.source == null || cell.triggerSource == mapping.source) &&
                (cell.triggerChannel < 0 || mapping.inputChannel == null || cell.triggerChannel == mapping.inputChannel)
        }


    private fun editMapping(m: EffectCcMapping, p: EffectParameter?) {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        form.addView(label(mappingDescription(m)))
        fun selector(title: String, options: List<String>, selected: Int): Spinner {
            form.addView(label(title))
            return Spinner(this).apply {
                adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, options)
                setSelection(selected); form.addView(this)
            }
        }
        val source = m.source ?: learnedSources[m.effectId to m.parameterId]
        val sourceSelector = selector("Input source", listOf("Any source") + listOfNotNull(source?.let { "Learned source: $it" }), if (m.source == null) 0 else 1)
        if (source == null) form.addView(label("Learn again to capture a specific source/device."))
        val channelSelector = selector("Input MIDI channel", listOf("Any channel") + (1..16).map { "Channel $it" }, m.inputChannel?.plus(1) ?: 0)
        fun field(title: String, value: Float): EditText {
            form.addView(label(title))
            return EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                setText(value.toString()); form.addView(this)
            }
        }
        val minimum = field("Minimum raw value", m.minRaw)
        val maximum = field("Maximum raw value", m.maxRaw)
        val invert = SwitchCompat(this).apply { text = "Invert"; isChecked = m.invert; form.addView(this) }
        selector("Pickup mode", listOf("Soft takeover (current)"), 0)
        form.addView(label("Only Soft takeover is supported. Move the controller to the current value before changes apply. Immediate mode is unavailable."))
        val consume = SwitchCompat(this).apply { text = "Consume MIDI CC (off: Pass-through)"; isChecked = m.disposition == CcDisposition.CONSUME; form.addView(this) }
        val warning = label(ccWarning(m.controller).trim()).apply { visibility = if (text.isEmpty()) View.GONE else View.VISIBLE }
        form.addView(warning)
        consume.setOnCheckedChangeListener { _, _ -> warning.text = ccWarning(m.controller).trim() }
        form.addView(label("Pass-through also sends the CC to the instrument. Consume uses it only for this effect."))
        val dialog = AlertDialog.Builder(this).setTitle("Edit MIDI CC mapping")
            .setView(ScrollView(this).apply { addView(form) }).setNegativeButton("Cancel", null)
            .setNeutralButton("Unlearn") { _, _ -> ccController.remove(m); refresh() }
            .setPositiveButton("Save", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val lo = minimum.text.toString().toFloatOrNull()
                val hi = maximum.text.toString().toFloatOrNull()
                if (lo == null || hi == null || !lo.isFinite() || !hi.isFinite() || hi <= lo ||
                    (p != null && (lo < p.minimum || hi > p.maximum))) {
                    maximum.error = if (p != null) "Use ${p.minimum}–${p.maximum}; maximum must exceed minimum" else "Enter finite values; maximum must exceed minimum"
                } else {
                    dialog.dismiss()
                    saveMapping(m.copy(minRaw = lo, maxRaw = hi, invert = invert.isChecked,
                        source = if (sourceSelector.selectedItemPosition == 0) null else source,
                        inputChannel = (channelSelector.selectedItemPosition - 1).takeIf { it >= 0 },
                        disposition = if (consume.isChecked) CcDisposition.CONSUME else CcDisposition.PASS), m)
                }
            }
        }
        dialog.show()
    }
    private fun ccWarning(cc: Int): String = if (cc in setOf(0, 32, 6, 38, 7, 10, 11, 64) || cc in 120..127)
        "\n\nWarning: CC $cc can change bank, data entry, volume, pan, expression, sustain or channel mode. Pass-through may also affect the instrument; Consume prevents its normal instrument action. Choose deliberately." else ""
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun pickSf2File(uri: Uri) {
        val fileName = uri.lastPathSegment
            ?.takeIf { it.contains('/') }
            ?.substringAfterLast('/')
            ?: uri.lastPathSegment
            ?: "soundfont.sf2"

        if (!fileName.endsWith(".sf2", ignoreCase = true) &&
            !fileName.endsWith(".sf3", ignoreCase = true)) {
            Toast.makeText(this, "Only .sf2 and .sf3 files allowed", Toast.LENGTH_SHORT).show()
            return
        }

        btnBrowse.isEnabled = false

        val svc = service
        val loadFuture = CompletableFuture.supplyAsync(java.util.function.Supplier<Int> {
            try {
                val destDir = getExternalFilesDir(null)
                    ?: throw IOException("Cannot access external files directory")

                val destFile = File(destDir, fileName)

                contentResolver.openInputStream(uri).use { input ->
                    if (input == null) throw IOException("Cannot read selected file")
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                }

                val result = svc?.loadSoundFont(destFile.absolutePath) ?: -1
                if (result < 0) {
                    AppLogger.error("SettingsActivity", "Failed to load SF2: $fileName (error: $result)")
                } else {
                    AppLogger.info("SettingsActivity", "Loaded SF2: $fileName (synth ID: $result)")
                }
                result
            } catch (e: Exception) {
                AppLogger.error("SettingsActivity", "SF2 load exception: ${e.message}")
                -1
            }
        }, mainExecutor)
        loadFuture.whenComplete { result, ex ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                btnBrowse.isEnabled = true
                val svcLocal = service
                if (ex == null && result >= 0 && svcLocal != null) {
                    // Refresh the loaded-SF2 list + count from the engine on a
                    // worker thread (JNI must not run on main), then update UI.
                    refreshSf2List(svcLocal)
                    Toast.makeText(this@SettingsActivity, "SoundFont loaded", Toast.LENGTH_SHORT).show()
                } else {
                    val msg = ex?.message ?: getString(R.string.settings_sf2_load_failed)
                    Toast.makeText(this@SettingsActivity, msg, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Reload a single SF2 by its id (arbitrary unload). Worker thread for the
    // JNI call; UI + persistence updated back on main.
    private fun unloadSoundFont(sf: LoadedSf2) {
        val svc = service ?: return
        CompletableFuture.supplyAsync(java.util.function.Supplier<Boolean> {
            try {
                val ok = svc.unloadSoundFont(sf.id)
                if (!ok) {
                    AppLogger.error("SettingsActivity", "Failed to unload SF2: ${sf.name} (id=${sf.id})")
                } else {
                    AppLogger.info("SettingsActivity", "Unloaded SF2: ${sf.name} (id=${sf.id})")
                }
                ok
            } catch (e: Exception) {
                AppLogger.error("SettingsActivity", "SF2 unload exception: ${e.message}")
                false
            }
        }, mainExecutor).whenComplete { ok, ex ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (ok && ex == null) {
                    refreshSf2List(svc)
                } else {
                    Toast.makeText(this@SettingsActivity, getString(R.string.settings_sf2_unload_failed),
                        Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun unloadAllSoundFonts() {
        btnUnload.isEnabled = false

        val svc = service ?: run { btnUnload.isEnabled = true; return }
        CompletableFuture.runAsync({
            svc.unloadSoundFonts()
        }, mainExecutor)
        .whenComplete { _, ex ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                btnUnload.isEnabled = true
                if (ex == null) {
                    AppLogger.info("SettingsActivity", "Unloaded all SoundFonts")
                    refreshSf2List(svc)
                } else {
                    AppLogger.error("SettingsActivity", "Failed to unload SoundFont: ${ex.message}")
                    Toast.makeText(this@SettingsActivity, getString(R.string.settings_sf2_unload_failed),
                        Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Read the current loaded-SF2 list + count from the engine (worker thread)
    // and push them to the UI adapter + persistence.
    private fun refreshSf2List(svc: PlaybackService) {
        CompletableFuture.supplyAsync(java.util.function.Supplier<Pair<List<LoadedSf2>, Int>?> {
            try {
                val list = parseLoadedSoundFonts(svc.getLoadedSoundFonts())
                val count = svc.getSoundFontCount()
                Pair(list, count)
            } catch (e: Exception) {
                AppLogger.error("SettingsActivity", "SF2 list read exception: ${e.message}")
                null
            }
        }, mainExecutor).whenComplete { pair, _ ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (pair != null) {
                    updateSf2List(pair.first, pair.second)
                    persistSf2Paths(pair.first)
                }
            }
        }
    }

    // Replace the in-memory list + count label, then rebuild the row views.
    // Rows are inflated into a LinearLayout (NOT a ListView) because the
    // settings screen scrolls via a ScrollView, and a ListView with
    // wrap_content height inside a ScrollView only measures its first row.
    private fun updateSf2List(list: List<LoadedSf2>, count: Int) {
        loadedSf2s.clear()
        loadedSf2s.addAll(list)
        llSf2List.removeAllViews()
        for (sf in loadedSf2s) {
            val row = layoutInflater.inflate(R.layout.sf2_list_item, llSf2List, false)
            row.findViewById<TextView>(R.id.tvSf2ItemName).text = sf.name
            row.findViewById<Button>(R.id.btnSf2ItemUnload).setOnClickListener {
                unloadSoundFont(sf)
            }
            llSf2List.addView(row)
        }
        tvSf2Empty.visibility = if (loadedSf2s.isEmpty()) View.VISIBLE else View.GONE
        tvSf2Count.text = getString(R.string.settings_sf2_loaded_count, count)
    }

    // Persist the loaded SF2 paths so they are reloaded after process death.
    // Stored as a JSON array under "sf2_paths" (multi-SF2). The legacy single
    // "sf2_path" key is cleared to avoid a stale single-path restore.
    private fun persistSf2Paths(list: List<LoadedSf2>) {
        val arr = JSONArray()
        for (sf in list) {
            arr.put(sf.path)
        }
        getSharedPreferences("piano_prefs", MODE_PRIVATE).edit()
            .remove("sf2_path")
            .putString("sf2_paths", arr.toString())
            .apply()
    }

    // Parse the JSON array returned by nativeGetLoadedSoundFonts:
    // [{"id":int,"path":"..."}, ...] → LoadedSf2 list (name = path basename).
    private fun parseLoadedSoundFonts(json: String): List<LoadedSf2> {
        val result = mutableListOf<LoadedSf2>()
        if (json.isBlank()) return result
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.getInt("id")
                val path = o.getString("path")
                val name = File(path).name
                result.add(LoadedSf2(id, name, path))
            }
        } catch (e: Exception) {
            AppLogger.error("SettingsActivity", "SF2 JSON parse exception: ${e.message}")
        }
        return result
    }

    // 16-channel multi-choice dialog for the pitch bend / mod / breath
    // fallback mask (bit i set = channel i active). Plain pref write — this
    // is a UI-side setting, not an engine setting.
    private fun showPitchBendChannelsDialog() {
        val prefs = getSharedPreferences("piano_prefs", MODE_PRIVATE)
        val currentMask = prefs.getInt("pitch_bend_channels", 1)
        val checked = BooleanArray(16) { (currentMask shr it) and 1 == 1 }
        val items = Array(16) { getString(R.string.settings_channel_n, it + 1) }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.settings_pitch_bend_channels_label)
            .setMultiChoiceItems(items, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("OK") { _, _ ->
                val mask = (0 until 16).fold(0) { acc, ch ->
                    if (checked[ch]) acc or (1 shl ch) else acc
                }
                if (mask == 0) {
                    Toast.makeText(this, R.string.settings_pitch_bend_channels_none_selected, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                prefs.edit().putInt("pitch_bend_channels", mask).apply()
                btnPitchBendChannels.text = formatPitchBendChannels(mask)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // "Channel 1" / "Channels 1, 5" / "All channels"
    private fun formatPitchBendChannels(mask: Int): String {
        val channels = (0 until 16).filter { (mask shr it) and 1 == 1 }
        return when {
            channels.isEmpty() -> getString(R.string.settings_channel_n, 1)
            channels.size == 16 -> getString(R.string.settings_all_channels)
            channels.size == 1 -> getString(R.string.settings_channel_n, channels[0] + 1)
            else -> getString(R.string.settings_channels_list,
                channels.joinToString(", ") { (it + 1).toString() })
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        navigateUpTo(Intent(this, MainActivity::class.java))
        return true
    }
}
