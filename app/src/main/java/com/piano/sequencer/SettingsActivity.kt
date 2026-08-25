package com.piano.sequencer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.piano.sequencer.service.PlaybackService
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

class SettingsActivity : AppCompatActivity() {

    private lateinit var btnBrowse: Button
    private lateinit var btnUnload: Button
    private lateinit var lvSf2List: ListView
    private lateinit var tvSf2Count: TextView
    // Loaded SF2s: each row {id, name, path}. Backed by the native list.
    private data class LoadedSf2(val id: Int, val name: String, val path: String)
    private val loadedSf2s = mutableListOf<LoadedSf2>()
    private var sf2ListAdapter: Sf2ListAdapter? = null
    private lateinit var tvPolyphony: TextView
    private lateinit var seekBarPolyphony: SeekBar
    private lateinit var tvMasterGain: TextView
    private lateinit var seekBarMasterGain: SeekBar
    private lateinit var tvReverb: TextView
    private lateinit var switchReverb: SwitchCompat
    private lateinit var tvChorus: TextView
    private lateinit var switchChorus: SwitchCompat
    private lateinit var tvInterps: TextView
    private lateinit var spinnerInterps: Spinner
    private lateinit var tvBufferSize: TextView
    private lateinit var seekBarBufferSize: SeekBar
    private lateinit var switchAutoTune: SwitchCompat
    private lateinit var btnPitchBendChannels: Button

    // Interpolation options (Spinner position → FluidSynth interp method).
    private val interpsValues = intArrayOf(0, 1, 4)

