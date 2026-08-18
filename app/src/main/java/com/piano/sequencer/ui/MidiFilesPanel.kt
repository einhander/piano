package com.piano.sequencer.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
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
import com.piano.sequencer.midi.SequencerCell
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

    /** Called when a note is learned (saved to store). */
    var onNoteLearned: (cell: SequencerCell, note: Int) -> Unit = { _, _ -> }

    /** Called when a mapping is removed (also frees slot). */
    var onNoteUnlearned: (note: Int) -> Unit = {}

    /** Called when a cell is removed (file stays on disk). */
    var onCellRemove: (cell: SequencerCell) -> Unit = {}

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

    /** item 8c: direct reference to per-row test button for label update. */
    private var currentTestBtn: Button? = null

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

        // File name
        val nameText = TextView(context).apply {
            text = if (cell.filePath.isNotEmpty()) {
                val name = File(cell.filePath).name
                if (name.length > 25) "${name.substring(0, 22)}..." else name
            } else "—"
            textSize = 12f
            setTextColor(0xFF000000.toInt())
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }

        // Key label
        val keyLabel = TextView(context).apply {
            text = if (cell.note >= 0) noteToName(cell.note) else "—"
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
        row1.addView(keyLabel)
        row1.addView(channelSpinner)
        row1.addView(loopCheck)
        row1.addView(tempoEdit)

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

        container.addView(row2)

        // ── Row 3: per-cell record + export ──
        val row3 = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
            )
        }

        val dot = context.getDrawable(R.drawable.ic_record_dot)

        val recordBtn = Button(context).apply {
            text = "Record"
            textSize = 10f
            setPadding(4, 4, 4, 4)
            minWidth = 0
            layoutParams = btnParams().apply { setMargins(0, 0, marginEnd, 0) }
            setCompoundDrawablesWithIntrinsicBounds(dot, null, null, null)
            setCompoundDrawablePadding(dpToPx(4, context))
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

        learnBtn.setOnClickListener {
            if (cell.note >= 0) {
                // UNLEARN
                val cur = store.get(cell.id) ?: return@setOnClickListener
                store.set(cur.copy(note = -1))
                onNoteUnlearned(cell.note)
            } else {
                // LEARN
                cancelLearnTimeout()
                MidiFileLearnState.startLearning { learnedNote ->
                    mainHandler.post {
                        // CRITICAL: re-read from store to avoid stale capture
                        val cur = store.get(cell.id) ?: return@post
                        // NOTE UNIQUENESS: ensure no other cell claims this note
                        for (c2 in store.all()) {
                            if (c2.note == learnedNote && c2.id != cur.id) {
                                store.set(c2.copy(note = -1))
                            }
                        }
                        val updated = cur.copy(note = learnedNote)
                        store.set(updated)
                        onNoteLearned(updated, learnedNote)
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
                Toast.makeText(context, "Press a key to learn...", Toast.LENGTH_SHORT).show()
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
            if (updated.note >= 0) {
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
            onCellRemove(cur)
        }

        // Save settings on change
        loopCheck.setOnCheckedChangeListener { _, isChecked ->
            val cur = store.get(cell.id) ?: return@setOnCheckedChangeListener
            val updated = cur.copy(loop = isChecked)
            store.set(updated)
            if (updated.note >= 0) {
                onSettingChange(updated)
            }
        }

        tempoEdit.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val cur = store.get(cell.id) ?: return@setOnFocusChangeListener
                val updated = cur.copy(tempo = getTempo())
                store.set(updated)
                if (updated.note >= 0) {
                    onSettingChange(updated)
                }
            }
        }

        channelSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val cur = store.get(cell.id) ?: return@onItemSelected
                val updated = cur.copy(channel = getChannel())
                store.set(updated)
                if (updated.note >= 0) {
                    onSettingChange(updated)
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // item 8c: track test button for label updates (last row with file wins)
        if (cell.filePath.isNotEmpty()) {
            setTestPlayTarget(testBtn)
        }

        // Track record button (+ its theme-default background for state restore)
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
                    btn.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null)
                    btn.setBackgroundColor(0xFFDC1414.toInt())
                    btn.setTextColor(0xFFFFFFFF.toInt())
                    btn.isEnabled = true
                }
                recordingCellId != null -> {
                    btn.text = "Record"
                    val dot = context.getDrawable(R.drawable.ic_record_dot)
                    btn.setCompoundDrawablesWithIntrinsicBounds(dot, null, null, null)
                    btn.background = cellRecordButtonDefaults[id]
                    btn.setTextColor(0xFF000000.toInt())
                    btn.isEnabled = false
                }
                else -> {
                    btn.text = "Record"
                    val dot = context.getDrawable(R.drawable.ic_record_dot)
                    btn.setCompoundDrawablesWithIntrinsicBounds(dot, null, null, null)
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

    /** item 8c: update test-play button label (▶ Test / ■ Stop). */
    fun updateTestPlayUI(isPlaying: Boolean) {
        val btn = currentTestBtn
        if (btn != null) {
            btn.text = if (isPlaying) "■ Stop" else "▶ Test"
        }
    }

    /** Called when a test-play button is pressed for a specific cell. */
    fun setTestPlayTarget(btn: Button) {
        currentTestBtn = btn
    }

    private fun dpToPx(dp: Int, context: Context): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }
}