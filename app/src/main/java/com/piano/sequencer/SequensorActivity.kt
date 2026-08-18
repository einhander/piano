package com.piano.sequencer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.Parcelable
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.piano.sequencer.midi.MidiFileAssignment
import com.piano.sequencer.midi.MidiFileLearnState
import com.piano.sequencer.midi.MidiFileMappingStore
import com.piano.sequencer.midi.MidiFileTriggerController
import com.piano.sequencer.midi.noteToName
import com.piano.sequencer.service.PlaybackService
import com.piano.sequencer.ui.MidiFilesPanel
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Sequensor activity — MIDI file pad assignment screen.
 *
 * Hosts the MidiFilesPanel with per-row channel selector.
 * Handles record export flow, file import, and test-play.
 *
 * D4: "Sequensor" (user-specified spelling).
 */
class SequensorActivity : AppCompatActivity() {

    private lateinit var panel: MidiFilesPanel
    private lateinit var panelContainer: LinearLayout

    private var service: PlaybackService? = null
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
            if (isFinishing || isDestroyed) return
            service = (binder as PlaybackService.PlaybackBinder).getService()
            MidiFileTriggerController.get(this@SequensorActivity).bind(this@SequensorActivity, service!!)
            panel.bindService(service!!)
            panel.refresh()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "SequensorWorker").apply { isDaemon = true }
    }

    // ── SAF file picker for MIDI import ──
    private val midiFileImportLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { sourceUri -> importMidiFile(sourceUri) }
    }

    // ── SAF folder picker for recorded MIDI output ──
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
            Toast.makeText(this@SequensorActivity, "Record folder selected", Toast.LENGTH_SHORT).show()
            // Complete pending export if one was waiting
            completePendingExport(uri)
        } else {
            // User cancelled picker — clear pending, toast, reset UI
            val pending = pendingExport?.takeIf { pe ->
                pendingExport = null
                true
            }
            if (pending != null) {
                Toast.makeText(this@SequensorActivity, "Export not saved (folder not selected)", Toast.LENGTH_SHORT).show()
                runOnUiThread {
                    panel.updateRecordUI(false, pending.eventCount)
                }
            }
            pendingExportFlag = false
            pendingExportFlagSaved = false
        }
    }

    // ── Pending export state ──

    /** Holds a pending export until the SAF folder picker returns. */
    private data class PendingExport(
        val service: PlaybackService,
        val eventCount: Int
    )

    private var pendingExport: PendingExport? = null

    /** Persist pending-export flag across rotation/process death. */
    private var pendingExportFlag = false
    private var pendingExportFlagSaved = false

    // ── Lifecycle ──

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sequensor)

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
        MidiFileTriggerController.get(this).onTestPlayStateChanged = { playing ->
            panel.updateTestPlayUI(playing)
        }

        setupPanelCallbacks()

        // Bind to playback service
        Intent(this, PlaybackService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }

        // Restore pending-export flag across rotation
        if (savedInstanceState != null) {
            pendingExportFlagSaved = savedInstanceState.getBoolean("pendingExportFlag", false)
        }
    }

    override fun onResume() {
        super.onResume()
        panel.refresh()
    }

    override fun onPause() {
        super.onPause()
        // m7: cancel any active learn timeout
        panel.cancelLearnTimeout()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingExportFlagSaved = pendingExportFlag
        outState.putBoolean("pendingExportFlag", pendingExportFlagSaved)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        pendingExportFlag = savedInstanceState.getBoolean("pendingExportFlag", false)
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

    // ── Panel callbacks (moved from MainActivity) ──

    private fun setupPanelCallbacks() {
        panel.onImportClick = {
            midiFileImportLauncher.launch("audio/midi")
        }

        panel.onRecordClick = {
            // m3: run the whole record toggle on a worker thread
            executor.execute {
                try {
                    val svc = service ?: run {
                        runOnUiThread {
                            Toast.makeText(this@SequensorActivity, "Service not connected", Toast.LENGTH_SHORT).show()
                        }
                        return@execute
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
                            panel.updateRecordUI(false, eventCount)
                        }
                    } else {
                        // Start recording
                        svc.startRecording()
                        runOnUiThread {
                            panel.updateRecordUI(true, 0)
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        Toast.makeText(this@SequensorActivity, "Record error: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        panel.onNoteTrigger = { note, filePath, loop, tempo, channel ->
            val assignment = MidiFileAssignment(note, filePath, loop, tempo, channel)
            MidiFileTriggerController.get(this).triggerSlot(assignment)
        }

        panel.onTestPlay = { note, filePath, loop, tempo, channel ->
            // item 8: test-play with load result check, generation counter,
            // stop-on-second-tap, worker-thread JNI
            MidiFileTriggerController.get(this).testPlay(filePath, loop, tempo, channel)
        }

        panel.onFileDelete = { filePath ->
            // Free any slot mapped to this file
            val controller = MidiFileTriggerController.get(this)
            val store = MidiFileMappingStore.get(this)
            for ((note, assignment) in store.all()) {
                if (assignment.filePath == filePath) {
                    store.remove(note)
                    controller.freeSlotForNote(note)
                }
            }
            panel.refresh()
        }

        panel.onSettingChange = { note, loop, tempo, channel ->
            val controller = MidiFileTriggerController.get(this)
            controller.onSettingChanged(note, loop, tempo, channel)
        }

        panel.onNoteLearned = { note, filePath, loop, tempo, channel ->
            // Allocate a slot for this note
            MidiFileTriggerController.get(this).allocateSlotForNote(note)
            // Clear any stale loaded-file tracking for this slot
            val controller = MidiFileTriggerController.get(this)
            val slot = controller.getSlotForNote(note)
            if (slot != null) {
                controller.clearLoadedFile(slot)
            }
            panel.refresh()
        }

        panel.onMappingRemove = { note ->
            MidiFileTriggerController.get(this).freeSlotForNote(note)
        }

        panel.onRefresh = {
            panel.refresh()
        }
    }

    // ── MIDI import ──

    private fun importMidiFile(sourceUri: Uri) {
        executor.execute {
            try {
                val midiDir = File(this@SequensorActivity.getExternalFilesDir(null), "midi_files")
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
                    Toast.makeText(this@SequensorActivity, "Imported: ${destFile.name}", Toast.LENGTH_SHORT).show()
                    panel.refresh()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@SequensorActivity, "Import failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
                AppLogger.warn("SequensorActivity", "MIDI import failed: ${e.message}")
            }
        }
    }

    // ── Record export ──

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
            val svc = service ?: return
            executor.execute {
                val count = svc.getRecordedEventCount()
                if (count > 0) {
                    writeRecordedMidiFileToUri(svc, count, uri)
                } else {
                    runOnUiThread {
                        Toast.makeText(this@SequensorActivity, "Nothing to export (recording lost)", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    /**
     * Write recorded MIDI to a temp file, then copy to SAF folder.
     * M5: uses actual BPM and PPQ from transport state.
     */
    private fun writeRecordedMidiFileToUri(service: PlaybackService, eventCount: Int, uri: Uri) {
        executor.execute {
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
                        panel.updateRecordUI(false, eventCount)
                        if (copied) {
                            Toast.makeText(this@SequensorActivity,
                                "Recorded $eventCount events", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@SequensorActivity, "Export failed (SAF copy)", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(this@SequensorActivity, "Export failed", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@SequensorActivity, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
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
            AppLogger.warn("SequensorActivity", "SAF copy failed: ${e.message}")
            // Still delete temp
            try { tempFile.delete() } catch (_: Exception) {}
            return false
        }
    }
}