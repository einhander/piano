package com.piano.sequencer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.content.UriPermission
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.provider.OpenableColumns
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.Locale
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.piano.sequencer.midi.MidiDeviceManager
import com.piano.sequencer.midi.MidiInputReceiver
import com.piano.sequencer.midi.MidiFileTriggerController
import com.piano.sequencer.midi.noteToName
import com.piano.sequencer.project.Project
import com.piano.sequencer.project.ProjectRepository
import com.piano.sequencer.service.PlaybackService

class MainActivity : AppCompatActivity() {

    private lateinit var layout: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var c4Button: Button
    private lateinit var d4Button: Button
    private lateinit var e4Button: Button
    private lateinit var panicButton: Button
    private lateinit var projectsButton: Button
    private lateinit var aboutButton: Button
    private lateinit var settingsButton: Button
    private lateinit var instrumentsButton: Button
    private lateinit var midiStatusText: TextView
    private lateinit var midiDeviceButton: Button

    private var selectedDeviceName: String? = null

    // True when the user explicitly chose "Disconnect" — suppresses the
    // auto-reconnect in onResume so the disconnected state is sticky.
    private var userDisconnected = false

    // App log views — built when the About dialog opens, nulled on dismiss.
    // Null while the dialog is closed; refreshLog()/updateLogFolderLabel()
    // are no-ops in that case.
    private var tvLog: TextView? = null
    private var tvLogFolder: TextView? = null

    private lateinit var projectRepo: ProjectRepository

    private lateinit var midiManager: MidiDeviceManager
    private lateinit var midiInputReceiver: MidiInputReceiver

