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
import android.text.util.Linkify
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
import com.piano.sequencer.midi.MidiFileMappingStore
import com.piano.sequencer.midi.MidiInputReceiver
import com.piano.sequencer.midi.MidiFileTriggerController
import com.piano.sequencer.midi.PitchBendChannelResolver
import com.piano.sequencer.midi.SequencerCell
import com.piano.sequencer.midi.noteToName
import com.piano.sequencer.project.PseqArchive
import com.piano.sequencer.project.PseqCell
import com.piano.sequencer.project.PseqDocument
import com.piano.sequencer.project.PseqFormatException
import com.piano.sequencer.project.ProjectRepository
import com.piano.sequencer.service.PlaybackService
import java.io.ByteArrayInputStream
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.LinkedHashMap

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
    private lateinit var effectsButton: Button
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

    // Channel of the last note played — pitch bend / mod / breath follow this
    // channel. -1 until the first note. MIDI input callbacks run on binder
    // threads (MidiReceiver.onSend); @Volatile for cross-thread visibility of
    // lastNoteChannel (an Int write is atomic, so worst case is one transient
    // stale channel).
    @Volatile
    private var lastNoteChannel: Int = -1

    // Fallback channels for pitch bend / mod / breath before the first note
    // is played — user-configurable in Settings (bit i set = channel i
    // active, default 1 = channel 1). Written on the main thread (onResume),
    // read on binder threads (MIDI callback).
    @Volatile
    private var pitchBendChannelsMask: Int = 1

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

    // SAF document creator for .pseq project save
    private val saveProjectLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) saveProjectToUri(uri)
    }

    // SAF document opener for .pseq project load. MIME is unreliable for
    // .pseq (same reason as the SF2 picker) — content is validated on load.
    private val loadProjectLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) loadProjectFromUri(uri)
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

            // Bind trigger controller (singleton) — cheap ref storage, safe on main thread
            MidiFileTriggerController.get(this@MainActivity).bind(this@MainActivity, playbackService!!)
            refreshLog()

            // Move engine boot OFF the main thread
            Thread({
                val svc = playbackService ?: return@Thread
                // Install the native crash handler FIRST so a native crash
                // (e.g. while loading the LSP bundle) leaves a backtrace in
                // filesDir/native_crash.log that we surface on the next launch.
                try {
                    NativeEngineBridge.nativeInitCrashHandler(filesDir.absolutePath)
                } catch (e: Throwable) {
                    AppLogger.warn("MainActivity", "crash handler install failed: ${e.message}")
                }
                // Surface any native crash captured on a previous launch.
                val prevCrash = readNativeCrashLog()
                if (prevCrash != null) {
                    AppLogger.error("MainActivity", "Previous launch native crash:\n$prevCrash")
                }
                if (!NativeEngineBridge.nativeInit()) {
                    AppLogger.error("MainActivity", "nativeInit() failed")
                    runOnUiThread { if (!isFinishing && !isDestroyed) Toast.makeText(this@MainActivity, "Native init failed", Toast.LENGTH_LONG).show() }
                    return@Thread
                }
                AppLogger.info("MainActivity", "Native engine initialized")

                val openResult = svc.openAudio()
                if (openResult != 0) {
                    AppLogger.error("MainActivity", "Audio open failed: $openResult")
                    runOnUiThread { if (!isFinishing && !isDestroyed) Toast.makeText(this@MainActivity, "Audio open failed: $openResult", Toast.LENGTH_SHORT).show() }
                    return@Thread
                }
                AppLogger.info("MainActivity", "Audio opened successfully")

                // Fix #3: use the ACTUAL Oboe stream rate (device rate, e.g.
                // 44100 or 48000) for the engine, not the hardcoded 48000. The
                // FluidSynth sample rate is fixed at init (it cannot be changed
                // after creation in this FluidSynth version), so this must be
                // the real rate — otherwise the transport tick math and the
                // synth render rate disagree.
                val actualRate = svc.getSampleRate()
                if (!svc.initEngine(actualRate, 512)) {
                    AppLogger.error("MainActivity", "Engine init failed")
                    runOnUiThread { if (!isFinishing && !isDestroyed) Toast.makeText(this@MainActivity, "Engine init failed", Toast.LENGTH_SHORT).show() }
                    return@Thread
                }
                AppLogger.info("MainActivity", "Engine initialized (${actualRate}Hz, 512 buffer)")

                // Load the prebuilt LSP LADSPA bundle (master effect chain: EQ →
                // Compressor → Limiter). Extracted into nativeLibraryDir at
                // install time by the jniLibs sourceSet. Best-effort: if it is
                // missing/incompatible, loadMasterEffectBundle() returns 0 and
                // the chain stays a passthrough (the engine keeps running).
                // Effects are loaded DISABLED (bypassed) by default; the UI
                // toggles them on after the user opts in.
                //
                // Pre-load the bundle via System.loadLibrary so the linker
                // resolves its NEEDED deps and registers the soname; the native
                // dlopen then finds it by soname fallback. The bundle is built
                // from the pinned LSP submodule by CI and packaged as a jniLib.
                // Result is logged to AppLogger.
                //
                // If the previous launch crashed while loading the bundle (a
                // native_crash.log is present), do NOT retry the load this
                // launch — leave the chain as a passthrough so the user can
                // read the captured backtrace and report it. The crash log is
                // cleared below so a subsequent (manual) reload attempt is
                // allowed to proceed.
                if (prevCrash != null) {
                    AppLogger.warn("MainActivity", "Skipping LSP bundle load: previous launch crashed (see native crash above). Chain stays passthrough.")
                } else {
                    NativeEngineBridge.preloadLspBundle(this@MainActivity)
                    loadMasterEffectBundle(svc)
                }

                // Restore persisted state (SF2, polyphony, master gain, channel programs)
                restorePersistedState(svc)

                // Start audio
                svc.startAudio()
                val playing = svc.isAudioPlaying() == true
                val underruns = svc.getUnderrunCount()

                // Warm the MIDI file event cache for all assigned cells
                MidiFileTriggerController.get(this@MainActivity).preloadAll()

                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    statusText.text = if (playing) "Piano Sequencer — running (underruns: $underruns)"
                                      else "Piano Sequencer — audio start failed"
                }
            }, "EngineBoot").apply { isDaemon = true }.start()
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

    /** Toast on the main thread; no-op if the activity is finishing/destroyed. */
    private fun uiToast(msg: String, length: Int = Toast.LENGTH_SHORT) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            Toast.makeText(this, msg, length).show()
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
            text = getString(R.string.instruments_title)
            setOnClickListener {
                startActivity(Intent(this@MainActivity, InstrumentActivity::class.java))
            }
        }
        effectsButton = Button(this).apply {
            text = getString(R.string.master_effects_title)
            setOnClickListener {
                startActivity(Intent(this@MainActivity, EffectsActivity::class.java))
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
        layout.addView(effectsButton)
        layout.addView(settingsButton)
        layout.addView(Button(this).apply {
            text = getString(R.string.sequencer_title)
            setOnClickListener {
                startActivity(Intent(this@MainActivity, SequencerActivity::class.java))
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
                // Keyboard is using this channel regardless of file triggering
                lastNoteChannel = channel
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
                // Delegate to trigger controller — consumed while learning (first CC
                // of the session is captured) or when a cell is mapped to this CC
                // (press toggles the cell's file; repeats are consumed too).
                if (MidiFileTriggerController.get(this@MainActivity).onControlChange(channel, controller, value)) return
                if (controller in 0..1) {
                    // Modulation / breath follow the keyboard's current channel
                    val targets = PitchBendChannelResolver.resolve(lastNoteChannel, pitchBendChannelsMask)
                    withService { svc ->
                        for (t in targets) {
                            svc.sendMidiMessage(0xB0 or t, controller, value)
                        }
                    }
                } else {
                    withService { it.sendMidiMessage(0xB0 or channel, controller, value) }
                }
            }
            override fun onProgramChange(channel: Int, program: Int) {
                withService { it.sendMidiMessage(0xC0 or channel, program, 0) }
            }
            override fun onPitchBend(channel: Int, value: Int) {
                // Delegate to trigger controller — consumed while learning (first
                // pitch bend of the session is captured) or when a cell is mapped
                // to pitch bend (press toggles the cell's file; repeats consumed).
                if (MidiFileTriggerController.get(this@MainActivity).onPitchBend(channel, value)) return
                // Pitch bend follows the keyboard's current channel
                val targets = PitchBendChannelResolver.resolve(lastNoteChannel, pitchBendChannelsMask)
                withService { svc ->
                    for (t in targets) {
                        svc.sendMidiMessage(0xE0 or t, value and 0x7F, (value shr 7) and 0x7F)
                    }
                }
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

    /**
     * Read filesDir/native_crash.log (written by the native crash handler on a
     * previous launch). Returns the contents and DELETES the file so a
     * subsequent manual reload is allowed; returns null if absent.
     */
    private fun readNativeCrashLog(): String? {
        return try {
            val f = java.io.File(filesDir, "native_crash.log")
            if (!f.exists()) return null
            val text = f.readText()
            f.delete()
            text
        } catch (e: Throwable) {
            null
        }
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
    // Runs inline on the EngineBoot worker thread (not main thread).
    // Covers process death: the engine is a fresh instance and everything
    // (SF2, polyphony, master gain, channel programs) is reset to defaults.
    // If the engine survived (activity recreation) its state is intact — the
    // sfcount guard skips the restore.
    /**
     * Load the prebuilt LSP LADSPA bundle into the master effect chain. Called
     * on the worker thread after the engine is initialized. The bundle is
     * packaged as a jniLib and extracted to nativeLibraryDir at install time.
     * Effects are loaded DISABLED (bypassed); the UI enables them on opt-in.
     */
    private fun loadMasterEffectBundle(svc: PlaybackService) {
        // The bundle is packaged as a jniLib (lib/arm64-v8a/liblsp-plugins-ladspa.so).
        // It is pre-loaded via System.loadLibrary above so the linker registers its
        // soname; the native dlopen then resolves it by soname fallback even if the
        // file is not materialized in nativeLibraryDir (extractNativeLibs=false).
        val libDir = applicationInfo.nativeLibraryDir
        val soPath = "$libDir/liblsp-plugins-ladspa.so"
        AppLogger.info("MainActivity", "Loading LSP bundle: $soPath")
        if (!java.io.File(soPath).exists()) {
            AppLogger.info("MainActivity", "LSP bundle not on disk at $soPath (extractNativeLibs=false) — relying on soname fallback")
        }
        val available = try {
            svc.loadMasterEffectBundle(soPath)
        } catch (e: UnsatisfiedLinkError) {
            AppLogger.error("MainActivity", "LSP bundle load threw UnsatisfiedLinkError: ${e.message}")
            0
        } catch (e: Exception) {
            AppLogger.error("MainActivity", "LSP bundle load threw ${e.javaClass.simpleName}: ${e.message}")
            0
        }
        if (available > 0) {
            AppLogger.info("MainActivity", "LSP master effects available: $available/${svc.getMasterEffectCount()}")
            restorePersistedEffectState(svc, available)
        } else {
            val reason = runCatching { svc.getMasterEffectLoadError() }.getOrDefault("")
            AppLogger.error(
                "MainActivity",
                "LSP master effects unavailable (chain bypassed). Reason: ${reason.ifEmpty { "none reported" }}"
            )
        }
    }

    /**
     * Re-apply persisted master-effect enable flags and parameter values after
     * the bundle is (re)loaded. Effects default to bypassed; this restores the
     * user's last choices across process death. Worker thread only (direct JNI).
     */
    private fun restorePersistedEffectState(svc: PlaybackService, effectCount: Int) {
        val prefs = getSharedPreferences("piano_prefs", MODE_PRIVATE)
        for (slot in 0 until effectCount) {
            val paramCount = try { svc.getMasterEffectParamCount(slot) } catch (e: Exception) { continue }
            for (index in 0 until paramCount) {
                val info = svc.getMasterEffectParamInfo(slot, index) ?: continue
                if (info.size < 1) continue
                val paramId = info[0].toInt()
                if (prefs.contains("fx_param_${slot}_$paramId")) {
                    val v = prefs.getFloat("fx_param_${slot}_$paramId", info[3])
                    try { svc.setMasterEffectParameter(slot, paramId, v) } catch (e: Exception) { }
                }
            }
            val enabled = prefs.getBoolean("fx_enabled_$slot", false)
            try { svc.setMasterEffectEnabled(slot, enabled) } catch (e: Exception) { }
        }
    }

    private fun restorePersistedState(svc: PlaybackService) {
        // A fresh engine always starts with no SoundFonts, so sfcount > 0
        // means the engine survived (activity recreation) — state intact.
        if (svc.getSoundFontCount() > 0) return
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

        // 2b. Restore reverb / chorus / interps / buffer size (Fix #10-12, #4).
        svc.setReverb(prefs.getInt("reverb", 1) != 0)
        svc.setChorus(prefs.getInt("chorus", 1) != 0)
        svc.setInterps(prefs.getInt("interps", 4))
        // m3: restore the auto-tune state (default ON = 1). Backward compat:
        // if no explicit auto_tune pref but a manual buffer_size was persisted
        // (old code), treat it as auto-tune OFF.
        val autoTune = if (prefs.contains("auto_tune")) {
            prefs.getInt("auto_tune", 1)
        } else {
            if (prefs.getInt("buffer_size", 0) > 0) 0 else 1
        }
        if (autoTune != 0) {
            svc.setAutoTune(true)
        } else {
            svc.setAutoTune(false)
            val bufferSize = prefs.getInt("buffer_size", 0)
            if (bufferSize > 0) {
                svc.setBufferSizeInFrames(bufferSize)
            }
        }

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
    }

    // ── .pseq project save / load ──

    /**
     * Save the current session (settings + cells + MIDI files) as a .pseq
     * archive to the SAF uri. File I/O + engine reads run on a worker thread
     * (JNI calls must never run on main); toasts on main.
     */
    private fun saveProjectToUri(uri: Uri) {
        Thread({
            try {
                val prefs = getSharedPreferences("piano_prefs", MODE_PRIVATE)

                // Settings from prefs (soundFont = SF2 file name only, not bundled)
                val sf2Path = prefs.getString("sf2_path", null)
                val soundFont = sf2Path?.let { File(it).name }
                val polyphony = prefs.getInt("polyphony", 64)
                val masterGain = prefs.getFloat("master_gain", 1.0f)
                val channels = (0 until 16).map { prefs.getInt("chan_prog_$it", 0) }

                // Transport from the engine (JNI — worker thread)
                val svc = playbackService
                val bpm = svc?.getBPM() ?: 120.0
                val ppq = svc?.getPpq() ?: 480

                // Cells: absolute path → archive-relative "midi/<basename>",
                // deduping colliding basenames so every cell.filePath matches
                // a real archive entry (the cell keeps pointing at its entry).
                val midiFiles = LinkedHashMap<String, File>()
                val cells = MidiFileMappingStore.get(this).all().map { cell ->
                    if (cell.filePath.isEmpty()) return@map cell
                    val entry = PseqArchive.uniqueDestName("midi/${File(cell.filePath).name}") { it in midiFiles }
                    midiFiles[entry] = File(cell.filePath)
                    cell.copy(filePath = entry)
                }

                val now = LocalDateTime.now()
                val doc = PseqDocument(
                    formatVersion = PseqArchive.FORMAT_VERSION,
                    name = "Session " + now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
                    createdAt = now.toString(), // ISO-8601
                    bpm = bpm,
                    ppq = ppq,
                    numerator = 4,
                    denominator = 4,
                    masterGain = masterGain,
                    polyphony = polyphony,
                    soundFont = soundFont,
                    channels = channels,
                    cells = cells.map {
                         PseqCell(it.id, it.note, it.filePath, it.loop, it.tempo, it.channel, it.triggerType, it.ccNumber)
                     }
                )

                val out = contentResolver.openOutputStream(uri)
                    ?: throw IOException("Cannot open output stream")
                PseqArchive.write(out, doc, midiFiles) // write() closes the stream

                AppLogger.info("MainActivity", "Project saved: ${doc.name} (${midiFiles.size} midi file(s))")
                uiToast("Project saved")
            } catch (e: Exception) {
                AppLogger.error("MainActivity", "Project save failed: $e")
                uiToast("Save failed: ${e.message}")
            }
        }, "PseqSave").apply { isDaemon = true }.start()
    }

    /**
     * Load a .pseq archive from the SAF uri: settings + cells + MIDI files.
     * Order matters (a failure before the first state change leaves nothing
     * modified): parse/validate the document, then extract MIDI files (temp
     * file + rename), then cells, SF2, prefs, engine. Worker thread; toasts
     * on main.
     */
    private fun loadProjectFromUri(uri: Uri) {
        Thread({
            try {
                // 1. Buffer the archive once — SAF streams are one-shot, so
                //    every PseqArchive call gets a fresh ByteArrayInputStream.
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IOException("Cannot open input stream")
                val source = { ByteArrayInputStream(bytes) }

                // 2. Parse + validate (PseqFormatException message is user-readable)
                val doc = PseqArchive.readDocument(source())

                // 3. Extract MIDI files: temp file + rename, so a failure never
                //    leaves a partial file at a final name. An entry missing
                //    from the archive degrades the cell to no-file (the archive
                //    is the source of truth) — it does not abort the load.
                val extDir = getExternalFilesDir(null)
                    ?: throw IOException("External storage unavailable")
                val midiDir = File(extDir, "midi_files")
                if (!midiDir.exists()) midiDir.mkdirs()
                val entries = PseqArchive.listEntries(source())
                val entryToDest = LinkedHashMap<String, String>()
                for (cell in doc.cells) {
                    val entry = cell.filePath
                    if (entry.isEmpty() || entryToDest.containsKey(entry) || entry !in entries) continue
                    val destName = PseqArchive.uniqueDestName(File(entry).name) { File(midiDir, it).exists() }
                    val temp = File.createTempFile("pseq_", ".tmp", midiDir)
                    try {
                        PseqArchive.extractEntry(source(), entry, temp)
                        if (!temp.renameTo(File(midiDir, destName))) {
                            throw IOException("Rename failed: ${temp.name} -> $destName")
                        }
                    } catch (e: Exception) {
                        temp.delete()
                        throw e
                    }
                    entryToDest[entry] = destName
                }

                // 4. Cells: clear + set. The store is synchronized; onCellSaved
                //    only enqueues a cache preload (no JNI/UI on this thread).
                // Stop all active file slots + test-play first: a successful
                // load must not keep the old project's loops playing, and the
                // stale toggle state would make the first press on a re-mapped
                // note a silent TOGGLE_OFF. Worker-safe (enqueues only).
                MidiFileTriggerController.get(this).stopAllForRecording()
                val store = MidiFileMappingStore.get(this)
                store.clear()
                for (cell in doc.cells) {
                    val destName = cell.filePath.takeIf { it.isNotEmpty() }?.let { entryToDest[it] }
                    store.set(
                        SequencerCell(
                            id = cell.id,
                            note = cell.note,
                            filePath = destName?.let { File(midiDir, it).absolutePath } ?: "",
                            loop = cell.loop,
                            tempo = cell.tempo,
                            channel = cell.channel,
                            triggerType = cell.triggerType,
                            ccNumber = cell.ccNumber
                        )
                    )
                }
                // The archive is now the mapping's source of truth — suppress
                // the legacy backfill (MidiFilesPanel.refresh) so orphan
                // old-project .mid files are not re-added as cells.
                store.markBackfillDone()

                // 5. SF2: name only in the archive — load it if the file is on
                //    this device, otherwise keep the current one. The pref is
                //    written only on success (or when the engine is not up yet —
                //    boot restore will load it), so a failed load never
                //    clobbers a working sf2_path.
                var sf2Missing = false
                var sf2Unavailable = false
                val sf2Name = doc.soundFont
                if (sf2Name != null) {
                    // User-provided archive data: reject path separators.
                    if (sf2Name.contains('/')) {
                        sf2Missing = true
                    } else {
                        val sf2File = File(extDir, sf2Name)
                        if (sf2File.exists()) {
                            val svc = playbackService
                            val id = svc?.loadSoundFont(sf2File.absolutePath) ?: -1
                            if (id >= 0 || svc == null) {
                                getSharedPreferences("piano_prefs", MODE_PRIVATE).edit()
                                    .putString("sf2_path", sf2File.absolutePath).apply()
                            } else {
                                sf2Unavailable = true
                                AppLogger.warn("MainActivity", "Failed to load SF2 on project load: ${sf2File.absolutePath} (error: $id)")
                            }
                        } else {
                            sf2Missing = true
                        }
                    }
                }

                // 6. Prefs (single Editor, single apply)
                val prefs = getSharedPreferences("piano_prefs", MODE_PRIVATE)
                val editor = prefs.edit()
                    .putInt("polyphony", doc.polyphony)
                    .putFloat("master_gain", doc.masterGain)
                for (ch in 0 until 16) {
                    editor.putInt("chan_prog_$ch", doc.channels[ch])
                }
                editor.apply()

                // 7. Engine (JNI — worker thread; same passthroughs as
                //    restorePersistedState; setBPM via the bridge, which has
                //    no PlaybackService passthrough).
                val svc = playbackService
                if (svc != null) {
                    svc.setPolyphony(doc.polyphony)
                    svc.setMasterGain(doc.masterGain)
                    for (ch in 0 until 16) {
                        val packed = doc.channels[ch]
                        svc.setChannelProgram(ch, packed shr 8, packed and 0xFF)
                    }
                    NativeEngineBridge.nativeSetBPM(doc.bpm)
                }

                // 8. UI: the cell grid (SequencerActivity → MidiFilesPanel)
                //    re-reads the singleton store in onResume → refreshPanel,
                //    so no explicit refresh is needed here.
                val msg = if (sf2Missing || sf2Unavailable) {
                    "Project loaded: ${doc.name} — SF2 $sf2Name not available, keeping current"
                } else {
                    "Project loaded: ${doc.name}"
                }
                uiToast(msg, Toast.LENGTH_LONG)
            } catch (e: PseqFormatException) {
                AppLogger.error("MainActivity", "Project load failed: $e")
                uiToast("Load failed: ${e.message}", Toast.LENGTH_LONG)
            } catch (e: Exception) {
                AppLogger.error("MainActivity", "Project load failed: $e")
                uiToast("Load failed: ${e.message}")
            }
        }, "PseqLoad").apply { isDaemon = true }.start()
    }

    /**
     * "Projects" entry point: the two project actions as a house-style
     * item list dialog (same pattern as the MIDI device picker).
     */
    private fun showProjectsDialog() {
        val actions = arrayOf("Save Project", "Load Project")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Projects")
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> saveProjectLauncher.launch("project.pseq")
                    1 -> loadProjectLauncher.launch(arrayOf("*/*"))
                }
            }
            .show()
    }

    /**
     * "About" entry point: app info (name + version + author + repo link)
     * and the app log
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
            text = "Andrey Spitsyn"
            textSize = 14f
            setPadding(0, 8, 0, 0)
        })

        card.addView(TextView(this).apply {
            text = "https://github.com/einhander/piano"
            textSize = 14f
            setPadding(0, 8, 0, 0)
            Linkify.addLinks(this, Linkify.WEB_URLS)
        })

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
        // Re-read the pitch bend / mod / breath fallback mask — covers first
        // launch and returning from SettingsActivity.
        pitchBendChannelsMask = getSharedPreferences("piano_prefs", MODE_PRIVATE)
            .getInt("pitch_bend_channels", 1)
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
