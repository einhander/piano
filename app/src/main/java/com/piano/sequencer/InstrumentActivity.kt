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
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.piano.sequencer.service.PlaybackService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.Executors

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

    // Deduplicated preset list of the loaded SoundFont (empty when no SF2)
    private var instruments: List<Instrument> = emptyList()

    private var service: PlaybackService? = null
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
            // Can be delivered after onDestroy (cold start, user closed the
            // screen during service startup) — the executor is already shut down.
            if (isFinishing || isDestroyed) return
            service = (binder as PlaybackService.PlaybackBinder).getService()
            refreshInstruments()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "InstrumentsMain").apply { isDaemon = true }
    }

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

        buildChannelRows()

        Intent(this, PlaybackService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
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
                setPadding(dp(8), dp(12), dp(8), dp(12))
            }

            val channelLabel = TextView(this).apply {
                text = if (ch == 9) getString(R.string.instrument_channel_drums)
                       else getString(R.string.instrument_channel, ch + 1)
                textSize = 14f
                setTextColor(resolveColorControlNormal())
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            channelLabel.layoutParams = LinearLayout.LayoutParams(dp(112), LinearLayout.LayoutParams.WRAP_CONTENT)

            val nameLabel = TextView(this).apply {
                text = getString(R.string.instrument_unknown)
                textSize = 14f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            nameLabel.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

            row.addView(channelLabel)
            row.addView(nameLabel)
            row.setOnClickListener { onChannelTapped(ch) }

            rowNameLabels[ch] = nameLabel
            channelList.addView(row)
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
            val current = IntArray(16) { ch -> svc.getChannelProgram(ch) }
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

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.instrument_picker_title, channel + 1))
            .setView(container)
            .show()

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

        container.addView(search)
        container.addView(listView)
        search.requestFocus()
        // requestFocus() alone does not reliably raise the IME on all devices
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        val imm = getSystemService(InputMethodManager::class.java)
        search.post { imm?.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT) }
    }

    // "name (bank N)" when the preset lives outside bank 0, so identical
    // preset names in different banks stay distinguishable
    private fun displayName(instrument: Instrument): String =
        if (instrument.bank != 0) getString(R.string.instrument_bank_suffix, instrument.name, instrument.bank)
        else instrument.name

    // Apply bank+program to the channel live; update the row label on success
    private fun applyInstrument(channel: Int, instrument: Instrument) {
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