    // Live re-enumeration: notified when any MIDI device is added/removed
    // (e.g. a virtual MIDI device connected after the app started).
    private val midiDeviceCallback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(deviceInfo: MidiDeviceInfo) {
            refreshMidiStatus()
            if (!midiManager.isConnected() && !userDisconnected) {
                val prefs = getSharedPreferences("piano_prefs", MODE_PRIVATE)
                val persistedKey = prefs.getString("midi_device_key", null)
                val keyMatches = persistedKey != null &&
                    midiManager.stableKey(deviceInfo) == persistedKey
                if (persistedKey == null || keyMatches) {
                    connectToDevice(deviceInfo)
                }
            }
        }
        override fun onDeviceRemoved(deviceInfo: MidiDeviceInfo) {
            refreshMidiStatus()
            val active = midiManager.getCurrentDevice()
            if (active != null && midiManager.stableKey(active) == midiManager.stableKey(deviceInfo)) {
                midiManager.disconnect()
                if (!userDisconnected) {
                    val devices = midiManager.listDevices()
                    val other = devices.firstOrNull {
                        midiManager.stableKey(it) != midiManager.stableKey(deviceInfo)
                    }
                    if (other != null) {
                        connectToDevice(other, persist = false)
                    }
                }
            }
        }
    }

    private var playbackService: PlaybackService? = null
    private var serviceBound = false

    // SAF folder picker for the crash log destination (crash.log)
    private val logFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            LogFolder.set(this@MainActivity, it)
            updateLogFolderLabel()
            Toast.makeText(this@MainActivity, "Log folder selected", Toast.LENGTH_SHORT).show()
        }
    }

    // SAF file picker for MIDI import
    private val midiImportLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            projectRepo.importResource(it, "imported.mid") { result ->
                runOnUiThread {
                    result.onSuccess { path ->
                        Toast.makeText(this@MainActivity, "MIDI imported: $path", Toast.LENGTH_SHORT).show()
                    }.onFailure {
                        Toast.makeText(this@MainActivity, "Import failed: ${it.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? PlaybackService.PlaybackBinder
            if (binder == null) {
                Toast.makeText(this@MainActivity, "Unexpected binder type", Toast.LENGTH_SHORT).show()
                return
            }
            playbackService = binder.getService()
            serviceBound = true

            // Initialize native engine singletons BEFORE any other native calls
            if (!NativeEngineBridge.nativeInit()) {
                AppLogger.error("MainActivity", "nativeInit() failed")
                Toast.makeText(this@MainActivity, "Native init failed", Toast.LENGTH_LONG).show()
                return
            }
            AppLogger.info("MainActivity", "Native engine initialized")

            val openResult = playbackService?.openAudio()
            if (openResult != 0) {
                AppLogger.error("MainActivity", "Audio open failed: $openResult")
                Toast.makeText(this@MainActivity, "Audio open failed: $openResult", Toast.LENGTH_SHORT).show()
            } else {
                AppLogger.info("MainActivity", "Audio opened successfully")
            }
            val initResult = playbackService?.initEngine(48000, 512)
            if (initResult != true) {
                AppLogger.error("MainActivity", "Engine init failed")
                Toast.makeText(this@MainActivity, "Engine init failed", Toast.LENGTH_SHORT).show()
            } else {
                AppLogger.info("MainActivity", "Engine initialized (48000Hz, 512 buffer)")
                // Restore persisted state (SF2, polyphony, master gain,
                // channel programs) if the engine was recreated (process
                // death). If the engine survived (activity recreation) the
                // state is intact — the sfcount guard inside skips the restore.
                restorePersistedState()
                // Audio always-on: start after engine init + state restore
                Thread({
                    playbackService?.startAudio()
                    val playing = playbackService?.isAudioPlaying() == true
                    val underruns = playbackService?.getUnderrunCount() ?: 0
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        statusText.text = if (playing) "Piano Sequencer — running (underruns: $underruns)"
                                         else "Piano Sequencer — audio start failed"
                    }
                }, "AudioAutoStart").apply { isDaemon = true }.start()
            }
            refreshLog()
            // Bind trigger controller (singleton)
            MidiFileTriggerController.get(this@MainActivity).bind(this@MainActivity, playbackService!!)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBound = false
            playbackService = null
        }
    }

    private fun withService(action: (PlaybackService) -> Unit) {
        if (serviceBound && playbackService != null) {
            action(playbackService!!)
        } else {
            Toast.makeText(this, "Service not connected", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshMidiStatus() {
        if (isFinishing || isDestroyed) return
        val devices = midiManager.listDevices()
        when {
            devices.isEmpty() -> {
                midiStatusText.text = "MIDI: no devices"
                midiDeviceButton.text = "MIDI: no devices"
            }
            !midiManager.isConnected() -> {
                midiStatusText.text = "MIDI: disconnected"
                midiDeviceButton.text = "MIDI: disconnected"
            }
            else -> {
                midiManager.getCurrentDevice()?.let {
                    val name = midiManager.deviceName(it)
                    midiStatusText.text = "MIDI: $name"
                    midiDeviceButton.text = name
                }
            }
        }
    }

    private fun connectToDevice(deviceInfo: MidiDeviceInfo, persist: Boolean = true) {
        midiManager.connect(deviceInfo)
        selectedDeviceName = midiManager.deviceName(deviceInfo)
        userDisconnected = false
        if (persist) {
            // Persist the selected device's stable key.
            getSharedPreferences("piano_prefs", MODE_PRIVATE).edit().putString(
                "midi_device_key",
                midiManager.stableKey(deviceInfo)
            ).apply()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        statusText = TextView(this).apply {
            text = "Piano Sequencer"
            textSize = 20f
        }
        c4Button = Button(this).apply {
            text = "C4 (60)"
            setOnClickListener {
                withService { it.noteOn(0, 60, 100) }
            }
            setOnLongClickListener {
                withService { it.noteOff(0, 60) }
                true
            }
        }
        d4Button = Button(this).apply {
            text = "D4 (62)"
            setOnClickListener {
                withService { it.noteOn(0, 62, 100) }
            }
            setOnLongClickListener {
                withService { it.noteOff(0, 62) }
                true
            }
        }
        e4Button = Button(this).apply {
            text = "E4 (64)"
            setOnClickListener {
                withService { it.noteOn(0, 64, 100) }
            }
            setOnLongClickListener {
                withService { it.noteOff(0, 64) }
                true
            }
        }
        panicButton = Button(this).apply {
            text = "PANIC"
            setOnClickListener {
                withService { it.panic() }
                Toast.makeText(this@MainActivity, "Panic!", Toast.LENGTH_SHORT).show()
            }
        }
        projectsButton = Button(this).apply {
            text = "Projects"
            setOnClickListener { showProjectsDialog() }
        }
        aboutButton = Button(this).apply {
            text = "About"
            setOnClickListener { showAboutDialog() }
        }
        settingsButton = Button(this).apply {
            text = "Settings"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            }
        }
        instrumentsButton = Button(this).apply {
            text = "Instruments"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, InstrumentActivity::class.java))
            }
        }
        layout.addView(statusText)

        // D1/D2: horizontal pad row — c4/d4/e4/panic in one row, PANIC = 1/3 width
        val padRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            addView(c4Button, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(d4Button, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(e4Button, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(panicButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f))
        }
        layout.addView(padRow)

        layout.addView(projectsButton)
        layout.addView(aboutButton)
        layout.addView(instrumentsButton)
        layout.addView(settingsButton)
        layout.addView(Button(this).apply {
            text = "Sequensor"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, SequensorActivity::class.java))
            }
        })
        setContentView(layout)

        midiStatusText = TextView(this).apply {
            text = "MIDI: checking..."
            textSize = 14f
        }
        layout.addView(midiStatusText)

        midiDeviceButton = Button(this).apply {
            text = "MIDI: no devices"
            setOnClickListener {
                val devices = midiManager.listDevices()
                if (devices.isEmpty()) {
                    Toast.makeText(this@MainActivity, "No MIDI input devices", Toast.LENGTH_SHORT).show()
                    midiDeviceButton.text = "MIDI: no devices"
                    return@setOnClickListener
                }
                val names = devices.map { midiManager.deviceName(it) } + "Disconnect"
                androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("MIDI device")
                    .setItems(names.toTypedArray()) { _, which ->
                        if (which < devices.size) {
                            connectToDevice(devices[which])
                        } else {
                            midiManager.disconnect()
                            selectedDeviceName = null
                            userDisconnected = true
                            getSharedPreferences("piano_prefs", MODE_PRIVATE).edit()
                                .remove("midi_device_key").apply()
                        }
                    }
                    .show()
            }
        }
        layout.addView(midiDeviceButton)

        // Setup MIDI receiver callback
        midiInputReceiver = MidiInputReceiver()
        midiInputReceiver.setCallback(object : MidiInputReceiver.Callback {
            override fun onNoteOn(channel: Int, note: Int, velocity: Int) {
                // Delegate to trigger controller — consumed if mapped
                if (MidiFileTriggerController.get(this@MainActivity).onNoteOn(channel, note, velocity)) return
                // Unmapped note → forward to engine
                withService { it.sendMidiMessage(0x90 or channel, note, velocity) }
            }
            override fun onNoteOff(channel: Int, note: Int, velocity: Int) {
                // Delegate to trigger controller — consumed if mapped
                if (!MidiFileTriggerController.get(this@MainActivity).onNoteOff(channel, note, velocity)) {
                    // Unmapped note → forward to engine
                    withService { it.sendMidiMessage(0x80 or channel, note, velocity) }
                }
            }
            override fun onControlChange(channel: Int, controller: Int, value: Int) {
                withService { it.sendMidiMessage(0xB0 or channel, controller, value) }
            }
            override fun onProgramChange(channel: Int, program: Int) {
                withService { it.sendMidiMessage(0xC0 or channel, program, 0) }
            }
            override fun onPitchBend(channel: Int, value: Int) {
                withService { it.sendMidiMessage(0xE0 or channel, value and 0x7F, (value shr 7) and 0x7F) }
            }
            override fun onChannelPressure(channel: Int, value: Int) {
                withService { it.sendMidiMessage(0xD0 or channel, value, 0) }
            }
        })

        // Setup MIDI device manager
        midiManager = MidiDeviceManager(this, midiInputReceiver)
        midiManager.setListener(object : MidiDeviceManager.Listener {
            override fun onDeviceConnected(device: MidiDeviceInfo) {
                runOnUiThread {
                    val name = midiManager.deviceName(device)
                    midiStatusText.text = "MIDI: $name"
                    midiDeviceButton.text = name
                }
            }
            override fun onDeviceDisconnected() {
                runOnUiThread {
                    midiStatusText.text = "MIDI: disconnected"
                    midiDeviceButton.text = "MIDI: disconnected"
                }
            }
        })

        // Auto-connect: try persisted device key first, fall back to devices[0].
        val devices = midiManager.listDevices()
        if (devices.isNotEmpty()) {
            val prefs = getSharedPreferences("piano_prefs", MODE_PRIVATE)
            val persistedKey = prefs.getString("midi_device_key", null)
            val targetDevice = if (persistedKey != null) {
                devices.firstOrNull { midiManager.stableKey(it) == persistedKey }
            } else {
                null
            }
            if (targetDevice != null) {
                connectToDevice(targetDevice)
            } else {
                connectToDevice(devices[0], persist = false)
            }
        } else {
            midiStatusText.text = "MIDI: no devices"
        }

        // Start (not just bind) so the service survives activity re-creation —
        // no audio gap on rotation/back-forward (always-on).
        startForegroundService(Intent(this, PlaybackService::class.java))
        bindService(Intent(this, PlaybackService::class.java), serviceConnection, BIND_AUTO_CREATE)

        // Live re-enumeration: listen for MIDI device add/remove events
        midiManager.registerDeviceCallback(midiDeviceCallback, Handler(Looper.getMainLooper()))

        // Initialize project repository
        projectRepo = ProjectRepository(this)
    }

    private fun refreshLog() {
        val tv = tvLog ?: return
        val entries = AppLogger.getAll()
        tv.text = if (entries.isEmpty()) "No log entries" else entries.joinToString("\n")
    }

    private fun copyLogToClipboard() {
        val entries = AppLogger.getAll()
        if (entries.isEmpty()) {
            Toast.makeText(this, "Log is empty", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("App Log", entries.joinToString("\n")))
        Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show()
    }

    // "Log folder: <name>" for the crash.log destination (SAF tree)
    private fun updateLogFolderLabel() {
        val tv = tvLogFolder ?: return
        val uri = LogFolder.get(this)
        tv.text = if (uri == null) "Log folder: not set"
        else "Log folder: ${logFolderDisplayName(uri)}"
    }

    private fun logFolderDisplayName(uri: Uri): String =
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else "unknown"
            } ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }

    // Restore persisted state after engine (re)initialization.
    // Covers process death: the engine is a fresh instance and everything
    // (SF2, polyphony, master gain, channel programs) is reset to defaults.
    // If the engine survived (activity recreation) its state is intact — the
    // sfcount guard skips the restore. All JNI calls run on a worker thread.
    private fun restorePersistedState() {
        val svc = playbackService ?: return
        Thread({
            // A fresh engine always starts with no SoundFonts, so sfcount > 0
            // means the engine survived (activity recreation) — state intact.
            if (svc.getSoundFontCount() > 0) return@Thread
            val prefs = getSharedPreferences("piano_prefs", MODE_PRIVATE)

            // 1. Reload the last SoundFont (file on disk, path in prefs).
            var sf2Loaded = false
            val path = prefs.getString("sf2_path", null)
            if (path != null && File(path).exists()) {
                val id = svc.loadSoundFont(path)
                if (id >= 0) {
                    sf2Loaded = true
                    AppLogger.info("MainActivity", "Reloaded SF2: ${File(path).name} (synth ID: $id)")
                } else {
                    AppLogger.warn("MainActivity", "Failed to reload SF2: $path (error: $id)")
                }
            }

            // 2. Restore polyphony + master gain.
            svc.setPolyphony(prefs.getInt("polyphony", 64))
            svc.setMasterGain(prefs.getFloat("master_gain", 1.0f))

            // 3. Restore the 16 channel programs (only meaningful with an SF2).
            if (sf2Loaded) {
                var restored = 0
                for (ch in 0 until 16) {
                    val packed = prefs.getInt("chan_prog_$ch", -1)
                    if (packed >= 0 &&
                        svc.setChannelProgram(ch, packed shr 8, packed and 0xFF)) {
                        restored++
                    }
                }
                if (restored > 0) {
                    AppLogger.info("MainActivity", "Restored $restored channel program(s)")
                }
            }
        }, "StateRestore").apply { isDaemon = true }.start()
    }

    private fun saveProject() {
        val project = Project(name = "Session ${System.currentTimeMillis() / 1000}")
        projectRepo.saveProject(project) { result ->
            runOnUiThread {
                result.onSuccess {
                    Toast.makeText(this@MainActivity, "Project saved: ${project.id}", Toast.LENGTH_LONG).show()
                }.onFailure {
                    Toast.makeText(this@MainActivity, "Save failed: ${it.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun loadProject() {
        projectRepo.listProjects { projects ->
            if (projects.isEmpty()) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "No saved projects", Toast.LENGTH_SHORT).show()
                }
                return@listProjects
            }
            val project = projects[0]
            projectRepo.loadProject(project.id) { result ->
                runOnUiThread {
                    result.onSuccess { loaded ->
                        // Serialize and send to native engine
                        val json = com.piano.sequencer.project.ProjectSerializer.toJson(loaded)
                        NativeEngineBridge.nativeLoadProject(json)
                        Toast.makeText(this@MainActivity, "Loaded: ${loaded.name}", Toast.LENGTH_LONG).show()
                    }.onFailure {
                        Toast.makeText(this@MainActivity, "Load failed: ${it.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun exportMidiFile() {
        // Get recorded events from native engine and write to MIDI file
        withService { service ->
            service.exportMidiFile { filePath ->
                runOnUiThread {
                    if (filePath != null) {
                        Toast.makeText(this@MainActivity, "MIDI exported: $filePath", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this@MainActivity, "No recorded events to export", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    /**
     * "Projects" entry point: the three project actions as a house-style
     * item list dialog (same pattern as the MIDI device picker).
     */
    private fun showProjectsDialog() {
        val actions = arrayOf("Save Project", "Load Project", "Export MIDI")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Projects")
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> saveProject()
                    1 -> loadProject()
                    2 -> exportMidiFile()
                }
            }
            .show()
    }

    /**
     * "About" entry point: app info (name + version) and the app log
     * (viewer, copy, clear, log-folder picker). The log views are rebuilt
     * on each open; tvLog/tvLogFolder are nulled on dismiss.
     */
    private fun showAboutDialog() {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.card_frame)
            setPadding(16, 16, 16, 16)
        }

        card.addView(TextView(this).apply {
            text = "Piano Sequencer"
            textSize = 18f
        })

        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) {
            null
        }
        if (versionName != null) {
            card.addView(TextView(this).apply {
                text = "Version $versionName"
                textSize = 14f
            })
        }

        card.addView(TextView(this).apply {
            text = "App Log"
            textSize = 16f
            setPadding(0, 16, 0, 8)
        })

        val logButtonsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        logButtonsRow.addView(Button(this).apply {
            text = "Copy"
            setOnClickListener { copyLogToClipboard() }
        })
        logButtonsRow.addView(Button(this).apply {
            text = "Clear"
            setOnClickListener {
                AppLogger.clear()
                refreshLog()
            }
        })
        card.addView(logButtonsRow)

        val logFolderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tvLogFolder = TextView(this).apply {
            textSize = 12f
            setPadding(0, 0, 8, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        logFolderRow.addView(tvLogFolder)
        logFolderRow.addView(Button(this).apply {
            text = "Choose"
            setOnClickListener { logFolderLauncher.launch(null) }
        })
        card.addView(logFolderRow)
        updateLogFolderLabel()

        val logScrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(220)
            )
        }
        tvLog = TextView(this).apply {
            text = "No log entries"
            typeface = Typeface.MONOSPACE
            textSize = 10f
            setBackgroundColor(Color.WHITE)
            setTextColor(Color.BLACK)
            setPadding(8, 8, 8, 8)
        }
        logScrollView.addView(tvLog)
        card.addView(logScrollView)

        // Outer ScrollView keeps the dialog from overflowing on small screens.
        val wrapper = ScrollView(this)
        wrapper.addView(card)

        refreshLog()

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("About")
            .setView(wrapper)
            .setPositiveButton("Close", null)
            .show()
        dialog.setOnDismissListener {
            tvLog = null
            tvLogFolder = null
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        if (!midiManager.isConnected() && !userDisconnected) {
            val devices = midiManager.listDevices()
            if (devices.isNotEmpty()) {
                val prefs = getSharedPreferences("piano_prefs", MODE_PRIVATE)
                val persistedKey = prefs.getString("midi_device_key", null)
                val targetDevice = if (persistedKey != null) {
                    devices.firstOrNull { midiManager.stableKey(it) == persistedKey }
                } else {
                    selectedDeviceName?.let { name ->
                        devices.firstOrNull { midiManager.deviceName(it) == name }
                    }
                }
                if (targetDevice != null) {
                    connectToDevice(targetDevice)
                } else {
                    connectToDevice(devices[0], persist = false)
                }
            } else {
                midiStatusText.text = "MIDI: no devices"
                midiDeviceButton.text = "MIDI: no devices"
            }
        }
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        midiManager.unregisterDeviceCallback(midiDeviceCallback)
        midiManager.close()
        projectRepo.shutdown()
        // Do NOT call nativeShutdown() here: the native engine is a
        // process-level singleton. Destroying it on activity destroy lost the
        // loaded SoundFont every time the user left and returned (back button
        // -> new empty engine on re-create). Native resources are reclaimed
        // on process death; the engine must survive activity recreation.
    }
}
