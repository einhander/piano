package com.piano.sequencer.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.piano.sequencer.AppLogger
import com.piano.sequencer.R
import com.piano.sequencer.midi.MidiFileAssignment
import com.piano.sequencer.midi.MidiFileMappingStore
import com.piano.sequencer.midi.MidiFileLearnState
import com.piano.sequencer.midi.noteToName
import com.piano.sequencer.service.PlaybackService
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Programmatic LinearLayout panel for MIDI file key mapping management.
 *
 * Displays a list of .mid files in getExternalFilesDir(null)/midi_files/,
 * with per-file controls: learned key label, loop checkbox, tempo edit,
 * learn key, play/stop test, delete file. Also has import .mid and record sections.
 *
 * House style: all views created programmatically, no layout XML.
 *
 * M2: receives single store instance from MainActivity constructor.
 * m3: record toggle runs on worker thread.
 * m6: double-tap guard via isRecordingUi flag.
 * m7: learn mode timeout (10s) via Handler.postDelayed.
 */
class MidiFilesPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    // ── Callbacks set by MainActivity ──

    /** Called when user wants to import a .mid file via GetContent picker. */
    var onImportClick: () -> Unit = {}

    /** Called when user wants to start/stop recording. */
    var onRecordClick: () -> Unit = {}

    /** Called when a mapped note key is pressed (start/stop slot). */
    var onNoteTrigger: (note: Int, filePath: String, loop: Boolean, tempo: Double) -> Unit =
        { _, _, _, _ -> }

    /** Called when a test-play button is pressed. */
    var onTestPlay: (note: Int, filePath: String, loop: Boolean, tempo: Double) -> Unit =
        { _, _, _, _ -> }

    /** Called when a file is deleted (also frees slot). */
    var onFileDelete: (filePath: String) -> Unit = {}

    /** Called when loop/tempo settings change for a mapped file. */
    var onSettingChange: (note: Int, loop: Boolean, tempo: Double) -> Unit = { _, _, _ -> }

    /** Called when a note is learned (saved to store). */
    var onNoteLearned: (note: Int, filePath: String, loop: Boolean, tempo: Double) -> Unit =
        { _, _, _, _ -> }

    /** Called when a mapping is removed (also frees slot). */
    var onMappingRemove: (note: Int) -> Unit = {}

    /** Called when record status changes (event count). */
    var onRecordStatus: (eventCount: Int) -> Unit = {}

    /** Refresh the file list (called on resume, after import, after record, after delete). */
    var onRefresh: () -> Unit = {}

    // ── State ──

    /** M2: store injected by MainActivity, not created here. */
    private lateinit var store: MidiFileMappingStore

    /** Injected from MainActivity constructor. */
    constructor(context: Context, store: MidiFileMappingStore) : this(context) {
        this.store = store
    }

    private var service: PlaybackService? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /** m6: guard against double-tap during export. */
    private val isRecordingUi = AtomicBoolean(false)

    /** m7: handler for learn timeout. */
    private var learnTimeoutRunnable: Runnable? = null

    /** n3: direct references to record section widgets (no text-matched scan). */
    private var recordBtn: Button? = null
    private var recordStatus: TextView? = null

    /** item 8c: direct reference to per-row test button for label update. */
    private var currentTestBtn: Button? = null
    private var currentTestNote: Int = -1

    /** Per-row UI state: note → row widgets. */
    private data class RowWidgets(
        val loopCheck: CheckBox,
        val tempoEdit: EditText,
        val learnBtn: Button,
        val testBtn: Button,
        val deleteBtn: Button
    )

    private val rowWidgets = mutableMapOf<Int, RowWidgets>()

    // ── Construction ──

    init {
        orientation = VERTICAL
        setupStyle(context)
    }

    private fun setupStyle(context: Context) {
        setPadding(0, 16, 0, 0)
        background = context.getDrawable(R.drawable.card_frame)
    }

    /** Bind the playback service (called from MainActivity.onCreate). */
    fun bindService(service: PlaybackService) {
        this.service = service
    }

    /** Load and display the file list. */
    fun refresh() {
        removeAllViews()

        // Get list of .mid files from external files dir
        val midiDir = File(context.getExternalFilesDir(null), "midi_files")
        val files = midiDir.listFiles { _, name -> name.endsWith(".mid", ignoreCase = true) }
            ?.sortedBy { it.name.lowercase() }
            ?.toList()
            ?: emptyList()

        val assignments = store.all()

        if (files.isEmpty()) {
            val emptyText = TextView(context).apply {
                text = "No MIDI files. Tap [Import .mid] to add one."
                textSize = 12f
                setPadding(12, 12, 12, 12)
                setTextColor(0xFF888888.toInt())
            }
            addView(emptyText, LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(12, 12, 12, 12)
            })
        } else {
            for (file in files) {
                addFileRow(file, assignments)
            }
        }

        // Import button
        addImportButton()

        // Record section
        addRecordSection()
    }

    private fun addFileRow(file: File, assignments: Map<Int, MidiFileAssignment>) {
        // Find assignment for this file path (reverse lookup)
        val assignment = assignments.values.find { it.filePath == file.absolutePath }
        val note = assignment?.note ?: -1

        val row = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(12, 8, 12, 8)
            setBackgroundColor(0xFFF5F5F5.toInt())
            setMinimumHeight(120)
        }

        // Top row: filename, key label, loop, tempo, buttons
        val topRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 4)
        }

        // File name (truncated)
        val nameText = TextView(context).apply {
            text = truncateFileName(file.name)
            textSize = 12f
            setTextColor(0xFF000000.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }

        // Learned key label
        val keyLabel = TextView(context).apply {
            text = if (note >= 0) noteToName(note) else "—"
            textSize = 12f
            setTextColor(0xFF444444.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(8, 0, 8, 0)
            }
        }

        // Loop checkbox
        val loopCheck = CheckBox(context).apply {
            isChecked = assignment?.loop ?: false
            textSize = 11f
        }

        // Tempo edit
        val tempoEdit = EditText(context).apply {
            hint = "Tempo"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(if (assignment != null) assignment.tempo.toInt().toString() else "120")
            layoutParams = LinearLayout.LayoutParams(60, LayoutParams.WRAP_CONTENT)
            textSize = 11f
        }

        // Learn key button
        val learnBtn = Button(context).apply {
            // n1: identical text for both branches — just "Learn key"
            text = "Learn key"
            textSize = 11f
        }

        // Test play/stop button
        val testBtn = Button(context).apply {
            text = "▶ Test"
            textSize = 11f
        }

        // Delete button
        val deleteBtn = Button(context).apply {
            text = "Delete"
            textSize = 11f
        }

        topRow.addView(nameText)
        topRow.addView(keyLabel)
        topRow.addView(loopCheck)
        topRow.addView(tempoEdit)
        topRow.addView(learnBtn)
        topRow.addView(testBtn)
        topRow.addView(deleteBtn)

        // Status line
        val statusText = TextView(context).apply {
            textSize = 10f
            setTextColor(0xFF888888.toInt())
            setPadding(0, 2, 0, 2)
        }

        row.addView(topRow)
        row.addView(statusText)

        // ── Button handlers ──

        learnBtn.setOnClickListener {
            if (note >= 0) {
                // Unlearn: remove mapping and free slot
                store.remove(note)
                onMappingRemove(note)
                rowWidgets.remove(note)
                refresh()
            } else {
                // Enter learn mode
                // m7: cancel any previous learn timeout before starting new one
                cancelLearnTimeout()
                MidiFileLearnState.startLearning { learnedNote ->
                    mainHandler.post {
                        val loop = loopCheck.isChecked
                        val tempoStr = tempoEdit.text.toString()
                        val tempo = try {
                            tempoStr.toDouble().coerceIn(20.0, 300.0)
                        } catch (_: NumberFormatException) {
                            120.0
                        }
                        @Suppress("NAME_SHADOWING")
                        val assignment = MidiFileAssignment(
                            note = learnedNote,
                            filePath = file.absolutePath,
                            loop = loop,
                            tempo = tempo
                        )
                        store.set(assignment)
                        onNoteLearned(learnedNote, file.absolutePath, loop, tempo)
                        refresh()
                    }
                }
                // m7: 10s timeout — cancel learn if no key pressed
                // item 10: check state before toasting to avoid 1ms race
                learnTimeoutRunnable = Runnable {
                    if (MidiFileLearnState.getState() == MidiFileLearnState.State.LEARNING) {
                        MidiFileLearnState.cancel()
                        Toast.makeText(context, "Learn cancelled (timeout)", Toast.LENGTH_SHORT).show()
                    }
                }
                mainHandler.postDelayed(learnTimeoutRunnable!!, 10_000)
                Toast.makeText(context, "Press a key to learn...", Toast.LENGTH_SHORT).show()
            }
        }

        testBtn.setOnClickListener {
            val loop = loopCheck.isChecked
            val tempoStr = tempoEdit.text.toString()
            val tempo = try {
                tempoStr.toDouble().coerceIn(20.0, 300.0)
            } catch (_: NumberFormatException) {
                120.0
            }
            if (assignment != null) {
                // Update persisted settings
                store.set(assignment.copy(loop = loop, tempo = tempo))
                onSettingChange(assignment.note, loop, tempo)
            }
            onTestPlay(
                assignment?.note ?: 0,
                file.absolutePath,
                loop,
                tempo
            )
        }

        deleteBtn.setOnClickListener {
            // Free slot if mapped
            if (note >= 0) {
                store.remove(note)
                onMappingRemove(note)
            }
            onFileDelete(file.absolutePath)
            // Delete the file
            if (file.delete()) {
                mainHandler.post { refresh() }
            }
        }

        // Save settings on change
        loopCheck.setOnCheckedChangeListener { _, isChecked ->
            if (assignment != null) {
                store.set(assignment.copy(loop = isChecked))
                onSettingChange(assignment.note, isChecked, assignment.tempo)
            }
        }

        tempoEdit.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && assignment != null) {
                val tempoStr = tempoEdit.text.toString()
                val tempo = try {
                    tempoStr.toDouble().coerceIn(20.0, 300.0)
                } catch (_: NumberFormatException) {
                    assignment.tempo
                }
                store.set(assignment.copy(tempo = tempo))
                onSettingChange(assignment.note, loopCheck.isChecked, tempo)
            }
        }

        // Store widgets for later updates
        if (note >= 0) {
            rowWidgets[note] = RowWidgets(loopCheck, tempoEdit, learnBtn, testBtn, deleteBtn)
            // item 8c: track test button for label updates
            setTestPlayTarget(note, testBtn)
        }

        // Populate status line from slot info
        if (assignment != null) {
            val svc = service
            if (svc != null) {
                // m4: display tempo with one decimal (n5)
                statusText.text = "loop=${assignment.loop} tempo=${String.format("%.1f", assignment.tempo)}bpm"
            }
        }

        addView(row, LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 4, 0, 4)
        })
    }

    private fun truncateFileName(name: String): String {
        return if (name.length > 25) "${name.substring(0, 22)}..." else name
    }

    private fun addImportButton() {
        val importBtn = Button(context).apply {
            text = "Import .mid"
            textSize = 12f
            setPadding(12, 8, 12, 8)
        }
        importBtn.setOnClickListener {
            onImportClick()
        }
        addView(importBtn, LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 8, 0, 4)
        })
    }

    private fun addRecordSection() {
        val recordRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 0)
        }

        val recordBtn = Button(context).apply {
            text = "Record"
            textSize = 12f
            setPadding(12, 8, 12, 8)
        }
        recordBtn.setOnClickListener {
            // m6: guard against double-tap during export
            if (isRecordingUi.get()) {
                Toast.makeText(context, "Please wait, export in progress...", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // Disable button during export
            isRecordingUi.set(true)
            recordBtn.isEnabled = false
            onRecordClick()
        }

        val recordStatus = TextView(context).apply {
            text = "No recording"
            textSize = 11f
            setTextColor(0xFF888888.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }

        // n3: save direct references to record section widgets
        this.recordBtn = recordBtn
        this.recordStatus = recordStatus

        addView(recordRow, LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 4, 0, 4)
        })
    }

    /** Update record button text and status. */
    fun updateRecordUI(recording: Boolean, eventCount: Int) {
        val btn = recordBtn
        val status = recordStatus
        if (btn != null) {
            btn.text = if (recording) "Stop" else "Record"
        }
        if (status != null) {
            status.text = if (recording) "Recording..."
            else if (eventCount > 0) "$eventCount events recorded"
            else "No recording"
        }
        // item 5 + gate-2 condition: release guard on BOTH start and stop paths —
        // the guard is only for the export window (stop tap -> export complete).
        // (Before: only the stop path released it -> after the start tap the button
        // stayed disabled + guard latched -> user could not stop the recording.)
        if (btn != null) btn.isEnabled = true
        isRecordingUi.set(false)
    }

    /** m7: cancel learn timeout on activity pause. */
    fun cancelLearnTimeout() {
        mainHandler.removeCallbacks(learnTimeoutRunnable ?: return)
        learnTimeoutRunnable = null
    }

    /** item 8c: update test-play button label (▶ Test / ■ Stop). */
    fun updateTestPlayUI(isPlaying: Boolean) {
        val btn = currentTestBtn
        if (btn != null) {
            btn.text = if (isPlaying) "■ Stop" else "▶ Test"
        }
    }

    /** Called when a test-play button is pressed for a specific note. */
    fun setTestPlayTarget(note: Int, btn: Button) {
        currentTestNote = note
        currentTestBtn = btn
    }
}