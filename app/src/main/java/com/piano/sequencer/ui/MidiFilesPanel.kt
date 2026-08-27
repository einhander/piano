package com.piano.sequencer.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.piano.sequencer.R
import com.piano.sequencer.midi.MidiFileLearnState
import com.piano.sequencer.midi.MidiFileMappingStore
import com.piano.sequencer.midi.MidiFileTriggerController
import com.piano.sequencer.midi.MODE_CHORD
import com.piano.sequencer.midi.MODE_FILE
import com.piano.sequencer.midi.SequencerCell
import com.piano.sequencer.midi.TRIGGER_CC
import com.piano.sequencer.midi.TRIGGER_NOTE
import com.piano.sequencer.midi.TRIGGER_PITCH_BEND
import com.piano.sequencer.midi.noteToName
import java.io.File

/**
 * Programmatic LinearLayout panel for MIDI file cell management.
 *
 * Displays a list of sequencer cells (SequencerCell) with per-cell controls:
 * learned key label, channel spinner, loop checkbox, tempo edit, learn, test,
 * import, remove, per-cell record (red dot), and export buttons.
 *
 * House style: all views created programmatically, no layout XML.
 *
 * The store is injected by the hosting activity (SequencerActivity) constructor.
 * m7: learn mode timeout (10s) via Handler.postDelayed.
 */
class MidiFilesPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    // ── Callbacks set by the hosting activity ──

    /** Called when user wants to import a .mid file via GetContent picker. */
    var onImportClick: (cellId: Int) -> Unit = {}

    /** Called when the per-cell record button is pressed (start/stop). */
    var onCellRecordClick: (cellId: Int) -> Unit = {}

    /** Called when the per-cell export button is pressed. */
    var onCellExportClick: (cellId: Int) -> Unit = {}

    /** Called when a test-play button is pressed. */
    var onTestPlay: (cell: SequencerCell) -> Unit = {}

    /** Called when loop/tempo/channel settings change for a mapped cell. */
    var onSettingChange: (cell: SequencerCell) -> Unit = {}

    /** Called when a trigger is learned (saved to store). Second arg = encoded trigger key
     * (raw note for NOTE cells, 128+cc for CC, 256 for PITCH_BEND — see SequencerCell.triggerKey). */
    var onNoteLearned: (cell: SequencerCell, key: Int) -> Unit = { _, _ -> }

    /** Called when a trigger is unlearned (also frees slot). Arg = encoded trigger key. */
    var onNoteUnlearned: (key: Int) -> Unit = {}

    /** Called when a cell is removed (file stays on disk). */
    var onCellRemove: (cell: SequencerCell) -> Unit = {}

    /** Called when the user toggles a cell's mode (FILE ↔ CHORD). */
    var onModeToggle: (cellId: Int, newMode: String) -> Unit = { _, _ -> }

    // ── State ──

    /** M2: store injected by hosting activity, not created here. */
    private lateinit var store: MidiFileMappingStore

    /** Injected from hosting activity constructor. */
    constructor(context: Context, store: MidiFileMappingStore) : this(context) {
        this.store = store
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /** m7: handler for learn timeout. */
    private var learnTimeoutRunnable: Runnable? = null

    /** Per-row test-play buttons keyed by cell id (label: ▶ Test / ■ Stop). */
    private val cellTestButtons = mutableMapOf<Int, Button>()

    /** Per-row record buttons keyed by cell id. */
    private val cellRecordButtons = mutableMapOf<Int, Button>()

    /** Theme-default background per record button, restored when the button is not active. */
    private val cellRecordButtonDefaults = mutableMapOf<Int, Drawable?>()

    // ── Channel spinner items ──
    // 1-based: "From file" + "Ch 1".."Ch 16" (internal 0..15 → display 1..16)
    private val channelItems = listOf("From file") + (1..16).map { "Ch $it" }

    // ── Construction ──

    init {
        orientation = VERTICAL
        setupStyle(context)
    }

    private fun setupStyle(context: Context) {
        setPadding(0, 16, 0, 0)
        background = context.getDrawable(R.drawable.card_frame)
    }

    /** Load and display the cell list. */
    fun refresh() {
        removeAllViews()
        cellTestButtons.clear()
        cellRecordButtons.clear()
        cellRecordButtonDefaults.clear()

        // ONE-TIME legacy backfill: scan midi_files dir, add files not yet in any cell
        if (store.needsLegacyBackfill()) {
            val midiDir = File(context.getExternalFilesDir(null), "midi_files")
            val midFiles = midiDir.listFiles { _, name -> name.endsWith(".mid", ignoreCase = true) }
                ?.toList()
                ?: emptyList()
            val existingPaths = store.all().map { it.filePath }.toSet()
            for (file in midFiles) {
                if (file.absolutePath !in existingPaths) {
                    store.set(SequencerCell(id = store.nextId(), filePath = file.absolutePath))
                }
            }
        }
        store.markBackfillDone()

        val cells = store.all()

        if (cells.isEmpty()) {
            val emptyText = TextView(context).apply {
                text = "No cells. Tap [＋ Add cell] to add one."
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
            for (cell in cells) {
                addCellRow(cell)
            }
        }

        // "＋ Add cell" button
        addAddCellButton()

        // Re-apply live test-play state: rows were rebuilt with "▶ Test" labels,
        // so if a test-play is active the pressed cell's button must show "■ Stop".
        val (tpCellId, tpPlaying) = MidiFileTriggerController.get(context).testPlayState()
        if (tpPlaying) {
            updateTestPlayUI(tpCellId, true)
        }
    }

    private fun addCellRow(cell: SequencerCell) {
        val container = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(8, 4, 8, 4)
            setBackgroundColor(0xFFF5F5F5.toInt())
            setMinimumHeight(60)
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 4, 0, 4)
            }
        }

        // ── Row 1: controls ──
        val row1 = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
            )
        }

        // File name / chord label
        val nameText = TextView(context).apply {
            text = if (cell.mode == MODE_CHORD) {
                val n = cell.chordNotes.size
                if (n > 0) "♪ Chord: $n note" + (if (n == 1) "" else "s") else "♪ Chord: empty"
            } else if (cell.filePath.isNotEmpty()) {
                val name = File(cell.filePath).name
                if (name.length > 25) "${name.substring(0, 22)}..." else name
            } else "—"
            textSize = 12f
            setTextColor(0xFF000000.toInt())
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }

        // Mode toggle: FILE ↔ CHORD. In chord mode the file-specific controls
        // (channel, loop, tempo, import, export, test) are irrelevant and hidden.
        val modeBtn = Button(context).apply {
            text = if (cell.mode == MODE_CHORD) "Mode: Chord" else "Mode: File"
            textSize = 9f
            setPadding(4, 4, 4, 4)
            minWidth = 0
            setAllCaps(false)
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 4, 0)
            }
        }

        // Key label: NOTE → note name; CC → "CC <n>"; PITCH_BEND → "PB" (short — the row
        // already holds name + spinner + checkbox + tempo edit, no room for "Pitch bend").
        val keyLabel = TextView(context).apply {
            text = when (cell.triggerType) {
                TRIGGER_CC -> "CC ${cell.ccNumber}"
                TRIGGER_PITCH_BEND -> "PB"
                else -> if (cell.note >= 0) noteToName(cell.note) else "—"
            }
            textSize = 12f
            setTextColor(0xFF444444.toInt())
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(8, 0, 8, 0)
            }
        }

        // Channel spinner
        val channelSpinner = Spinner(context).apply {
            val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, channelItems)
            setAdapter(adapter)
            // Internal 0..15 → display 1..16; -1 → position 0
            setSelection(if (cell.channel == -1) 0 else cell.channel + 1)
        }

        // Loop checkbox
        val loopCheck = CheckBox(context).apply {
            isChecked = cell.loop
            textSize = 11f
            minWidth = 0
        }

        // Tempo edit
        val tempoEdit = EditText(context).apply {
            hint = "Tempo"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(cell.tempo.toInt().toString())
            layoutParams = LayoutParams(
                dpToPx(56, context), LayoutParams.WRAP_CONTENT
            )
            textSize = 11f
        }

        row1.addView(nameText)
        row1.addView(modeBtn)
        row1.addView(keyLabel)
        row1.addView(channelSpinner)
        row1.addView(loopCheck)
        row1.addView(tempoEdit)

        // In chord mode hide the file-specific controls (they have no meaning).
        val isChord = cell.mode == MODE_CHORD
        if (isChord) {
            channelSpinner.visibility = android.view.View.GONE
            loopCheck.visibility = android.view.View.GONE
            tempoEdit.visibility = android.view.View.GONE
        }

        container.addView(row1, LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 0, 0, dpToPx(4, context))
        })

        // ── Row 2: buttons ──
        val row2 = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
            )
        }

        fun btnParams(): LayoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)

        val marginEnd = dpToPx(4, context)

        val learnBtn = Button(context).apply {
            text = "Learn"
            textSize = 10f
            setPadding(4, 4, 4, 4)
            minWidth = 0
            layoutParams = btnParams().apply { setMargins(0, 0, marginEnd, 0) }
        }

        val testBtn = Button(context).apply {
            text = "▶ Test"
            textSize = 10f
            setPadding(4, 4, 4, 4)
            minWidth = 0
            layoutParams = btnParams().apply { setMargins(0, 0, marginEnd, 0) }
        }

        val importBtn = Button(context).apply {
            text = "Import .mid"
            textSize = 10f
            setPadding(4, 4, 4, 4)
            minWidth = 0
            layoutParams = btnParams().apply { setMargins(0, 0, marginEnd, 0) }
        }

        val removeBtn = Button(context).apply {
            text = "−"
            textSize = 10f
            setPadding(4, 4, 4, 4)
            minWidth = 0
            layoutParams = btnParams()
        }

        row2.addView(learnBtn)
        row2.addView(testBtn)
        row2.addView(importBtn)
        row2.addView(removeBtn)

        // File-only buttons are meaningless in chord mode.
        if (isChord) {
            testBtn.visibility = android.view.View.GONE
            importBtn.visibility = android.view.View.GONE
        }

        container.addView(row2)

        // ── Row 3: per-cell record + export ──
        val row3 = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
            )
        }

        val recordBtn = Button(context).apply {
            text = recordLabel()
            textSize = 10f
            setPadding(4, 4, 4, 4)
            minWidth = 0
            layoutParams = btnParams().apply { setMargins(0, 0, marginEnd, 0) }
        }

        val exportBtn = Button(context).apply {
            text = "Export"
            textSize = 10f
            setPadding(4, 4, 4, 4)
            minWidth = 0
            layoutParams = btnParams()
        }

        recordBtn.setOnClickListener {
            onCellRecordClick(cell.id)
        }

        exportBtn.setOnClickListener {
            onCellExportClick(cell.id)
        }

        row3.addView(recordBtn)
        row3.addView(exportBtn)

        // Export is file-only (chord has no .mid to export).
        if (isChord) {
            exportBtn.visibility = android.view.View.GONE
        }

        container.addView(row3, LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, dpToPx(2, context), 0, 0)
        })

        // ── Value readers ──
        fun getChannel(): Int {
            return if (channelSpinner.selectedItemPosition == 0) -1
            else channelSpinner.selectedItemPosition - 1
        }

        fun getLoop(): Boolean = loopCheck.isChecked

        fun getTempo(): Double {
            val str = tempoEdit.text.toString()
            return try { str.toDouble().coerceIn(20.0, 300.0) } catch (_: NumberFormatException) { 120.0 }
        }

        // ── Button handlers ──

        modeBtn.setOnClickListener {
            val cur = store.get(cell.id) ?: return@setOnClickListener
            // Stop any sounding chord before leaving chord mode; free the slot
            // when leaving either mode so the trigger re-binds cleanly.
            val newMode = if (cur.mode == MODE_CHORD) MODE_FILE else MODE_CHORD
            onModeToggle(cur.id, newMode)
            refresh()
        }

        learnBtn.setOnClickListener {
            if (cell.hasTrigger()) {
                // UNLEARN (note, CC, or pitch bend): clear the trigger, free the slot
                // via the encoded trigger key (works for all three trigger types).
                val cur = store.get(cell.id) ?: return@setOnClickListener
                val key = cur.triggerKey()
                store.set(cur.copy(triggerType = TRIGGER_NOTE, ccNumber = null, note = -1))
                onNoteUnlearned(key)
            } else {
                // LEARN: first event of ANY type wins (note, CC, or pitch bend).
                cancelLearnTimeout()
                MidiFileLearnState.startLearning { event ->
                    mainHandler.post {
                        // CRITICAL: re-read from store to avoid stale capture;
                        // applyLearnedTrigger also enforces trigger uniqueness
                        // (one trigger → one cell) across all other cells.
                        val updated = store.applyLearnedTrigger(cell.id, event) ?: return@post
                        onNoteLearned(updated, updated.triggerKey())
                    }
                }
                // m7: 10s timeout
                learnTimeoutRunnable = Runnable {
                    if (MidiFileLearnState.getState() == MidiFileLearnState.State.LEARNING) {
                        MidiFileLearnState.cancel()
                        Toast.makeText(context, "Learn cancelled (timeout)", Toast.LENGTH_SHORT).show()
                    }
                }
                mainHandler.postDelayed(learnTimeoutRunnable!!, 10_000)
                Toast.makeText(context, "Press a key or move a controller to learn...", Toast.LENGTH_SHORT).show()
            }
        }

        testBtn.setOnClickListener {
            // CRITICAL: re-read from store
            val cur = store.get(cell.id) ?: return@setOnClickListener
            if (cur.filePath.isEmpty()) {
                Toast.makeText(context, "No file assigned", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val updated = cur.copy(loop = getLoop(), tempo = getTempo(), channel = getChannel())
            store.set(updated)
            if (updated.hasTrigger()) {
                onSettingChange(updated)
            }
            onTestPlay(updated)
        }

        importBtn.setOnClickListener {
            onImportClick(cell.id)
        }

        removeBtn.setOnClickListener {
            // CRITICAL: re-read from store
            val cur = store.get(cell.id) ?: return@setOnClickListener
            // The activity frees the trigger slot (encoded key) AFTER its
            // recording guard passes — a blocked removal keeps the cell AND its slot.
            onCellRemove(cur)
        }

        // Save settings on change
        loopCheck.setOnCheckedChangeListener { _, isChecked ->
            val cur = store.get(cell.id) ?: return@setOnCheckedChangeListener
            val updated = cur.copy(loop = isChecked)
            store.set(updated)
            if (updated.hasTrigger()) {
                onSettingChange(updated)
            }
        }

        tempoEdit.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val cur = store.get(cell.id) ?: return@setOnFocusChangeListener
                val updated = cur.copy(tempo = getTempo())
                store.set(updated)
                if (updated.hasTrigger()) {
                    onSettingChange(updated)
                }
            }
        }

        channelSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val cur = store.get(cell.id) ?: return@onItemSelected
                val updated = cur.copy(channel = getChannel())
                store.set(updated)
                if (updated.hasTrigger()) {
                    onSettingChange(updated)
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // Track test-play + record buttons (keyed by cell id)
        cellTestButtons[cell.id] = testBtn
        cellRecordButtons[cell.id] = recordBtn
        cellRecordButtonDefaults[cell.id] = recordBtn.background

        addView(container)
    }

    private fun addAddCellButton() {
        val addBtn = Button(context).apply {
            text = "＋ Add cell"
            textSize = 12f
            setPadding(dpToPx(12, context), dpToPx(8, context), dpToPx(12, context), dpToPx(8, context))
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, dpToPx(8, context), 0, dpToPx(4, context))
            }
        }
        addBtn.setOnClickListener {
            store.set(SequencerCell(id = store.nextId()))
            refresh()
        }
        addView(addBtn)
    }

    /** Update per-cell record button states. recordingCellId == null → all idle. */
    fun updateCellRecordState(recordingCellId: Int?) {
        for ((id, btn) in cellRecordButtons) {
            when {
                id == recordingCellId -> {
                    btn.text = "■ Stop"
                    btn.setBackgroundColor(0xFFDC1414.toInt())
                    btn.setTextColor(0xFFFFFFFF.toInt())
                    btn.isEnabled = true
                }
                recordingCellId != null -> {
                    btn.text = recordLabel()
                    btn.background = cellRecordButtonDefaults[id]
                    btn.setTextColor(0xFF000000.toInt())
                    btn.isEnabled = false
                }
                else -> {
                    btn.text = recordLabel()
                    btn.background = cellRecordButtonDefaults[id]
                    btn.setTextColor(0xFF000000.toInt())
                    btn.isEnabled = true
                }
            }
        }
    }

    /** m7: cancel learn timeout on activity pause. */
    fun cancelLearnTimeout() {
        mainHandler.removeCallbacks(learnTimeoutRunnable ?: return)
        learnTimeoutRunnable = null
        MidiFileLearnState.cancel()
    }

    /**
     * Update test-play button labels (▶ Test / ■ Stop).
     * Playing: label the pressed cell's button (no-op if the cell row is gone).
     * Stopped: reset ALL test buttons to "▶ Test" (covers the null-cellId stop case).
     */
    fun updateTestPlayUI(cellId: Int?, isPlaying: Boolean) {
        if (isPlaying) {
            if (cellId != null) {
                cellTestButtons[cellId]?.text = "■ Stop"
            }
        } else {
            for (btn in cellTestButtons.values) {
                btn.text = "▶ Test"
            }
        }
    }

    private fun dpToPx(dp: Int, context: Context): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }

    /**
     * "● Record" with a red dot — same pattern as "▶ Test" / "■ Stop"
     * (glyph in the text, so the dot always sits next to the word, not at
     * the button edge).
     */
    private fun recordLabel(): CharSequence {
        val s = SpannableString("● Record")
        s.setSpan(ForegroundColorSpan(0xFFDC1414.toInt()), 0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        return s
    }
}