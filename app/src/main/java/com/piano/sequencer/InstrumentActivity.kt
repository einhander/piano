package com.piano.sequencer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.IBinder
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.piano.sequencer.midi.MidiInitialPrograms
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.piano.sequencer.service.PlaybackService
import com.piano.sequencer.midi.FileInstrumentSetStore
import com.piano.sequencer.midi.MidiFileMappingStore
import com.piano.sequencer.midi.MODE_FILE
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Instrument assignment screen. Lists all 16 MIDI channels; tapping a channel
 * opens a picker of every preset in the loaded SoundFont. Selecting a preset
 * applies bank+program to that channel live through the native engine.
 */
class InstrumentActivity : AppCompatActivity() {

    private lateinit var channelList: LinearLayout
    private lateinit var tvHint: TextView

    // Instrument name label for each MIDI channel (0-15)
    private val rowNameLabels = arrayOfNulls<TextView>(16)
    private val noteIndicators = arrayOfNulls<View>(16)
    private val channelRows = arrayOfNulls<View>(16)
    private val effectsButtons = arrayOfNulls<Button>(16)
    private var selectedChannel = -1
    @Volatile private var observingNotes = false
    @Volatile private var nativeChannelMask = 0
    private val noteListener: () -> Unit = {
        runOnUiThread { if (observingNotes) renderHeldNotes() }
    }

    private fun renderHeldNotes() {
        val physical = com.piano.sequencer.midi.HeldMidiNotes.process.snapshot()
        val mask = nativeChannelMask
        physical.forEachIndexed { channel, active ->
            noteIndicators[channel]?.apply {
                visibility = if (active || (mask and (1 shl channel) != 0)) View.VISIBLE else View.INVISIBLE
            }
        }
    }

    override fun onStart() {
        super.onStart()
        observingNotes = true
        com.piano.sequencer.midi.HeldMidiNotes.process.addListener(noteListener)
        maskPoll?.cancel(false)
        pollChannelMask()
        renderHeldNotes()
    }

    override fun onStop() {
        observingNotes = false
        maskPoll?.cancel(false)
        maskPoll = null
        nativeChannelMask = 0
        com.piano.sequencer.midi.HeldMidiNotes.process.removeListener(noteListener)
        super.onStop()
    }

    private fun pollChannelMask() {
        if (!observingNotes) return
        executor.execute {
            nativeChannelMask = service?.getChannelActivityMask() ?: 0
            runOnUiThread { if (observingNotes) renderHeldNotes() }
            if (observingNotes) maskPoll = executor.schedule({ pollChannelMask() }, 80, TimeUnit.MILLISECONDS)
        }
    }

    // Deduplicated preset list of the loaded SoundFont (empty when no SF2)
    private var instruments: List<Instrument> = emptyList()
    private val selectedSetId: String? by lazy { intent.getStringExtra(EXTRA_SET_ID) }
    private val fileSets by lazy { FileInstrumentSetStore.get(this) }

    companion object { const val EXTRA_SET_ID = "instrument_set_id" }

