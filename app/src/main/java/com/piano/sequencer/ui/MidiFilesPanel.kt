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
import com.piano.sequencer.midi.TRIGGER_PROGRAM_CHANGE
import com.piano.sequencer.midi.TRIGGER_SYSEX
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

    /**
     * Called when the user applies a track selection for a cell: the explicit
     * list of checked track indices (empty = cell silent) and the per-track
     * channel map (track index → 0-15; "From file" tracks omitted).
     */
    var onTrackSelection: (cellId: Int, selectedTracks: List<Int>, trackChannels: Map<Int, Int>, trackInstrumentSets: Map<Int, String>) -> Unit =
        { _, _, _, _ -> }

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

    companion object {
        /**
         * Channel spinner items (FILE mode), shared with [TrackSelectionDialog]:
         * position 0 = "From file", positions 1..16 = "Ch 1".."Ch 16"
         * (internal 0..15 → display 1..16). In CHORD mode the panel swaps
         * position 0 for "As recorded".
         */
        val CHANNEL_ITEMS: List<String> = listOf("From file") + (1..16).map { "Ch $it" }
    }

    // ── Construction ──

    init {
        orientation = VERTICAL
        setupStyle(context)
    }

    private fun setupStyle(context: Context) {
        setPadding(0, 0, 0, 0)
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
            val pad = dpToPx(12, context)
            setPadding(pad, pad, pad, pad)
            background = context.getDrawable(R.drawable.card_frame)
            setMinimumHeight(60)
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, dpToPx(12, context))
            }
        }

        // ── Row 0: cell name (top of the cell, full width) ──
        val row0 = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
            )
        }

        // File name / chord label — full width, above the controls row
        val nameText = TextView(context).apply {
            text = if (cell.mode == MODE_CHORD) {
                val n = cell.chordNotes.size
                if (n > 0) "♪ Chord: $n note" + (if (n == 1) "" else "s") else "♪ Chord: empty"
            } else if (cell.filePath.isNotEmpty()) {
                val name = File(cell.filePath).name
                name
            } else "—"
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            contentDescription = text
            minimumHeight = dpToPx(48, context)
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener {
                androidx.appcompat.app.AlertDialog.Builder(context)
                    .setMessage(text).setPositiveButton(android.R.string.ok, null).show()
            }
            setTextColor(context.getColor(R.color.ui_text))
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
            )
        }

        row0.addView(nameText)

        // ── Row 1: controls ──
        val row1 = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
            )
        }

        // Mode toggle: FILE ↔ CHORD. In CHORD mode loop/tempo and file-only actions
        // (test/import/export) are hidden; channel selection stays available.
        val modeBtn = Button(context).apply {
            text = context.getString(if (cell.mode == MODE_CHORD) R.string.ui_mode_chord else R.string.ui_mode_file)
            textSize = 14f
            minimumHeight = dpToPx(48, context)
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
        // already holds mode + spinner + checkbox + tempo edit, no room for "Pitch bend").
        val keyLabel = TextView(context).apply {
            text = when (cell.triggerType) {
                TRIGGER_CC -> "CC ${cell.ccNumber}"
                TRIGGER_PITCH_BEND -> "PB"
                TRIGGER_PROGRAM_CHANGE -> "PC ${cell.programNumber}"
                TRIGGER_SYSEX -> "SysEx"
                else -> if (cell.note >= 0) noteToName(cell.note) else "—"
            }
            textSize = 14f
            contentDescription = context.getString(R.string.ui_trigger, text)
            setTextColor(context.getColor(R.color.ui_muted))
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(8, 0, 8, 0)
            }
        }

        // Channel spinner
        val channelSpinner = Spinner(context).apply {
            minimumHeight = dpToPx(48, context)
            contentDescription = context.getString(R.string.ui_channel)
            val items = if (cell.mode == MODE_CHORD) listOf("As recorded") + (1..16).map { "Ch $it" } else CHANNEL_ITEMS
            val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, items)
            setAdapter(adapter)
            // Internal 0..15 → display 1..16; -1 → position 0
            setSelection(if (cell.channel == -1) 0 else cell.channel + 1)
        }

        // Loop checkbox
        val loopCheck = CheckBox(context).apply {
            isChecked = cell.loop
            text = context.getString(R.string.ui_loop)
            textSize = 14f
            minimumHeight = dpToPx(48, context)
            minWidth = 0
        }

        // Tempo edit
        val tempoEdit = EditText(context).apply {
            hint = context.getString(R.string.ui_tempo)
            contentDescription = hint
            minimumHeight = dpToPx(48, context)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(cell.tempo.toInt().toString())
            layoutParams = LayoutParams(
                dpToPx(96, context), LayoutParams.WRAP_CONTENT
            )
            textSize = 14f
        }

        val modeRow = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(modeBtn, LayoutParams(0, -2, 1f))
            addView(keyLabel)
        }
        container.addView(row0)
        container.addView(modeRow)
        row1.addView(loopCheck)
        row1.addView(tempoEdit)

        // In chord mode hide file-only controls; channel selection stays available.
        val isChord = cell.mode == MODE_CHORD
        if (isChord) {
            loopCheck.visibility = android.view.View.GONE
            tempoEdit.visibility = android.view.View.GONE
        }

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
            text = context.getString(if (cell.hasTrigger()) R.string.ui_unlearn else R.string.ui_learn)
            textSize = 14f
            minimumHeight = dpToPx(48, context)
            isAllCaps = false
            setPadding(4, 4, 4, 4)
            minWidth = 0
            layoutParams = btnParams().apply { setMargins(0, 0, marginEnd, 0) }
        }

        val testBtn = Button(context).apply {
            text = context.getString(R.string.ui_test)
            textSize = 14f
            minimumHeight = dpToPx(48, context)
            isAllCaps = false
            setPadding(4, 4, 4, 4)
            minWidth = 0
            layoutParams = btnParams().apply { setMargins(0, 0, marginEnd, 0) }
        }

        val importBtn = Button(context).apply {
            text = context.getString(R.string.ui_import)
            textSize = 14f
            minimumHeight = dpToPx(48, context)
            isAllCaps = false
            setPadding(4, 4, 4, 4)
            minWidth = 0
            layoutParams = btnParams().apply { setMargins(0, 0, marginEnd, 0) }
        }

        val removeBtn = Button(context).apply {
            text = context.getString(R.string.ui_delete_cell)
            setTextColor(context.getColor(R.color.ui_danger))
            textSize = 14f
            minimumHeight = dpToPx(48, context)
            isAllCaps = false
            setPadding(4, 4, 4, 4)
            minWidth = 0
            layoutParams = btnParams()
        }

        row2.addView(testBtn)

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
            textSize = 14f
            minimumHeight = dpToPx(48, context)
            isAllCaps = false
            setPadding(4, 4, 4, 4)
            minWidth = 0
            layoutParams = btnParams().apply { setMargins(0, 0, marginEnd, 0) }
        }

        val exportBtn = Button(context).apply {
            text = context.getString(R.string.ui_export)
            textSize = 14f
            minimumHeight = dpToPx(48, context)
            isAllCaps = false
            setPadding(4, 4, 4, 4)
            minWidth = 0
            layoutParams = btnParams()
        }

        // Tracks: multi-track selection + per-track channel. Starts hidden;
        // shown only for FILE cells whose file has a readable track list with
        // ≥2 tracks (fetched off the UI thread — single-track files keep the
        // channel spinner as the only control).
        val tracksBtn = Button(context).apply {
            text = context.getString(R.string.ui_tracks)
            textSize = 14f
            minimumHeight = dpToPx(48, context)
            isAllCaps = false
            setPadding(4, 4, 4, 4)
            minWidth = 0
            layoutParams = btnParams()
            visibility = android.view.View.GONE
        }

        recordBtn.setOnClickListener {
            onCellRecordClick(cell.id)
        }

        exportBtn.setOnClickListener {
            onCellExportClick(cell.id)
        }

        row2.addView(recordBtn)
        row2.addView(learnBtn)
        container.addView(channelSpinner, LayoutParams(-1, -2))
        container.addView(row1)
        row3.addView(importBtn)
        row3.addView(exportBtn)
        val fileActions = LinearLayout(context).apply {
            addView(tracksBtn)
            addView(removeBtn)
        }

        // Export is file-only (chord has no .mid to export). Tracks is
        // file-only too (chord has no .mid to select tracks in).
        if (isChord) {
            exportBtn.visibility = android.view.View.GONE
            tracksBtn.visibility = android.view.View.GONE
        }

        if (!isChord && cell.filePath.isNotEmpty()) {
            val path = cell.filePath
            var trackNames: List<String>? = null
            MidiFileTriggerController.get(context).fetchTrackNames(path) { names ->
                // The row may have been rebuilt or the file re-imported since
                // the fetch started — only show the button when the cell still
                // points at this file and the list is usable (≥2 tracks).
                if (names != null && names.size >= 2 && store.get(cell.id)?.filePath == path) {
                    trackNames = names.toList()
                    tracksBtn.visibility = android.view.View.VISIBLE
                    // Multi-track file: per-track channels live in the Tracks
                    // dialog, so the cell-level spinner is hidden. Same signal
                    // and same callback as the button — the two stay in sync
                    // by construction (a rebuilt row starts with the spinner
                    // visible again, matching the button's GONE default).
                    channelSpinner.visibility = android.view.View.GONE
                }
            }
            tracksBtn.setOnClickListener {
                val names = trackNames ?: return@setOnClickListener
                // CRITICAL: re-read from store
                val cur = store.get(cell.id) ?: return@setOnClickListener
                if (cur.filePath != path || cur.mode != MODE_FILE) return@setOnClickListener
                TrackSelectionDialog.show(
                    context, names, cur.selectedTracks, cur.trackChannels, cur.trackInstrumentSets,
                    com.piano.sequencer.midi.FileInstrumentSetStore.get(context).all().firstOrNull { it.id == cur.instrumentSetId }?.name
                ) { selected, channels, sets ->
                    onTrackSelection(cur.id, selected, channels, sets)
                }
            }
        }

        container.addView(row3, LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, dpToPx(2, context), 0, 0)
        })
        container.addView(fileActions)

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
                store.set(cur.copy(triggerType = TRIGGER_NOTE, ccNumber = null, programNumber = null, sysexBytes = emptyList(), note = -1))
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
                Toast.makeText(context, "Press a key/pad or move a controller to learn...", Toast.LENGTH_SHORT).show()
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
            text = context.getString(R.string.ui_add_cell)
            textSize = 14f
            minimumHeight = dpToPx(48, context)
            isAllCaps = false
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
                    btn.text = context.getString(R.string.ui_stop)
                    btn.setBackgroundColor(context.getColor(R.color.ui_danger))
                    btn.setTextColor(context.getColor(R.color.ui_surface))
                    btn.isEnabled = true
                }
                recordingCellId != null -> {
                    btn.text = recordLabel()
                    btn.background = cellRecordButtonDefaults[id]
                    btn.setTextColor(context.getColor(R.color.ui_primary))
                    btn.isEnabled = false
                }
                else -> {
                    btn.text = recordLabel()
                    btn.background = cellRecordButtonDefaults[id]
                    btn.setTextColor(context.getColor(R.color.ui_primary))
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
                cellTestButtons[cellId]?.text = context.getString(R.string.ui_stop)
            }
        } else {
            for (btn in cellTestButtons.values) {
                btn.text = context.getString(R.string.ui_test)
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
        val s = SpannableString(context.getString(R.string.ui_record))
        s.setSpan(ForegroundColorSpan(context.getColor(R.color.ui_danger)), 0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        return s
    }
}
