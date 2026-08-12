package com.piano.sequencer

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.UriPermission
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.provider.DocumentsContract
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.piano.sequencer.midi.MidiDeviceManager
import com.piano.sequencer.midi.MidiInputReceiver
import com.piano.sequencer.project.Project
import com.piano.sequencer.project.ProjectRepository
import com.piano.sequencer.service.PlaybackService

class MainActivity : Activity() {

    private lateinit var layout: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var playButton: Button
    private lateinit var c4Button: Button
    private lateinit var d4Button: Button
    private lateinit var e4Button: Button
    private lateinit var panicButton: Button
    private lateinit var saveButton: Button
    private lateinit var loadButton: Button
    private lateinit var exportMidiButton: Button
    private lateinit var midiStatusText: TextView

    private lateinit var projectRepo: ProjectRepository

    private lateinit var midiManager: MidiDeviceManager
    private lateinit var midiInputReceiver: MidiInputReceiver

    private var playbackService: PlaybackService? = null
    private var serviceBound = false

    // SAF file picker for project save/load
    private val filePickerLauncher = registerForActivityResult(
        android.app.ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            projectRepo.setProjectUri(it)
            Toast.makeText(this@MainActivity, "Project directory selected", Toast.LENGTH_SHORT).show()
        }
    }

    // SAF file picker for MIDI import
    private val midiImportLauncher = registerForActivityResult(
        android.app.ActivityResultContracts.GetContent()
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

            val openResult = playbackService?.openAudio()
            if (openResult != 0) {
                Toast.makeText(this@MainActivity, "Audio open failed: $openResult", Toast.LENGTH_SHORT).show()
            }
            val initResult = playbackService?.initEngine(48000, 512)
            if (initResult != true) {
                Toast.makeText(this@MainActivity, "Engine init failed", Toast.LENGTH_SHORT).show()
            }
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
        playButton = Button(this).apply {
            text = "Play"
            setOnClickListener { toggleAudio() }
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
                Toast.makeText(this, "Panic!", Toast.LENGTH_SHORT).show()
            }
        }
        saveButton = Button(this).apply {
            text = "Save Project"
            setOnClickListener { saveProject() }
        }
        loadButton = Button(this).apply {
            text = "Load Project"
            setOnClickListener { loadProject() }
        }
        exportMidiButton = Button(this).apply {
            text = "Export MIDI"
            setOnClickListener { exportMidiFile() }
        }
        layout.addView(statusText)
        layout.addView(playButton)
        layout.addView(c4Button)
        layout.addView(d4Button)
        layout.addView(e4Button)
        layout.addView(panicButton)
        layout.addView(saveButton)
        layout.addView(loadButton)
        layout.addView(exportMidiButton)
        setContentView(layout)

        midiStatusText = TextView(this).apply {
            text = "MIDI: checking..."
            textSize = 14f
        }
        layout.addView(midiStatusText)

        // Setup MIDI receiver callback
        midiInputReceiver = MidiInputReceiver()
        midiInputReceiver.setCallback(object : MidiInputReceiver.Callback {
            override fun onNoteOn(channel: Int, note: Int, velocity: Int) {
                withService { it.sendMidiMessage(0x90 or channel, note, velocity) }
            }
            override fun onNoteOff(channel: Int, note: Int, velocity: Int) {
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
                    midiStatusText.text = "MIDI: connected (${device.product})"
                }
            }
            override fun onDeviceDisconnected() {
                runOnUiThread {
                    midiStatusText.text = "MIDI: disconnected"
                }
            }
        })

        // Auto-connect first available device
        val devices = midiManager.listDevices()
        if (devices.isNotEmpty()) {
            midiManager.connect(devices[0])
        } else {
            midiStatusText.text = "MIDI: no devices"
        }

        bindService(Intent(this, PlaybackService::class.java), serviceConnection, BIND_AUTO_CREATE)

        // Initialize project repository
        projectRepo = ProjectRepository(this)
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

    private fun showSaveProjectPicker() {
        filePickerLauncher.launch(null)
    }

    override fun onResume() {
        super.onResume()
        if (!midiManager.isConnected()) {
            val devices = midiManager.listDevices()
            if (devices.isNotEmpty()) {
                midiManager.connect(devices[0])
            } else {
                midiStatusText.text = "MIDI: no devices"
            }
        }
    }

    override fun onPause() {
        super.onPause()
        withService { it.stopAudio() }
        playButton.text = "Play"
        statusText.text = "Piano Sequencer — stopped"
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        midiManager.close()
        projectRepo.shutdown()
    }

    private fun toggleAudio() {
        withService { service ->
            if (service.isAudioPlaying()) {
                service.stopAudio()
                playButton.text = "Play"
                statusText.text = "Piano Sequencer — stopped"
            } else {
                service.startAudio()
                playButton.text = "Stop"
                val underruns = service.getUnderrunCount()
                statusText.text = "Piano Sequencer — playing (underruns: $underruns)"
            }
        }
    }
}