    @Volatile private var service: PlaybackService? = null
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
            // Can be delivered after onDestroy (cold start, user closed the
            // screen during service startup) — the executor is already shut down.
            if (isFinishing || isDestroyed) return
            service = (binder as PlaybackService.PlaybackBinder).getService()
            refreshInstruments()
            refreshRackStates()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "InstrumentsMain").apply { isDaemon = true }
    }
    @Volatile private var maskPoll: ScheduledFuture<*>? = null

    // One entry of the JSON array returned by NativeEngineBridge.nativeGetInstruments()
    private data class Instrument(val name: String, val bank: Int, val program: Int)

    private val json = Json { ignoreUnknownKeys = true }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_instruments)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }

        channelList = findViewById(R.id.channelList)
        tvHint = findViewById(R.id.tvHint)
        val setSelector = Button(this).apply {
            isAllCaps = false
            textSize = 14f
            minHeight = dp(48)
            minimumHeight = dp(48)
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { chooseInstrumentSet() }
        }
        (tvHint.parent as? android.view.ViewGroup)?.let { parent ->
            val index = parent.indexOfChild(tvHint)
            parent.addView(setSelector, index.coerceAtLeast(0) + 1,
                android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                ))
        }

        val setId = selectedSetId
        if (setId != null) {
            val set = fileSets.all().firstOrNull { it.id == setId }
            if (set == null) { Toast.makeText(this, "File instrument set no longer exists", Toast.LENGTH_LONG).show(); finish(); return }
            supportActionBar?.title = set.name
            tvHint.text = "Editing file set · changes stay with this MIDI file"
            setSelector.text = "Instrument set: ${set.name} ▾"
            val resetButton = Button(this).apply {
                text = "Reset from MIDI"
                isAllCaps = false
                textSize = 13f
                minHeight = dp(48)
                minimumHeight = dp(48)
                setPadding(dp(12), 0, dp(12), 0)
                setOnClickListener { confirmResetFromMidi(setId) }
            }
            (tvHint.parent as? android.view.ViewGroup)?.let { parent ->
                parent.addView(resetButton, parent.indexOfChild(setSelector) + 1,
                    android.view.ViewGroup.LayoutParams(-2, -2))
            }
        } else {
            setSelector.text = "Instrument set: Main instruments ▾"
        }
        tvHint.setOnClickListener { chooseInstrumentSet() }

        buildChannelRows()

        Intent(this, PlaybackService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun confirmResetFromMidi(setId: String) {
        AlertDialog.Builder(this)
            .setTitle("Reset instrument set?")
            .setMessage("Replace all 16 channel presets with the initial programs from this MIDI file? Your edits to this file's instrument set will be overwritten.")
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Reset") { _, _ -> resetFromMidi(setId) }
            .show()
    }

    private fun resetFromMidi(setId: String) {
        executor.execute {
            val source = MidiFileMappingStore.get(applicationContext).all()
                .firstOrNull { it.mode == MODE_FILE && it.instrumentSetId == setId }
            val file = source?.filePath?.takeIf { it.isNotBlank() }?.let { java.io.File(it) }
            if (file == null || !file.isFile || !file.canRead()) {
                showResetError("Source MIDI file is missing or unreadable")
                return@execute
            }
            val svc = service
            if (svc == null) {
                showResetError("Playback service is not connected")
                return@execute
            }
            val programs = runCatching { MidiInitialPrograms.fromFile(file) }.getOrNull()
            if (programs == null || programs.size != 16) {
                showResetError("Could not read initial programs from MIDI file")
                return@execute
            }
            val set = fileSets.all().firstOrNull { it.id == setId }
            if (set == null) {
                showResetError("File instrument set no longer exists")
                return@execute
            }
            if (!svc.isEngineInitialized()) {
                showResetError("Audio engine is not initialized")
                return@execute
            }
            val applied = programs.indices.all { ch ->
                val packed = programs[ch]
                svc.setChannelProgram(set.channelBase + ch, packed ushr 8, packed and 0xff)
            }
            if (!applied) {
                showResetError("Could not apply all MIDI programs; instrument set was not saved")
                return@execute
            }
            if (!fileSets.updatePrograms(setId, programs)) {
                showResetError("Could not save reset instrument set")
                return@execute
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                applyInstruments(programs.toIntArray())
                Toast.makeText(this, "Reset all 16 presets from MIDI", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showResetError(message: String) {
        runOnUiThread { if (!isFinishing && !isDestroyed) Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    }

    override fun onResume() {
        super.onResume()
        // Re-read in case a SoundFont was loaded/unloaded in Settings meanwhile
        if (service != null) refreshInstruments()
    }

    override fun onDestroy() {
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

    // Build the 16 channel rows (Ch 1 .. Ch 16) with dividers between them
    private fun buildChannelRows() {
        for (ch in 0 until 16) {
            if (ch > 0) channelList.addView(makeDivider())

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                // selectableItemBackground resolved via the app Light theme
                // (the list_selector_background platform drawable flips with
                // the device system theme: light ripple on white card in dark
                // mode). Note: ContextCompat.getDrawable takes a resource id,
                // not an attr — the attr must be resolved first.
                val selector = TypedValue()
                if (theme.resolveAttribute(android.R.attr.selectableItemBackground, selector, true)) {
                    background = if (selector.resourceId != 0)
                        ContextCompat.getDrawable(this@InstrumentActivity, selector.resourceId)
                    else
                        ColorDrawable(selector.data)
                }
                setPadding(dp(8), dp(8), dp(4), dp(8))
                minimumHeight = dp(64)
                layoutParams = LinearLayout.LayoutParams(-1, -2)
            }

            val channelLabel = TextView(this).apply {
                text = if (ch == 9) getString(R.string.instrument_channel_drums)
                       else getString(R.string.instrument_channel, ch + 1)
                textSize = 14f
                setTextColor(getColor(R.color.ui_muted))
            }
            channelLabel.layoutParams = LinearLayout.LayoutParams(-1, -2)
            channelLabel.textSize = 13f
            channelLabel.setTypeface(null, android.graphics.Typeface.BOLD)

            val nameLabel = TextView(this).apply {
                text = getString(R.string.instrument_unknown)
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                // Wrap the full program name, including at large font sizes.
            }
            nameLabel.layoutParams = LinearLayout.LayoutParams(-1, -2)

            row.addView(View(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(getColor(R.color.ui_active))
                }
                layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(8) }
                visibility = View.INVISIBLE
                contentDescription = getString(R.string.instrument_note_held, ch + 1)
                noteIndicators[ch] = this
            })
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(channelLabel)
                addView(nameLabel)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            val effectsButton = Button(this).apply {
                com.piano.sequencer.ui.UiControlStyle.apply(this)
                text = getString(R.string.ui_effects)
                isAllCaps = false
                textSize = 14f
                minHeight = dp(48)
                minimumHeight = dp(48)
                setPadding(dp(4), 0, dp(4), 0)
                contentDescription = getString(R.string.ui_effects_channel, ch + 1)
                setOnClickListener {
                    startActivity(Intent(this@InstrumentActivity, InstrumentEffectsActivity::class.java)
                        .putExtra(InstrumentEffectsActivity.EXTRA_CHANNEL, ch))
                }
            }
            row.addView(effectsButton, LinearLayout.LayoutParams(dp(84), -2))
            effectsButtons[ch] = effectsButton
            row.setOnClickListener { selectChannel(ch); onChannelTapped(ch) }

            rowNameLabels[ch] = nameLabel
            channelRows[ch] = row
            channelList.addView(row)
        }
        refreshRackStates()
    }

    private fun selectChannel(channel: Int) {
        selectedChannel = channel
        channelRows.forEachIndexed { index, row ->
            row?.setBackgroundColor(if (index == channel) getColor(R.color.ui_selected) else Color.TRANSPARENT)
        }
    }

    private fun refreshRackStates() {
        val svc = service ?: return
        executor.execute {
            val states = (0..15).map { runCatching { svc.isInstrumentRackEnabled(it) }.getOrDefault(false) }
            runOnUiThread { states.forEachIndexed { ch, enabled -> showRackState(ch, enabled) } }
        }
    }

    private fun showRackState(channel: Int, enabled: Boolean) {
        effectsButtons[channel]?.apply {
            // Retain a quiet active-rack cue without adding another control.
            alpha = if (enabled) 1f else 0.78f
            contentDescription = "Effects${if (enabled) ", active rack" else ""}, channel ${channel + 1}. Open effects editor"
        }
    }

    // Fetch preset list + current program of every channel off the UI thread,
    // then populate the rows
    private fun refreshInstruments() {
        val svc = service
        if (svc == null) return

        executor.execute {
            if (!svc.isEngineInitialized()) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    Toast.makeText(this, "Engine not initialized", Toast.LENGTH_SHORT).show()
                }
                return@execute
            }

            val jsonStr = svc.getInstruments()
            val parsed = parseInstruments(jsonStr)
            val current = selectedSetId?.let { id -> fileSets.all().firstOrNull { it.id == id }?.programs?.toIntArray() }
                ?: IntArray(16) { ch -> svc.getChannelProgram(ch) }
            // Diagnostics: pinpoint where the chain breaks when the SF2 is
            // loaded but the list comes back empty.
            AppLogger.info(
                "InstrumentActivity",
                "refresh: sf2 count=${svc.getSoundFontCount()}, json=${jsonStr.length} chars, presets=${parsed.size}"
            )

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                instruments = parsed
                applyInstruments(current)
            }
        }
    }

    // Parse the JSON array and dedupe by (bank, program), keeping the first name
    private fun parseInstruments(jsonStr: String): List<Instrument> {
        if (jsonStr.isBlank()) return emptyList()
        return try {
            val all = json.parseToJsonElement(jsonStr).jsonArray
            val seen = HashSet<Long>()
            val result = ArrayList<Instrument>()
            for (element in all) {
                val obj = element.jsonObject
                val name = obj["name"]?.jsonPrimitive?.content ?: continue
                val bank = obj["bank"]?.jsonPrimitive?.intOrNull ?: 0
                val program = obj["program"]?.jsonPrimitive?.intOrNull ?: 0
                val key = bank.toLong() * 128 + program.toLong()
                if (seen.add(key)) result.add(Instrument(name, bank, program))
            }
            result
        } catch (e: Exception) {
            AppLogger.error("InstrumentActivity", "Failed to parse instrument list: ${e.message}")
            emptyList()
        }
    }

    // Update hint + row labels from the fetched state
    private fun applyInstruments(current: IntArray) {
        if (instruments.isEmpty()) {
            tvHint.text = getString(R.string.instrument_no_sf2)
            for (ch in 0 until 16) {
                rowNameLabels[ch]?.apply {
                    text = getString(R.string.instrument_unknown)
                    alpha = 0.5f
                }
            }
            return
        }

        tvHint.text = getString(R.string.instrument_hint)
        for (ch in 0 until 16) {
            val packed = current[ch]
            val label = if (packed >= 0) {
                val bank = packed shr 8
                val program = packed and 0xFF
                instruments.firstOrNull { it.bank == bank && it.program == program }?.name
                    ?: getString(R.string.instrument_program_fallback)
            } else {
                getString(R.string.instrument_program_fallback)
            }
            rowNameLabels[ch]?.apply {
                text = label
                alpha = 1f
            }
        }
    }

    private fun onChannelTapped(channel: Int) {
        if (service == null) {
            Toast.makeText(this, "Service not connected", Toast.LENGTH_SHORT).show()
            return
        }
        if (instruments.isEmpty()) {
            Toast.makeText(this, getString(R.string.instrument_no_sf2_toast), Toast.LENGTH_SHORT).show()
            return
        }

        val all = instruments
        // Visible subset of [all]; list item positions map into this
        var visible = all

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }

        val search = EditText(this).apply {
            hint = getString(R.string.instrument_search_hint)
            maxLines = 1
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isFocusableInTouchMode = true
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        // Bounded height so the list scrolls on large SoundFonts (thousands of
// presets) without overflowing the screen: cap at 70% of screen height and
// leave room for the dialog title, search box, and container padding.
        val dm = resources.displayMetrics
        val listHeight = (dm.heightPixels * 0.7).toInt()
            .coerceAtMost(dm.heightPixels - dp(200))
            .coerceAtLeast(dp(200))
        val listView = ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                listHeight
            )
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, all.map { displayName(it) })
        listView.adapter = adapter

        // Build the full view hierarchy BEFORE showing the dialog, so the
        // EditText is part of the window when it becomes visible — needed for
        // the soft-input flag below to take effect.
        container.addView(search)
        container.addView(listView)

        // create() (not show()) so we can set the soft-input mode on the
        // window before it is shown. SOFT_INPUT_STATE_ALWAYS_VISIBLE is the
        // forceful variant; the plain _VISIBLE flag is a hint the system may
        // ignore, which is why the keyboard did not appear.
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.instrument_picker_title, channel + 1))
            .setView(container)
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.show()

        listView.setOnItemClickListener { _, _, position, _ ->
            applyInstrument(channel, visible[position])
            dialog.dismiss()
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                val query = s?.toString()?.trim()?.lowercase().orEmpty()
                visible = if (query.isEmpty()) all
                          else all.filter { displayName(it).lowercase().contains(query) }
                adapter.clear()
                adapter.addAll(visible.map { displayName(it) })
                adapter.notifyDataSetChanged()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        search.requestFocus()
        // requestFocus() alone does not reliably raise the IME on all devices;
        // post the show call so it runs after the EditText is attached and
        // laid out.
        val imm = getSystemService(InputMethodManager::class.java)
        search.post { imm?.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT) }
    }

    private fun chooseInstrumentSet() {
        executor.execute {
            val mappings = MidiFileMappingStore.get(applicationContext)
            fileSets.removeUnreferenced(mappings.all().mapNotNull { it.instrumentSetId }.toSet())
            var capacityExhausted = false
            mappings.all().forEach { cell ->
                if (cell.mode == MODE_FILE && cell.filePath.isNotEmpty() && cell.instrumentSetId == null) {
                    val updated = fileSets.createForCell(cell, com.piano.sequencer.midi.MidiInitialPrograms.fromFile(java.io.File(cell.filePath)))
                        ?.copy(trackInstrumentSets = cell.trackInstrumentSets)
                    if (updated == null) capacityExhausted = true else mappings.set(updated)
                }
            }
            val sets = fileSets.all()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (capacityExhausted) Toast.makeText(this, "Could not create instrument sets for all MIDI files: 15-set limit reached", Toast.LENGTH_LONG).show()
                val labels = listOf("Main instruments") + sets.map { it.name }
                AlertDialog.Builder(this).setTitle("Choose instrument set")
                    .setItems(labels.toTypedArray()) { _, which ->
                        val target = if (which == 0) null else sets[which - 1].id
                        startActivity(Intent(this, InstrumentActivity::class.java).apply {
                            if (target != null) putExtra(EXTRA_SET_ID, target)
                        })
                        finish()
                    }.show()
            }
        }
    }

    // "name (bank N)" when the preset lives outside bank 0, so identical
    // preset names in different banks stay distinguishable
    private fun displayName(instrument: Instrument): String =
        if (instrument.bank != 0) getString(R.string.instrument_bank_suffix, instrument.name, instrument.bank)
        else instrument.name

    // Apply bank+program to the channel live; update the row label on success
    private fun applyInstrument(channel: Int, instrument: Instrument) {
        val setId = selectedSetId
        if (setId != null) {
            val set = fileSets.all().firstOrNull { it.id == setId } ?: return
            val packed = (instrument.bank shl 8) or instrument.program
            executor.execute {
                val svc = service
                val applied = svc?.setChannelProgram(set.channelBase + channel, instrument.bank, instrument.program) == true
                val saved = fileSets.updatePreset(setId, channel, packed)
                runOnUiThread {
                    if (applied && saved) rowNameLabels[channel]?.text = instrument.name
                    else Toast.makeText(this, "Could not apply or save file preset", Toast.LENGTH_SHORT).show()
                }
            }
            return
        }
        val svc = service ?: return
        executor.execute {
            val ok = svc.setChannelProgram(channel, instrument.bank, instrument.program)
            // false means the program was not applied (e.g. the preset is not
            // in the loaded SoundFont — FluidSynth then plays a substitute).
            // Re-read the channel to see the resulting state.
            val actual = if (ok) -1 else svc.getChannelProgram(channel)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (ok) {
                    rowNameLabels[channel]?.text = instrument.name
                    AppLogger.info(
                        "InstrumentActivity",
                        "Channel ${channel + 1} → ${instrument.name} (bank ${instrument.bank}, program ${instrument.program})"
                    )
                    // Persist the assignment (packed bank<<8|program) so it
                    // survives process death / restart.
                    getSharedPreferences("piano_prefs", MODE_PRIVATE).edit()
                        .putInt("chan_prog_$channel", (instrument.bank shl 8) or instrument.program)
                        .apply()
                } else {
                    val bank = actual shr 8
                    val program = actual and 0xFF
                    val label = if (actual >= 0) {
                        instruments.firstOrNull { it.bank == bank && it.program == program }?.name
                            ?: getString(R.string.instrument_program_fallback)
                    } else {
                        getString(R.string.instrument_program_fallback)
                    }
                    rowNameLabels[channel]?.text = label
                    AppLogger.error(
                        "InstrumentActivity",
                        "Channel ${channel + 1}: setChannelProgram failed for preset ${instrument.name} (bank ${instrument.bank}, program ${instrument.program})"
                    )
                    Toast.makeText(this, getString(R.string.instrument_apply_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // 1dp divider between rows, matching the settings screen section dividers
    private fun makeDivider(): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
            )
            background = ColorDrawable(resolveColorControlNormal())
        }
    }

    private fun resolveColorControlNormal(): Int {
        val value = TypedValue()
        if (theme.resolveAttribute(android.R.attr.colorControlNormal, value, true)) {
            return if (value.resourceId != 0) ContextCompat.getColor(this, value.resourceId)
            else value.data
        }
        return Color.LTGRAY
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