    private var service: PlaybackService? = null
    // m3: true while loadCurrentValues() restores the switch state — suppresses
    // the switch listener so a programmatic state change doesn't re-apply the
    // (already-current) auto-tune setting.
    private var isRestoringState = false
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
            // Can be delivered after onDestroy (cold start, user closed the
            // screen during service startup) — the executor is already shut down.
            if (isFinishing || isDestroyed) return
            service = (binder as PlaybackService.PlaybackBinder).getService()
            CompletableFuture.runAsync({
            loadCurrentValues()
        }, mainExecutor)
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    private val mainExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "SettingsMain").apply { isDaemon = true }
    }

    private val sf2Picker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { pickSf2File(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }

        btnBrowse = findViewById(R.id.btnBrowse)
        btnUnload = findViewById(R.id.btnUnload)
        lvSf2List = findViewById(R.id.lvSf2List)
        tvSf2Count = findViewById(R.id.tvSf2Count)
        tvPolyphony = findViewById(R.id.tvPolyphony)
        seekBarPolyphony = findViewById(R.id.seekBarPolyphony)
        tvMasterGain = findViewById(R.id.tvMasterGain)
        seekBarMasterGain = findViewById(R.id.seekBarMasterGain)
        btnPitchBendChannels = findViewById(R.id.btnPitchBendChannels)
        tvReverb = findViewById(R.id.tvReverb)
        switchReverb = findViewById(R.id.switchReverb)
        tvChorus = findViewById(R.id.tvChorus)
        switchChorus = findViewById(R.id.switchChorus)
        tvInterps = findViewById(R.id.tvInterps)
        spinnerInterps = findViewById(R.id.spinnerInterps)
        tvBufferSize = findViewById(R.id.tvBufferSize)
        seekBarBufferSize = findViewById(R.id.seekBarBufferSize)
        switchAutoTune = findViewById(R.id.switchAutoTune)

        // Interpolation spinner (None / Linear / 4th Order → 0 / 1 / 4).
        val interpsLabels = arrayOf(
            getString(R.string.settings_interps_none),
            getString(R.string.settings_interps_linear),
            getString(R.string.settings_interps_4th)
        )
        spinnerInterps.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, interpsLabels)
        spinnerInterps.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val method = interpsValues[position]
                tvInterps.text = getString(R.string.settings_interps_value, interpsLabels[position])
                if (isFinishing || isDestroyed) return
                val svc = service ?: return
                CompletableFuture.runAsync({ svc.setInterps(method) }, mainExecutor)
                    .whenComplete { _, ex ->
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            if (ex == null) {
                                getSharedPreferences("piano_prefs", MODE_PRIVATE).edit()
                                    .putInt("interps", method).apply()
                            }
                        }
                    }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // Reverb / Chorus switches (Fix #10-11).
        switchReverb.setOnCheckedChangeListener { _, checked ->
            tvReverb.text = getString(R.string.settings_reverb_value,
                if (checked) getString(R.string.settings_on) else getString(R.string.settings_off))
            if (isFinishing || isDestroyed) return@setOnCheckedChangeListener
            val svc = service ?: return@setOnCheckedChangeListener
            val on = checked
            CompletableFuture.runAsync({ svc.setReverb(on) }, mainExecutor)
                .whenComplete { _, ex ->
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        if (ex == null) {
                            getSharedPreferences("piano_prefs", MODE_PRIVATE).edit()
                                .putInt("reverb", if (on) 1 else 0).apply()
                        }
                    }
                }
        }
        switchChorus.setOnCheckedChangeListener { _, checked ->
            tvChorus.text = getString(R.string.settings_chorus_value,
                if (checked) getString(R.string.settings_on) else getString(R.string.settings_off))
            if (isFinishing || isDestroyed) return@setOnCheckedChangeListener
            val svc = service ?: return@setOnCheckedChangeListener
            val on = checked
            CompletableFuture.runAsync({ svc.setChorus(on) }, mainExecutor)
                .whenComplete { _, ex ->
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        if (ex == null) {
                            getSharedPreferences("piano_prefs", MODE_PRIVATE).edit()
                                .putInt("chorus", if (on) 1 else 0).apply()
                        }
                    }
                }
        }

        // Auto buffer size (m3): when ON, the Oboe LatencyTuner manages the buffer
        // size (2×burst..8×burst) and the seekbar below is disabled. When OFF,
        // the seekbar sets a fixed buffer size.
        switchAutoTune.setOnCheckedChangeListener { _, checked ->
            if (isRestoringState) return@setOnCheckedChangeListener
            if (isFinishing || isDestroyed) return@setOnCheckedChangeListener
            val svc = service ?: return@setOnCheckedChangeListener
            if (checked) {
                // Auto-tune ON: the LatencyTuner manages the buffer size.
                CompletableFuture.runAsync({ svc.setAutoTune(true) }, mainExecutor)
                    .whenComplete { _, ex ->
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            seekBarBufferSize.isEnabled = false
                            if (ex == null) {
                                getSharedPreferences("piano_prefs", MODE_PRIVATE).edit()
                                    .putInt("auto_tune", 1).apply()
                            }
                        }
                    }
            } else {
                // Auto-tune OFF: use the fixed buffer size from the seekbar.
                val frames = 128 + (seekBarBufferSize.progress) * 128
                CompletableFuture.runAsync({
                    svc.setAutoTune(false)
                    svc.setBufferSizeInFrames(frames)
                }, mainExecutor)
                    .whenComplete { _, ex ->
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            seekBarBufferSize.isEnabled = true
                            if (ex == null) {
                                getSharedPreferences("piano_prefs", MODE_PRIVATE).edit()
                                    .putInt("auto_tune", 0)
                                    .putInt("buffer_size", frames).apply()
                            }
                        }
                    }
            }
        }

        // Buffer size (Fix #4): 128 + progress*128 frames (128..2048). Dragging
        // the seekbar implies a manual buffer size — turn auto-tune off.
        seekBarBufferSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val frames = 128 + progress * 128
                tvBufferSize.text = getString(R.string.settings_buffer_size_value, frames)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                if (isFinishing || isDestroyed) return
                val frames = 128 + (sb?.progress ?: 0) * 128
                val svc = service ?: return@onStopTrackingTouch
                // Dragging the seekbar implies manual buffer size — turn
                // auto-tune off (the switch listener applies the new size).
                if (switchAutoTune.isChecked) {
                    switchAutoTune.isChecked = false
                    return@onStopTrackingTouch
                }
                CompletableFuture.supplyAsync({
                    svc.setBufferSizeInFrames(frames)
                }, mainExecutor)
                    .whenComplete { _, ex ->
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            if (ex == null) {
                                getSharedPreferences("piano_prefs", MODE_PRIVATE).edit()
                                    .putInt("buffer_size", frames).apply()
                            }
                        }
                    }
            }
        })

        btnBrowse.setOnClickListener { sf2Picker.launch("*/*") }

        btnUnload.setOnClickListener { unloadAllSoundFonts() }

        // Loaded-SF2 list adapter: each row shows the font name + an "Unload"
        // button that removes just that one SF2 (arbitrary single unload).
        sf2ListAdapter = Sf2ListAdapter(loadedSf2s) { sf -> unloadSoundFont(sf) }
        lvSf2List.adapter = sf2ListAdapter
        lvSf2List.onItemClickListener = null

        btnPitchBendChannels.setOnClickListener { showPitchBendChannelsDialog() }

        // Plain pref read (no service call) — the mask is a UI-side setting.
        btnPitchBendChannels.text = formatPitchBendChannels(
            getSharedPreferences("piano_prefs", MODE_PRIVATE).getInt("pitch_bend_channels", 1)
        )

        seekBarPolyphony.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                tvPolyphony.text = getString(R.string.settings_polyphony_value, progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                if (isFinishing || isDestroyed) return
                val p = sb?.progress ?: return
                val svc = service ?: return@onStopTrackingTouch
                CompletableFuture.supplyAsync({ svc.setPolyphony(p) }, mainExecutor)
                    .whenComplete { _, ex ->
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            if (ex == null) {
                                Toast.makeText(this@SettingsActivity,
                                    getString(R.string.settings_polyphony_value, p), Toast.LENGTH_SHORT).show()
                                getSharedPreferences("piano_prefs", MODE_PRIVATE).edit()
                                    .putInt("polyphony", p)
                                    .apply()
                            } else {
                                Toast.makeText(this@SettingsActivity,
                                    "Failed to set polyphony", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
            }
        })

        seekBarMasterGain.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val gain = progress / 1000f
                tvMasterGain.text = getString(R.string.settings_master_gain_value, gain)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                if (isFinishing || isDestroyed) return
                val gain = (sb?.progress ?: 0) / 1000f
                val svc = service ?: return@onStopTrackingTouch
                CompletableFuture.supplyAsync({ svc.setMasterGain(gain) }, mainExecutor)
                    .whenComplete { _, ex ->
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            if (ex == null) {
                                Toast.makeText(this@SettingsActivity,
                                    getString(R.string.settings_master_gain_value, gain), Toast.LENGTH_SHORT).show()
                                getSharedPreferences("piano_prefs", MODE_PRIVATE).edit()
                                    .putFloat("master_gain", gain)
                                    .apply()
                            } else {
                                Toast.makeText(this@SettingsActivity,
                                    "Failed to set master gain", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
            }
        })

        Intent(this, PlaybackService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onDestroy() {
        mainExecutor.shutdown()
        super.onDestroy()
        try {
            unbindService(serviceConnection)
        } catch (_: Exception) {
            // ignore if not bound
        }
    }

    private fun loadCurrentValues() {
        val svc = service ?: run {
            runOnUiThread {
                Toast.makeText(this, "Service not connected", Toast.LENGTH_SHORT).show()
            }
            return
        }

        if (!svc.isEngineInitialized()) {
            runOnUiThread {
                Toast.makeText(this, "Engine not initialized", Toast.LENGTH_SHORT).show()
            }
            return
        }

        val polyphony = svc.getPolyphony()
        val gain = svc.getMasterGain()
        val gainProgress = (gain * 1000f).toInt().coerceIn(0, 2000)
        val sf2List = parseLoadedSoundFonts(svc.getLoadedSoundFonts())
        val sf2Count = svc.getSoundFontCount()
        val reverb = svc.getReverb()
        val chorus = svc.getChorus()
        val interps = svc.getInterps()
        val bufferSize = svc.getBufferSizeInFrames()
        // m3: auto-tune is a UI-side pref (default ON = 1).
        val autoTune = getSharedPreferences("piano_prefs", MODE_PRIVATE).getInt("auto_tune", 1)

        runOnUiThread {
            seekBarPolyphony.progress = polyphony.coerceIn(1, 256)
            tvPolyphony.text = getString(R.string.settings_polyphony_value, polyphony)

            seekBarMasterGain.progress = gainProgress
            tvMasterGain.text = getString(R.string.settings_master_gain_value, gain)

            // Reverb / Chorus (Fix #10-11).
            val reverbOn = reverb != 0
            switchReverb.isChecked = reverbOn
            tvReverb.text = getString(R.string.settings_reverb_value,
                if (reverbOn) getString(R.string.settings_on) else getString(R.string.settings_off))
            val chorusOn = chorus != 0
            switchChorus.isChecked = chorusOn
            tvChorus.text = getString(R.string.settings_chorus_value,
                if (chorusOn) getString(R.string.settings_on) else getString(R.string.settings_off))

            // Interpolation (Fix #12): map the method (0/1/4) to a spinner position.
            val interpsPos = interpsValues.indexOf(interps).coerceAtLeast(0)
            spinnerInterps.setSelection(interpsPos)
            tvInterps.text = getString(R.string.settings_interps_value,
                arrayOf(
                    getString(R.string.settings_interps_none),
                    getString(R.string.settings_interps_linear),
                    getString(R.string.settings_interps_4th)
                )[interpsPos])

            // Buffer size (Fix #4): map frames (128..2048) to a seekbar progress.
            val bufProgress = ((bufferSize - 128) / 128).coerceIn(0, 15)
            seekBarBufferSize.progress = bufProgress
            tvBufferSize.text = getString(R.string.settings_buffer_size_value,
                128 + bufProgress * 128)

            // m3: restore the auto-tune switch + seekbar enabled state
            // (suppress the listener so the programmatic change doesn't
            // re-apply the already-current setting).
            isRestoringState = true
            switchAutoTune.isChecked = (autoTune != 0)
            seekBarBufferSize.isEnabled = (autoTune == 0)
            isRestoringState = false

            updateSf2List(sf2List, sf2Count)
        }
    }

    private fun pickSf2File(uri: Uri) {
        val fileName = uri.lastPathSegment
            ?.takeIf { it.contains('/') }
            ?.substringAfterLast('/')
            ?: uri.lastPathSegment
            ?: "soundfont.sf2"

        if (!fileName.endsWith(".sf2", ignoreCase = true) &&
            !fileName.endsWith(".sf3", ignoreCase = true)) {
            Toast.makeText(this, "Only .sf2 and .sf3 files allowed", Toast.LENGTH_SHORT).show()
            return
        }

        btnBrowse.isEnabled = false

        val svc = service
        val loadFuture = CompletableFuture.supplyAsync(java.util.function.Supplier<Int> {
            try {
                val destDir = getExternalFilesDir(null)
                    ?: throw IOException("Cannot access external files directory")

                val destFile = File(destDir, fileName)

                contentResolver.openInputStream(uri).use { input ->
                    if (input == null) throw IOException("Cannot read selected file")
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                }

                val result = svc?.loadSoundFont(destFile.absolutePath) ?: -1
                if (result < 0) {
                    AppLogger.error("SettingsActivity", "Failed to load SF2: $fileName (error: $result)")
                } else {
                    AppLogger.info("SettingsActivity", "Loaded SF2: $fileName (synth ID: $result)")
                }
                result
            } catch (e: Exception) {
                AppLogger.error("SettingsActivity", "SF2 load exception: ${e.message}")
                -1
            }
        }, mainExecutor)
        loadFuture.whenComplete { result, ex ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                btnBrowse.isEnabled = true
                val svcLocal = service
                if (ex == null && result >= 0 && svcLocal != null) {
                    // Refresh the loaded-SF2 list + count from the engine on a
                    // worker thread (JNI must not run on main), then update UI.
                    refreshSf2List(svcLocal)
                    Toast.makeText(this@SettingsActivity, "SoundFont loaded", Toast.LENGTH_SHORT).show()
                } else {
                    val msg = ex?.message ?: getString(R.string.settings_sf2_load_failed)
                    Toast.makeText(this@SettingsActivity, msg, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Reload a single SF2 by its id (arbitrary unload). Worker thread for the
    // JNI call; UI + persistence updated back on main.
    private fun unloadSoundFont(sf: LoadedSf2) {
        val svc = service ?: return
        CompletableFuture.supplyAsync(java.util.function.Supplier<Boolean> {
            try {
                val ok = svc.unloadSoundFont(sf.id)
                if (!ok) {
                    AppLogger.error("SettingsActivity", "Failed to unload SF2: ${sf.name} (id=${sf.id})")
                } else {
                    AppLogger.info("SettingsActivity", "Unloaded SF2: ${sf.name} (id=${sf.id})")
                }
                ok
            } catch (e: Exception) {
                AppLogger.error("SettingsActivity", "SF2 unload exception: ${e.message}")
                false
            }
        }, mainExecutor).whenComplete { ok, ex ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (ok && ex == null) {
                    refreshSf2List(svc)
                } else {
                    Toast.makeText(this@SettingsActivity, getString(R.string.settings_sf2_unload_failed),
                        Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun unloadAllSoundFonts() {
        btnUnload.isEnabled = false

        val svc = service ?: run { btnUnload.isEnabled = true; return }
        CompletableFuture.runAsync({
            svc.unloadSoundFonts()
        }, mainExecutor)
        .whenComplete { _, ex ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                btnUnload.isEnabled = true
                if (ex == null) {
                    AppLogger.info("SettingsActivity", "Unloaded all SoundFonts")
                    refreshSf2List(svc)
                } else {
                    AppLogger.error("SettingsActivity", "Failed to unload SoundFont: ${ex.message}")
                    Toast.makeText(this@SettingsActivity, getString(R.string.settings_sf2_unload_failed),
                        Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Read the current loaded-SF2 list + count from the engine (worker thread)
    // and push them to the UI adapter + persistence.
    private fun refreshSf2List(svc: PlaybackService) {
        CompletableFuture.supplyAsync(java.util.function.Supplier<Pair<List<LoadedSf2>, Int>?> {
            try {
                val list = parseLoadedSoundFonts(svc.getLoadedSoundFonts())
                val count = svc.getSoundFontCount()
                Pair(list, count)
            } catch (e: Exception) {
                AppLogger.error("SettingsActivity", "SF2 list read exception: ${e.message}")
                null
            }
        }, mainExecutor).whenComplete { pair, _ ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (pair != null) {
                    updateSf2List(pair.first, pair.second)
                    persistSf2Paths(pair.first)
                }
            }
        }
    }

    // Replace the in-memory list + count label, then notify the adapter.
    private fun updateSf2List(list: List<LoadedSf2>, count: Int) {
        loadedSf2s.clear()
        loadedSf2s.addAll(list)
        sf2ListAdapter?.notifyDataSetChanged()
        tvSf2Count.text = getString(R.string.settings_sf2_loaded_count, count)
    }

    // Persist the loaded SF2 paths so they are reloaded after process death.
    // Stored as a JSON array under "sf2_paths" (multi-SF2). The legacy single
    // "sf2_path" key is cleared to avoid a stale single-path restore.
    private fun persistSf2Paths(list: List<LoadedSf2>) {
        val arr = JSONArray()
        for (sf in list) {
            arr.put(sf.path)
        }
        getSharedPreferences("piano_prefs", MODE_PRIVATE).edit()
            .remove("sf2_path")
            .putString("sf2_paths", arr.toString())
            .apply()
    }

    // Parse the JSON array returned by nativeGetLoadedSoundFonts:
    // [{"id":int,"path":"..."}, ...] → LoadedSf2 list (name = path basename).
    private fun parseLoadedSoundFonts(json: String): List<LoadedSf2> {
        val result = mutableListOf<LoadedSf2>()
        if (json.isBlank()) return result
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.getInt("id")
                val path = o.getString("path")
                val name = File(path).name
                result.add(LoadedSf2(id, name, path))
            }
        } catch (e: Exception) {
            AppLogger.error("SettingsActivity", "SF2 JSON parse exception: ${e.message}")
        }
        return result
    }

    // ListView adapter for the loaded-SF2 rows. Each row shows the font name
    // and an "Unload" button (removes just that one SF2). The button click is
    // wired per-row; the ListView's own item click is disabled.
    private inner class Sf2ListAdapter(
        private val items: List<LoadedSf2>,
        private val onUnload: (LoadedSf2) -> Unit
    ) : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(parent.context)
                .inflate(R.layout.sf2_list_item, parent, false)
            val sf = items[position]
            view.findViewById<TextView>(R.id.tvSf2ItemName).text = sf.name
            view.findViewById<Button>(R.id.btnSf2ItemUnload).setOnClickListener {
                onUnload(sf)
            }
            return view
        }
    }

    // 16-channel multi-choice dialog for the pitch bend / mod / breath
    // fallback mask (bit i set = channel i active). Plain pref write — this
    // is a UI-side setting, not an engine setting.
    private fun showPitchBendChannelsDialog() {
        val prefs = getSharedPreferences("piano_prefs", MODE_PRIVATE)
        val currentMask = prefs.getInt("pitch_bend_channels", 1)
        val checked = BooleanArray(16) { (currentMask shr it) and 1 == 1 }
        val items = Array(16) { getString(R.string.settings_channel_n, it + 1) }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.settings_pitch_bend_channels_label)
            .setMultiChoiceItems(items, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("OK") { _, _ ->
                val mask = (0 until 16).fold(0) { acc, ch ->
                    if (checked[ch]) acc or (1 shl ch) else acc
                }
                if (mask == 0) {
                    Toast.makeText(this, R.string.settings_pitch_bend_channels_none_selected, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                prefs.edit().putInt("pitch_bend_channels", mask).apply()
                btnPitchBendChannels.text = formatPitchBendChannels(mask)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // "Channel 1" / "Channels 1, 5" / "All channels"
    private fun formatPitchBendChannels(mask: Int): String {
        val channels = (0 until 16).filter { (mask shr it) and 1 == 1 }
        return when {
            channels.isEmpty() -> getString(R.string.settings_channel_n, 1)
            channels.size == 16 -> getString(R.string.settings_all_channels)
            channels.size == 1 -> getString(R.string.settings_channel_n, channels[0] + 1)
            else -> getString(R.string.settings_channels_list,
                channels.joinToString(", ") { (it + 1).toString() })
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        navigateUpTo(Intent(this, MainActivity::class.java))
        return true
    }
}