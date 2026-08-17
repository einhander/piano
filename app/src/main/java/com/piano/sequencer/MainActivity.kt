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
import android.provider.DocumentsContract
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
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.piano.sequencer.midi.MidiDeviceManager
import com.piano.sequencer.midi.MidiFileAssignment
import com.piano.sequencer.midi.MidiFileLearnState
import com.piano.sequencer.midi.MidiFileMappingStore
import com.piano.sequencer.midi.MidiInputReceiver
import com.piano.sequencer.midi.NoteToggleStateMachine
import com.piano.sequencer.midi.noteToName
import com.piano.sequencer.project.Project
import com.piano.sequencer.project.ProjectRepository
import com.piano.sequencer.service.PlaybackService
import com.piano.sequencer.ui.MidiFilesPanel

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

    // ── MIDI file key mapping (Phase 2) ──

    /** M2: single store instance created here, passed to MidiFilesPanel. */
    private lateinit var midiFileStore: MidiFileMappingStore
    private val noteStateMachine = NoteToggleStateMachine()

    /** m2: ConcurrentHashMap for thread-safe concurrent access from MIDI binder thread. */
    private val noteSlotMap = ConcurrentHashMap<Int, Int>()
    private var nextSlotIndex = 0

    /** m8: track loaded-file-per-slot to skip redundant loads. */
    private val loadedFilePerSlot = ConcurrentHashMap<Int, String>()

    /** MIDI files UI panel. */
    private lateinit var midiFilesPanel: MidiFilesPanel

    /** SAF folder picker for recorded MIDI output. */
    private val recordFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            getSharedPreferences("piano_prefs", MODE_PRIVATE).edit()
                .putString("record_folder_uri", uri.toString())
                .apply()
            Toast.makeText(this@MainActivity, "Record folder selected", Toast.LENGTH_SHORT).show()
            // M4: complete pending export if one was waiting
            completePendingExport(uri)
        } else {
            // M4(a): user cancelled picker — clear pending, toast, reset UI
            val pending = pendingExport?.takeIf { pe ->
                pendingExport = null
                true
            }
            if (pending != null) {
                Toast.makeText(this@MainActivity, "Export not saved (folder not selected)", Toast.LENGTH_SHORT).show()
                runOnUiThread {
                    midiFilesPanel.updateRecordUI(false, pending.eventCount)
                }
            }
            // item 4(b): clear pending-export flag
            pendingExportFlag = false
            pendingExportFlagSaved = false
        }
    }

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

    // MIDI file import for the MidiFilesPanel (copy to midi_files/ dir)
    private val midiFileImportLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { sourceUri ->
            Thread({
                try {
                    val midiDir = File(this@MainActivity.getExternalFilesDir(null), "midi_files")
                    if (!midiDir.exists()) midiDir.mkdirs()
                    // Deduplicate: find a unique name
                    var baseName = try {
                        contentResolver.query(sourceUri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) cursor.getString(0) else null
                        } ?: "imported.mid"
                    } catch (_: Exception) {
                        "imported.mid"
                    }
                    if (!baseName.endsWith(".mid", ignoreCase = true)) baseName += ".mid"
                    var destFile = File(midiDir, baseName)
                    var counter = 1
                    while (destFile.exists()) {
                        destFile = File(midiDir, "${baseName.removeSuffix(".mid")}_$counter.mid")
                        counter++
                    }
                    contentResolver.openInputStream(sourceUri)?.use { input ->
                        FileOutputStream(destFile).use { output ->
                            input.copyTo(output)
                        }
                    } ?: throw IOException("Could not open input stream for $sourceUri")
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Imported: ${destFile.name}", Toast.LENGTH_SHORT).show()
                        midiFilesPanel.refresh()
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Import failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                    AppLogger.warn("MainActivity", "MIDI import failed: ${e.message}")
                }
            }, "MidiFileImport").apply { isDaemon = true }.start()
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
            // Wire up MIDI files panel
            midiFilesPanel.bindService(playbackService!!)
            setupPanelCallbacks()
            midiFilesPanel.refresh()
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
        layout.addView(c4Button)
        layout.addView(d4Button)
        layout.addView(e4Button)
        layout.addView(panicButton)
        layout.addView(projectsButton)
        layout.addView(aboutButton)
        layout.addView(instrumentsButton)
        layout.addView(settingsButton)
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

        // ── MIDI Files panel (Phase 2) ──
        // M2: single store instance, passed to panel
        midiFileStore = MidiFileMappingStore(
            getSharedPreferences("piano_prefs", MODE_PRIVATE)
        )
        midiFilesPanel = MidiFilesPanel(this, midiFileStore)
        layout.addView(midiFilesPanel)

        // Setup MIDI receiver callback
        midiInputReceiver = MidiInputReceiver()
        midiInputReceiver.setCallback(object : MidiInputReceiver.Callback {
            override fun onNoteOn(channel: Int, note: Int, velocity: Int) {
                // D1: channel-agnostic mapping
                // 1. If learn state active → capture note
                if (MidiFileLearnState.getState() == MidiFileLearnState.State.LEARNING) {
                    MidiFileLearnState.captureNote(note)
                    return
                }
                // 2. If note is mapped (any channel) → run toggle state machine
                val assignment = midiFileStore.get(note)
                if (assignment != null) {
                    // D2: mapped note is CONSUMED — do NOT forward to engine
                    // M3: tri-state result (TOGGLE_ON / TOGGLE_OFF / IGNORED)
                    val result = noteStateMachine.noteOn(note)
                    when (result) {
                        NoteToggleStateMachine.Result.TOGGLE_ON -> {
                            // Start slot (worker thread)
                            withService { svc ->
                                triggerSlot(svc, note, assignment)
                            }
                        }
                        NoteToggleStateMachine.Result.TOGGLE_OFF -> {
                            // item 3: STOP the slot (keep mapping), don't FREE
                            withService { _ ->
                                stopSlotForNote(note)
                            }
                        }
                        NoteToggleStateMachine.Result.IGNORED -> {
                            // Key repeat — nothing to do
                        }
                    }
                    return
                }
                // 3. Unmapped note → forward to engine as today
                withService { it.sendMidiMessage(0x90 or channel, note, velocity) }
            }
            override fun onNoteOff(channel: Int, note: Int, velocity: Int) {
                // Feed state machine (no stop — loop keeps playing)
                noteStateMachine.noteOff(note)
                // D2: mapped note is CONSUMED — do NOT forward to engine (its
                // note-on never reached the synth; a forwarded note-off could cut
                // a sequencer note on the same channel)
                if (midiFileStore.get(note) != null) return
                // Unmapped notes forward as today
                withService { it.sendMidiMessage(0x80 or channel, note, velocity) }
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
        midiFilesPanel.refresh()
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
        // m7: cancel any active learn timeout
        midiFilesPanel.cancelLearnTimeout()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // item 4(b): persist pending-export flag across rotation
        pendingExportFlagSaved = pendingExportFlag
        outState.putBoolean("pendingExportFlag", pendingExportFlagSaved)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        // item 4(b): restore pending-export flag after rotation
        pendingExportFlag = savedInstanceState.getBoolean("pendingExportFlag", false)
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

    // ── Phase 2: MIDI file slot management ──

    /**
     * Get or allocate a slot for a note.
     * D6: 16 slots; round-robin for 17th+ mapping.
     * item 2: slot 15 reserved for test-play → allocate from 0-14 only.
     */
    private fun allocateSlot(note: Int): Int {
        return noteSlotMap.getOrPut(note) {
            val slot = nextSlotIndex % 15  // item 2: reserve slot 15 for test-play
            nextSlotIndex++
            slot
        }
    }

    /**
     * Trigger a slot: load if not loaded, then start/stop.
     * D2: worker thread for all JNI calls.
     * Handles -4 (busy) with up to 3 retries at 50ms apart.
     * m8: skip load when same file already in slot (loadedFilePerSlot).
     */
    private fun triggerSlot(service: PlaybackService, note: Int, assignment: MidiFileAssignment) {
        Thread({
            val slot = allocateSlot(note)
            val isPlaying = service.isMidiFileSlotPlaying(slot)

            if (!isPlaying) {
                // m8: skip load if same file already loaded in this slot
                val alreadyLoaded = loadedFilePerSlot[slot] == assignment.filePath
                if (!alreadyLoaded) {
                    // Load if not already loaded (lazy load on first trigger)
                    var loadResult = service.loadMidiFileSlot(
                        slot, assignment.filePath, assignment.tempo, assignment.loop
                    )
                    // Handle -4 (busy) with retries
                    var retries = 0
                    while (loadResult == -4 && retries < 3) {
                        Thread.sleep(50)
                        loadResult = service.loadMidiFileSlot(
                            slot, assignment.filePath, assignment.tempo, assignment.loop
                        )
                        retries++
                    }
                    if (loadResult != 0) {
                        val msg = when (loadResult) {
                            -1 -> "Invalid file or engine"
                            -2 -> "File too long (>8192 events)"
                            -3 -> "Command queue full"
                            -4 -> "Slot busy (after retries)"
                            else -> "Error $loadResult"
                        }
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                        }
                        return@Thread
                    }
                    // m8: track loaded file
                    loadedFilePerSlot[slot] = assignment.filePath
                }
            }

            // Start/stop slot
            if (service.isMidiFileSlotPlaying(slot)) {
                service.stopMidiFileSlot(slot)
                noteStateMachine.stopPlaying(note)
            } else {
                service.startMidiFileSlot(slot)
            }
        }, "MidiFileTrigger-$note").apply { isDaemon = true }.start()
    }

    /** Free a slot when a mapping is removed or file is deleted. */
    private fun freeSlotForNote(note: Int) {
        val slot = noteSlotMap.remove(note)
        if (slot != null) {
            // m1: reset toggle machine so next press after free is TOGGLE_ON
            noteStateMachine.stopPlaying(note)
            withService { it.freeMidiFileSlot(slot) }
            loadedFilePerSlot.remove(slot)
        }
    }

    /** item 3: stop a slot without freeing it (keeps mapping + loaded file). */
    private fun stopSlotForNote(note: Int) {
        val slot = noteSlotMap[note] ?: return
        withService { it.stopMidiFileSlot(slot) }
        noteStateMachine.stopPlaying(note)
    }

    // ── Phase 2: MIDI file panel callbacks ──

    private fun setupPanelCallbacks() {
        midiFilesPanel.onImportClick = {
            midiFileImportLauncher.launch("audio/midi")
        }

        midiFilesPanel.onRecordClick = {
            // item 6: run the whole record toggle on a worker thread
            Thread({
                try {
                    val svc = playbackService ?: run {
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Service not connected", Toast.LENGTH_SHORT).show()
                        }
                        return@Thread
                    }
                    val isRec = svc.isRecording()
                    if (isRec) {
                        // Stop recording → write to SAF folder
                        svc.stopRecording()
                        val eventCount = svc.getRecordedEventCount()
                        if (eventCount > 0) {
                            // Check if SAF folder chosen
                            val prefs = getSharedPreferences("piano_prefs", MODE_PRIVATE)
                            val recordUriStr = prefs.getString("record_folder_uri", null)
if (recordUriStr == null) {
                                // No SAF folder chosen → trigger picker
                                // M4: hold pending export until picker returns
                                pendingExport = PendingExport(svc, eventCount)
                                pendingExportFlag = true
                                pendingExportFlagSaved = false
                                runOnUiThread {
                                    recordFolderLauncher.launch(null)
                                }
                            } else {
                                writeRecordedMidiFileToUri(svc, eventCount, Uri.parse(recordUriStr))
                            }
                        }
                        runOnUiThread {
                            midiFilesPanel.updateRecordUI(false, eventCount)
                        }
                    } else {
                        // Start recording
                        svc.startRecording()
                        runOnUiThread {
                            midiFilesPanel.updateRecordUI(true, 0)
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Record error: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }, "MidiRecordToggle").apply { isDaemon = true }.start()
        }

        midiFilesPanel.onNoteTrigger = { note, filePath, loop, tempo ->
            val assignment = MidiFileAssignment(note, filePath, loop, tempo)
            withService { svc ->
                triggerSlot(svc, note, assignment)
            }
        }

        midiFilesPanel.onTestPlay = { _note, filePath, loop, tempo ->
            // item 8: test-play with load result check, generation counter,
            // stop-on-second-tap, worker-thread JNI
            Thread({
                try {
                    val svc = playbackService ?: return@Thread
                    val gen = testPlayGeneration + 1
                    testPlayGeneration = gen
                    if (testPlayPlaying) {
                        // Stop test-play (second tap)
                        svc.stopMidiFileSlot(testPlaySlot)
                        testPlayPlaying = false
                        runOnUiThread {
                            midiFilesPanel.updateTestPlayUI(false)
                        }
                        return@Thread
                    }
                    // item 8(a): check load result, retry on -4
                    var loadResult = svc.loadMidiFileSlot(testPlaySlot, filePath, tempo, loop)
                    var retries = 0
                    while (loadResult == -4 && retries < 3) {
                        Thread.sleep(50)
                        loadResult = svc.loadMidiFileSlot(testPlaySlot, filePath, tempo, loop)
                        retries++
                    }
                    if (loadResult != 0) {
                        val msg = when (loadResult) {
                            -1 -> "Invalid file or engine"
                            -2 -> "File too long (>8192 events)"
                            -3 -> "Command queue full"
                            -4 -> "Slot busy (after retries)"
                            else -> "Error $loadResult"
                        }
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                        }
                        return@Thread
                    }
                    svc.startMidiFileSlot(testPlaySlot)
                    testPlayPlaying = true
                    runOnUiThread {
                        midiFilesPanel.updateTestPlayUI(true)
                    }
                    // item 8(b): auto-stop only if still current generation
                    // item 8(d): worker thread, not main looper
                    Thread {
                        Thread.sleep(3000)
                        if (testPlayGeneration == gen) {
                            svc.stopMidiFileSlot(testPlaySlot)
                            testPlayPlaying = false
                            runOnUiThread {
                                midiFilesPanel.updateTestPlayUI(false)
                            }
                        }
                    }.apply { isDaemon = true }.start()
                } catch (_: Exception) {}
            }, "MidiTestPlay").apply { isDaemon = true }.start()
        }

        midiFilesPanel.onFileDelete = { filePath ->
            // Free any slot mapped to this file
            for ((note, assignment) in midiFileStore.all()) {
                if (assignment.filePath == filePath) {
                    midiFileStore.remove(note)
                    freeSlotForNote(note)
                }
            }
            midiFilesPanel.refresh()
        }

        midiFilesPanel.onSettingChange = { note, loop, tempo ->
            val assignment = midiFileStore.get(note)
            if (assignment != null) {
                val slot = noteSlotMap[note]
                if (slot != null) {
                    withService { it.setMidiFileSlotLoop(slot, loop) }
                    withService { it.setMidiFileSlotTempo(slot, tempo) }
                    // m8: if the file path changed (re-learn), clear loaded-file cache
                    loadedFilePerSlot.remove(slot)
                }
            }
        }

        midiFilesPanel.onNoteLearned = { note, _filePath, _loop, _ ->
            // Allocate a slot for this note
            allocateSlot(note)
            // Clear any stale loaded-file tracking for this slot
            val slot = noteSlotMap[note]
            if (slot != null) {
                loadedFilePerSlot.remove(slot)
            }
            midiFilesPanel.refresh()
        }

        midiFilesPanel.onMappingRemove = { note ->
            freeSlotForNote(note)
        }

        midiFilesPanel.onRefresh = {
            midiFilesPanel.refresh()
        }
    }

    /** M4: Pending export for first-time SAF picker flow. */

    /**
     * Holds a pending export until the SAF folder picker returns.
     * Created when user stops recording without a saved folder URI.
     * Cleared when picker returns (success → export, cancel → discard).
     */
    private data class PendingExport(
        val service: PlaybackService,
        val eventCount: Int
    )

    private var pendingExport: PendingExport? = null

    /** item 4(b): persist pending-export flag across rotation/process death. */
    private var pendingExportFlag = false
    private var pendingExportFlagSaved = false

    /** item 8: test-play state — scratch slot, generation counter, playing flag. */
    private var testPlayGeneration = 0
    private var testPlayPlaying = false
    private val testPlaySlot = 15  // item 2: reserved for test-play

    /**
     * Complete a pending export with the chosen SAF URI.
     * Called from recordFolderLauncher callback.
     */
    private fun completePendingExport(uri: Uri) {
        val pending = pendingExport
        if (pending != null) {
            pendingExport = null
            pendingExportFlag = false
            pendingExportFlagSaved = false
            writeRecordedMidiFileToUri(pending.service, pending.eventCount, uri)
            return
        }
        // 4b: field lost (rotation/process death) but the flag survived — reconstruct
        // from engine state (recorder keeps events until the next startRecording;
        // getRecordedEventCount is JNI -> worker thread).
        if (pendingExportFlag) {
            pendingExportFlag = false
            pendingExportFlagSaved = false
            val svc = playbackService ?: return
            Thread {
                val count = svc.getRecordedEventCount()
                if (count > 0) {
                    writeRecordedMidiFileToUri(svc, count, uri)
                } else {
                    runOnUiThread {
                        Toast.makeText(this, "Nothing to export (recording lost)", Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        }
    }

    /**
     * Write recorded MIDI to a temp file, then copy to SAF folder.
     * M5: uses actual BPM and PPQ from transport state.
     */
    private fun writeRecordedMidiFileToUri(service: PlaybackService, eventCount: Int, uri: Uri) {
        Thread({
            try {
                val tempDir = File(filesDir, "midi_files")
                if (!tempDir.exists()) tempDir.mkdirs()
                // item 9: unique temp name to avoid concurrent export collisions
                val tempFileName = "rec_${System.currentTimeMillis()}.mid"
                val tempFile = File(tempDir, tempFileName)

                // M5: get actual transport BPM and PPQ
                val bpm = service.getBPM()
                val ppq = service.getPpq()
                val tempoUs = (60_000_000.0 / bpm).toInt()

                val success = service.writeRecordedMidiFile(
                    tempFile.absolutePath, ppq, tempoUs
                )

                if (success) {
                    // Copy temp file to SAF folder
                    val copied = copyToSaf(tempFile, uri)
                    runOnUiThread {
                        midiFilesPanel.updateRecordUI(false, eventCount)
                        if (copied) {
                            Toast.makeText(this@MainActivity,
                                "Recorded $eventCount events", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@MainActivity, "Export failed (SAF copy)", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Export failed", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }, "MidiRecordExport").apply { isDaemon = true }.start()
    }

    /**
     * Copy a file to a SAF folder using DocumentFile API.
     * m5: returns Boolean (true = copied, false = failed).
     * m5: uses unique temp name with timestamp to avoid collisions.
     */
    private fun copyToSaf(tempFile: File, destUri: Uri): Boolean {
        try {
            val docFile = DocumentsContract.buildDocumentUriUsingTree(
                destUri,
                DocumentsContract.getTreeDocumentId(destUri)
            )
            // m5: unique temp name using ISO timestamp
            val uniqueName = "rec_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.mid"
            val childUri = DocumentsContract.createDocument(
                contentResolver, docFile, "audio/midi", uniqueName
            ) ?: throw IOException("Could not create document in SAF folder")
            contentResolver.openOutputStream(childUri)?.use { out ->
                tempFile.inputStream().use { input ->
                    input.copyTo(out)
                }
            } ?: throw IOException("Could not open output stream for $childUri")
            // Delete temp file
            tempFile.delete()
            return true
        } catch (e: Exception) {
            AppLogger.warn("MainActivity", "SAF copy failed: ${e.message}")
            // Still delete temp
            try { tempFile.delete() } catch (_: Exception) {}
            return false
        }
    }
}