package com.piano.sequencer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.piano.sequencer.service.PlaybackService
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

class SettingsActivity : AppCompatActivity() {

    private lateinit var btnBrowse: Button
    private lateinit var btnUnload: Button
    private lateinit var tvSf2Path: TextView
    private lateinit var tvSf2Count: TextView
    private lateinit var tvPolyphony: TextView
    private lateinit var seekBarPolyphony: SeekBar
    private lateinit var tvMasterGain: TextView
    private lateinit var seekBarMasterGain: SeekBar
    private lateinit var btnPitchBendChannels: Button

    private var service: PlaybackService? = null
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
        tvSf2Path = findViewById(R.id.tvSf2Path)
        tvSf2Count = findViewById(R.id.tvSf2Count)
        tvPolyphony = findViewById(R.id.tvPolyphony)
        seekBarPolyphony = findViewById(R.id.seekBarPolyphony)
        tvMasterGain = findViewById(R.id.tvMasterGain)
        seekBarMasterGain = findViewById(R.id.seekBarMasterGain)
        btnPitchBendChannels = findViewById(R.id.btnPitchBendChannels)

        btnBrowse.setOnClickListener { sf2Picker.launch("*/*") }

        btnUnload.setOnClickListener { unloadSoundFont() }

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
        val sf2Path = svc.getSoundFontPath()
        val sf2Count = svc.getSoundFontCount()

        runOnUiThread {
            seekBarPolyphony.progress = polyphony.coerceIn(1, 256)
            tvPolyphony.text = getString(R.string.settings_polyphony_value, polyphony)

            seekBarMasterGain.progress = gainProgress
            tvMasterGain.text = getString(R.string.settings_master_gain_value, gain)

            if (sf2Path.isEmpty()) {
                tvSf2Path.text = getString(R.string.settings_sf2_none)
            } else {
                tvSf2Path.text = File(sf2Path).name
            }
            tvSf2Count.text = getString(R.string.settings_sf2_loaded_count, sf2Count)
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
            // Read the SF count on the worker thread (JNI must not run on the
            // main thread); pass it into the UI update.
            val count = if (ex == null && result >= 0) (service?.getSoundFontCount() ?: 0) else 0
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                btnBrowse.isEnabled = true
                if (ex == null && result >= 0) {
                    val svc = service
                    if (svc != null) {
                        tvSf2Path.text = fileName
                        tvSf2Count.text = getString(R.string.settings_sf2_loaded_count, count)
                        Toast.makeText(this@SettingsActivity, "SoundFont loaded", Toast.LENGTH_SHORT).show()
                        // Persist the path so the SF2 is reloaded after
                        // process death (the engine is recreated empty).
                        getSharedPreferences("piano_prefs", MODE_PRIVATE).edit()
                            .putString("sf2_path", File(getExternalFilesDir(null)!!.absolutePath, fileName).absolutePath)
                            .apply()
                    }
                } else {
                    val msg = ex?.message ?: "Failed to load SoundFont (error: $result)"
                    Toast.makeText(this@SettingsActivity, msg, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun unloadSoundFont() {
        btnUnload.isEnabled = false

        val svc = service ?: return
        CompletableFuture.runAsync({
            svc.unloadSoundFonts()
        }, mainExecutor)
        .whenComplete { _, ex ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                btnUnload.isEnabled = true
                if (ex == null) {
                    AppLogger.info("SettingsActivity", "Unloaded all SoundFonts")
                    val svc = service
                    if (svc != null) {
                        tvSf2Path.text = getString(R.string.settings_sf2_none)
                        tvSf2Count.text = getString(R.string.settings_sf2_loaded_count, 0)
                    }
                    getSharedPreferences("piano_prefs", MODE_PRIVATE).edit()
                        .remove("sf2_path")
                        .apply()
                } else {
                    AppLogger.error("SettingsActivity", "Failed to unload SoundFont: ${ex.message}")
                    Toast.makeText(this@SettingsActivity, "Failed to unload SoundFont", Toast.LENGTH_SHORT).show()
                }
            }
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