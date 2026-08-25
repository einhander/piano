package com.piano.sequencer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.piano.sequencer.midi.ChordNote
import com.piano.sequencer.midi.ChordRecorder
import com.piano.sequencer.midi.MidiFileLearnState
import com.piano.sequencer.midi.MidiFileMappingStore
import com.piano.sequencer.midi.MidiFileTriggerController
import com.piano.sequencer.midi.MidiRecordSession
import com.piano.sequencer.midi.MODE_CHORD
import com.piano.sequencer.midi.SequencerCell
import com.piano.sequencer.service.PlaybackService
import com.piano.sequencer.ui.MidiFilesPanel
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sequencer activity — MIDI file pad assignment screen.
 *
 * Hosts the MidiFilesPanel with per-cell controls.
 * Handles per-cell record (with overwrite confirm), per-cell export to SAF, file import, and test-play.
 */
class SequencerActivity : AppCompatActivity() {

    private lateinit var panel: MidiFilesPanel
    private lateinit var panelContainer: LinearLayout

    private var service: PlaybackService? = null
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
            if (isFinishing || isDestroyed) return
            service = (binder as PlaybackService.PlaybackBinder).getService()
            MidiFileTriggerController.get(this@SequencerActivity).bind(this@SequencerActivity, service!!)
            refreshPanel()

            // F2: Restore per-cell recording UI across rotation (engine keeps recording).
            // The holder is process-level; wait out any in-flight start/stop from a
            // previous activity instance before reading engine state.
            val svc = service!!
            executor.execute {
                var tries = 0
                while (MidiRecordSession.inFlight && tries < 20) {
                    Thread.sleep(50)
                    tries++
                }
                if (MidiRecordSession.inFlight) return@execute
                if (svc.isRecording()) {
                    val id = MidiRecordSession.cellId
                        ?: getSharedPreferences("piano_prefs", MODE_PRIVATE).getInt("recording_cell_id", -1)
                    if (id > 0) {
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                recordingCellId = id
                                panel.updateCellRecordState(id)
                            }
                        }
                    }
                } else {
                    MidiRecordSession.cellId = null
                    getSharedPreferences("piano_prefs", MODE_PRIVATE).edit().remove("recording_cell_id").apply()
                }
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "SequencerWorker").apply { isDaemon = true }
    }

    // ── SAF file picker for MIDI import ──
    private val midiFileImportLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { sourceUri -> importMidiFile(sourceUri, pendingImportCellId) }
    }

    // ── SAF folder picker for cell file export ──
    private val exportFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            getSharedPreferences("piano_prefs", MODE_PRIVATE).edit()
                .putString("export_folder_uri", uri.toString())
                .apply()
            Toast.makeText(this, "Export folder selected", Toast.LENGTH_SHORT).show()
            pendingExportCellId?.let { id ->
                pendingExportCellId = null
                val store = MidiFileMappingStore.get(this)
                store.get(id)?.let { cell ->
                    if (cell.filePath.isNotEmpty() && File(cell.filePath).exists()) {
                        exportCellFile(cell, uri)
                    }
                }
            }
        } else {
            pendingExportCellId = null
            Toast.makeText(this, "Export cancelled", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Lifecycle ──

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sequencer)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }

        panelContainer = findViewById(R.id.panelContainer)
        panel = MidiFilesPanel(this, MidiFileMappingStore.get(this))
        panelContainer.addView(panel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // Wire test-play state callback → panel label update
        MidiFileTriggerController.get(this).onTestPlayStateChanged = { cellId, playing ->
            panel.updateTestPlayUI(cellId, playing)
        }

        setupPanelCallbacks()

        // Bind to playback service
        Intent(this, PlaybackService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPanel()
    }

    override fun onPause() {
        super.onPause()
        // m7: cancel any active learn timeout
        panel.cancelLearnTimeout()
    }

    override fun onDestroy() {
        MidiFileTriggerController.get(this).onTestPlayStateChanged = null
        executor.shutdown()
        super.onDestroy()
        try {
            unbindService(serviceConnection)
        } catch (_: Exception) {
            // ignore if not bound
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        navigateUpTo(Intent(this, MainActivity::class.java))
        return true
    }

    // ── Per-cell import tracking ──
    private var pendingImportCellId: Int? = null

    /** Cell currently being recorded (main-thread UI source of truth). */
    @Volatile
    private var recordingCellId: Int? = null

    /** m6-style double-tap guard for the record toggle. */
    private val recordBusy = AtomicBoolean(false)

    /** Cell waiting for the export folder picker. */
    private var pendingExportCellId: Int? = null

    // ── Panel refresh ──

    /**
     * Rebuild the panel and re-apply the per-cell record state.
     * refresh() recreates all row buttons (idle state), so the active
     * "■ Stop" state must be re-applied while a recording is in progress.
     */
    private fun refreshPanel() {
        panel.refresh()
        panel.updateCellRecordState(recordingCellId)
    }

    // ── Panel callbacks ──

    private fun setupPanelCallbacks() {
        panel.onImportClick = { cellId ->
            pendingImportCellId = cellId
            midiFileImportLauncher.launch("audio/midi")
        }

        panel.onCellRecordClick = cell@{ cellId ->
            // m6: guard against double-tap during the worker round-trip
            if (recordBusy.get()) {
                Toast.makeText(this, "Please wait...", Toast.LENGTH_SHORT).show()
                return@cell
            }
            recordBusy.set(true)
            if (recordingCellId != null) {
                // Second press stops the active recording — no dialog
                stopRecordingFlow()
            } else {
                val cell = MidiFileMappingStore.get(this).get(cellId)
                if (cell == null) {
                    recordBusy.set(false)
                    return@cell
                }
                val hasExisting = if (cell.mode == MODE_CHORD) {
                    cell.chordNotes.isNotEmpty()
                } else {
                    cell.filePath.isNotEmpty() && File(cell.filePath).exists()
                }
                if (hasExisting) {
                    // F1: build with .create(), add cancel listener, then show
                    val dialog = AlertDialog.Builder(this)
                        .setTitle("Overwrite recording?")
                        .setMessage("This cell already has a recording. The old file will be replaced.")
                        .setPositiveButton("Overwrite") { _, _ -> startRecordingFlow(cellId) }
                        .setNegativeButton("Cancel") { _, _ -> recordBusy.set(false) }
                        .create()
                    // F1: back button / outside tap dismiss via cancel() — release the guard there too
                    dialog.setOnCancelListener { recordBusy.set(false) }
                    dialog.show()
                } else {
                    startRecordingFlow(cellId)
                }
            }
        }

        panel.onCellExportClick = { cellId ->
            val cell = MidiFileMappingStore.get(this).get(cellId)
            if (cell != null && cell.filePath.isNotEmpty() && File(cell.filePath).exists()) {
                val prefs = getSharedPreferences("piano_prefs", MODE_PRIVATE)
                val folderUriStr = prefs.getString("export_folder_uri", null)
                if (folderUriStr == null) {
                    pendingExportCellId = cellId
                    exportFolderLauncher.launch(null)
                } else {
                    exportCellFile(cell, Uri.parse(folderUriStr))
                }
            } else {
                Toast.makeText(this, "No file assigned", Toast.LENGTH_SHORT).show()
            }
        }

        panel.onTestPlay = { cell ->
            MidiFileTriggerController.get(this).testPlay(cell.id, cell.filePath, cell.loop, cell.tempo, cell.channel)
        }

        panel.onSettingChange = { cell ->
            // B4: any trigger type (NOTE/CC/PITCH_BEND) live-updates its slot —
            // pass the encoded trigger key, not the raw note.
            if (cell.hasTrigger()) {
                MidiFileTriggerController.get(this).onSettingChanged(cell.triggerKey(), cell.loop, cell.tempo, cell.channel)
            }
        }

        panel.onNoteLearned = { cell, key ->
            val c = MidiFileTriggerController.get(this)
            c.allocateSlotForKey(key)
            c.getSlotForKey(key)?.let { c.clearLoadedFile(it) }
            refreshPanel()
        }

        panel.onNoteUnlearned = { key ->
            MidiFileTriggerController.get(this).freeSlotForKey(key)
            refreshPanel()
        }

        panel.onModeToggle = { cellId, newMode ->
            val store = MidiFileMappingStore.get(this)
            val cur = store.get(cellId)
            if (cur != null && cur.mode != newMode) {
                // Stop any sounding chord / free the file slot for the old mode.
                if (cur.hasTrigger()) {
                    MidiFileTriggerController.get(this).freeSlotForTrigger(cur.triggerKey())
                }
                store.set(cur.copy(mode = newMode))
            }
        }

        panel.onCellRemove = { cell ->
            // F3: guard — don't remove a cell that's actively being recorded
            if (cell.id == MidiRecordSession.cellId) {
                Toast.makeText(this, "Stop recording first", Toast.LENGTH_SHORT).show()
            } else {
                // Free the trigger slot (encoded key: NOTE 0-127, CC 128-255, PB 256)
                // ONLY after the recording guard passed — a blocked removal keeps
                // the cell and its slot. Idempotent for NOTE cells (second free is
                // a no-op; C++ FREE is idempotent).
                if (cell.hasTrigger()) {
                    MidiFileTriggerController.get(this).freeSlotForTrigger(cell.triggerKey())
                }
                MidiFileMappingStore.get(this).remove(cell.id)
                refreshPanel()
            }
        }
    }

    // ── MIDI import ──

    private fun importMidiFile(sourceUri: Uri, cellId: Int?) {
        executor.execute {
            try {
                val midiDir = File(this@SequencerActivity.getExternalFilesDir(null), "midi_files")
                if (!midiDir.exists()) midiDir.mkdirs()
                // Deduplicate: find a unique name
                var baseName = try {
                    contentResolver.query(sourceUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
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
                    if (cellId != null) {
                        val store = MidiFileMappingStore.get(this@SequencerActivity)
                        val cur = store.get(cellId)
                        if (cur != null) {
                            store.set(cur.copy(filePath = destFile.absolutePath))
                        }
                    }
                    Toast.makeText(this@SequencerActivity, "Imported: ${destFile.name}", Toast.LENGTH_SHORT).show()
                    refreshPanel()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@SequencerActivity, "Import failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
                AppLogger.warn("SequencerActivity", "MIDI import failed: ${e.message}")
            }
        }
    }

    // ── Per-cell record ──

    /** Start recording into the given cell (worker thread). */
    private fun startRecordingFlow(cellId: Int) {
        executor.execute {
            try {
                val svc = service ?: run {
                    runOnUiThread {
                        Toast.makeText(this, "Service not connected", Toast.LENGTH_SHORT).show()
                        recordBusy.set(false)
                    }
                    return@execute
                }
                // F4: cancel any active learn mode — its capture would swallow the first key
                MidiFileLearnState.cancel()
                // F5: stop all active file slots + test-play; their pass-start events
                // (timestamp == 0) would otherwise leak into the recording
                MidiFileTriggerController.get(this).stopAllForRecording()
                val cell = MidiFileMappingStore.get(this).get(cellId)
                val isChord = cell?.mode == MODE_CHORD
                MidiRecordSession.inFlight = true
                try {
                    if (isChord) {
                        // Chord mode: collect notes in Kotlin (no engine MIDI
                        // recorder needed — the chord is the union of played
                        // keys, not a timed MIDI stream).
                        ChordRecorder.start()
                    } else {
                        svc.startRecording()
                    }
                    MidiRecordSession.cellId = cellId
                    recordingCellId = cellId
                    getSharedPreferences("piano_prefs", MODE_PRIVATE).edit()
                        .putInt("recording_cell_id", cellId)
                        .apply()
                } finally {
                    MidiRecordSession.inFlight = false
                }
                runOnUiThread {
                    panel.updateCellRecordState(cellId)
                    recordBusy.set(false)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Record error: ${e.message}", Toast.LENGTH_SHORT).show()
                    recordBusy.set(false)
                }
            }
        }
    }

    /** Stop the active recording and save it into the recorded cell (worker thread). */
    private fun stopRecordingFlow() {
        executor.execute {
            try {
                // F2: service-null branch — DO NOT clear recordingCellId, prefs, or panel state
                // (the engine is a process singleton and may still be recording;
                //  recovery on (re)connect reconciles).
                val svc = service ?: run {
                    runOnUiThread {
                        Toast.makeText(this, "Service not connected", Toast.LENGTH_SHORT).show()
                        recordBusy.set(false)
                    }
                    return@execute
                }
                MidiRecordSession.inFlight = true
                val recCellId = MidiRecordSession.cellId
                val cell = recCellId?.let { MidiFileMappingStore.get(this).get(it) }
                val isChord = cell?.mode == MODE_CHORD
                try {
                    if (!isChord) {
                        svc.stopRecording()
                    }
                    // Holder is authoritative (survives activity recreation); in-memory mirror for UI
                    MidiRecordSession.cellId = null
                    recordingCellId = null
                    getSharedPreferences("piano_prefs", MODE_PRIVATE).edit().remove("recording_cell_id").apply()
                } finally {
                    MidiRecordSession.inFlight = false
                }

                if (isChord && recCellId != null) {
                    val chordNotes = ChordRecorder.stop()
                    if (chordNotes.isNotEmpty() && cell != null) {
                        runOnUiThread {
                            val store = MidiFileMappingStore.get(this)
                            val cur = store.get(recCellId)
                            if (cur != null) {
                                store.set(cur.copy(chordNotes = chordNotes))
                                refreshPanel()
                                Toast.makeText(this, "Chord recorded: ${chordNotes.size} notes", Toast.LENGTH_SHORT).show()
                            } else {
                                panel.updateCellRecordState(null)
                            }
                            recordBusy.set(false)
                        }
                    } else {
                        ChordRecorder.cancel()
                        runOnUiThread {
                            Toast.makeText(this, "Nothing recorded", Toast.LENGTH_SHORT).show()
                            panel.updateCellRecordState(null)
                            recordBusy.set(false)
                        }
                    }
                    return@execute
                }

                val eventCount = if (!isChord) svc.getRecordedEventCount() else 0
                if (eventCount > 0 && recCellId != null) {
                    val midiDir = File(getExternalFilesDir(null), "midi_files")
                    if (!midiDir.exists()) midiDir.mkdirs()
                    // F6: add milliseconds to recorded file name
                    val name = "recorded_" +
                        SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()) + ".mid"
                    val destFile = File(midiDir, name)
                    val bpm = svc.getBPM()
                    val ppq = svc.getPpq()
                    val tempoUs = (60_000_000.0 / bpm).toInt()
                    val ok = svc.writeRecordedMidiFile(destFile.absolutePath, ppq, tempoUs)
                    if (ok) {
                        runOnUiThread {
                            val store = MidiFileMappingStore.get(this)
                            val cell = store.get(recCellId)
                            if (cell != null) {
                                store.set(cell.copy(filePath = destFile.absolutePath))
                                // Force slot reload of the new file (same pattern as onNoteLearned)
                                if (cell.hasTrigger()) {
                                    val c = MidiFileTriggerController.get(this)
                                    c.getSlotForKey(cell.triggerKey())?.let { c.clearLoadedFile(it) }
                                }
                                refreshPanel()
                                Toast.makeText(this, "Recorded: ${destFile.name} ($eventCount events)", Toast.LENGTH_SHORT).show()
                            } else {
                                // Cell was removed while recording — no row to refresh; just reset button states
                                panel.updateCellRecordState(null)
                            }
                            // F10: release the double-tap guard only after the UI update is posted
                            recordBusy.set(false)
                        }
                    } else {
                        runOnUiThread {
                            Toast.makeText(this, "Record save failed", Toast.LENGTH_SHORT).show()
                            panel.updateCellRecordState(null)
                            recordBusy.set(false)
                        }
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(this,
                            if (eventCount == 0) "Nothing recorded" else "Recording lost",
                            Toast.LENGTH_SHORT).show()
                        panel.updateCellRecordState(null)
                        recordBusy.set(false)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Record error: ${e.message}", Toast.LENGTH_SHORT).show()
                    panel.updateCellRecordState(null)
                    recordBusy.set(false)
                }
            }
        }
    }

    // ── Per-cell export ──

    /** Copy the cell's file to the SAF export folder (worker thread). */
    private fun exportCellFile(cell: SequencerCell, uri: Uri) {
        executor.execute {
            try {
                val source = File(cell.filePath)
                val copied = copyToSaf(source, uri, source.name)
                runOnUiThread {
                    if (copied) {
                        Toast.makeText(this, "Exported: ${source.name}", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Export failed", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                AppLogger.warn("SequencerActivity", "Cell export failed: ${e.message}")
                runOnUiThread {
                    Toast.makeText(this, "Export failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ── SAF copy helper ──

    /**
     * Copy a file to a SAF folder using DocumentFile API.
     * Does NOT delete the source file (cell files must persist).
     */
    private fun copyToSaf(sourceFile: File, destUri: Uri, fileName: String): Boolean {
        try {
            val docFile = DocumentsContract.buildDocumentUriUsingTree(
                destUri,
                DocumentsContract.getTreeDocumentId(destUri)
            )
            val childUri = DocumentsContract.createDocument(
                contentResolver, docFile, "audio/midi", fileName
            ) ?: throw IOException("Could not create document in SAF folder")
            contentResolver.openOutputStream(childUri)?.use { out ->
                sourceFile.inputStream().use { input ->
                    input.copyTo(out)
                }
            } ?: throw IOException("Could not open output stream for $childUri")
            return true
        } catch (e: Exception) {
            AppLogger.warn("SequencerActivity", "SAF copy failed: ${e.message}")
            return false
        }
    }
}