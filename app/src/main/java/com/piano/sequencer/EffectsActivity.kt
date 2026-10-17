package com.piano.sequencer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Typeface
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.piano.sequencer.service.PlaybackService
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Master effect chain UI (Milestone 7).
 *
 * Presents the fixed 3-effect chain as compact rows. Parameter controls remain
 * available in an explicitly expanded section until dedicated editors arrive.
 * Parameter ranges
 * and flags come from the native descriptor tables (queried via JNI), so the UI
 * never duplicates DSP metadata. All JNI calls run on a worker executor (direct
 * JNI calls, never the main thread). Persistence of user tweaks is kept in the
 * shared "piano_prefs" store (does not touch the project format — that is
 * Milestone 8).
 */
class EffectsActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private lateinit var statusText: TextView
    private var editorSlot: Int? = null

    private var service: PlaybackService? = null
    private var isRestoringState = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
            if (isFinishing || isDestroyed) return
            service = (binder as PlaybackService.PlaybackBinder).getService()
            CompletableFuture.runAsync({ buildUi() }, mainExecutor)
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    private val mainExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "EffectsMain").apply { isDaemon = true }
    }

    private lateinit var repository: EffectRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_effects)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }
        container = findViewById(R.id.effectsContainer)
        statusText = findViewById(R.id.effectsStatus)
        editorSlot = if (intent.hasExtra(EXTRA_EFFECT_SLOT)) intent.getIntExtra(EXTRA_EFFECT_SLOT, -1).takeIf { it >= 0 } else null

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

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    // ── UI build (worker thread → main thread) ──

    private fun buildUi() {
        val svc = service ?: return
        repository = EffectRepository(svc)
        if (!svc.isEngineInitialized()) {
            runOnUiThread {
                statusText.text = getString(R.string.master_effects_engine_not_ready)
            }
            return
        }
        runOnUiThread { statusText.text = getString(R.string.master_effects_loading) }
        val available = try {
            svc.isMasterEffectChainAvailable()
        } catch (e: Throwable) {
            AppLogger.error("EffectsActivity", "Could not query effect chain: ${e.message}")
            runOnUiThread {
                statusText.text = getString(R.string.master_effects_error)
            }
            return
        }
        if (!available) {
            runOnUiThread {
                statusText.text = getString(R.string.master_effects_unavailable)
            }
            return
        }

        val instances = try { repository.snapshot() } catch (e: Exception) {
            AppLogger.error("EffectsActivity", "Could not read effect chain: ${e.message}")
            runOnUiThread { statusText.text = getString(R.string.master_effects_error) }
            return
        }
        val count = instances.size
        if (count <= 0) {
            runOnUiThread {
                statusText.text = getString(R.string.master_effects_unavailable)
            }
            return
        }
        // Gather metadata + current values on the worker thread, then build
        // views on the main thread.
        runOnUiThread {
            supportActionBar?.title = if (editorSlot == null) getString(R.string.master_effects_title)
                else instances.firstOrNull { it.slot == editorSlot }?.let { friendlyEffectTitle(it.descriptor.stableId) }
                    ?: getString(R.string.master_effects_title)
            statusText.text = if (editorSlot == null) getString(R.string.master_effects_chain_heading) else ""
            isRestoringState = true
            if (editorSlot == null) {
                for (instance in instances) container.addView(buildEffectCard(instance.slot, friendlyEffectTitle(instance.descriptor.stableId), instance))
            } else {
                val index = instances.indexOfFirst { it.slot == editorSlot }
                if (index < 0) {
                    statusText.text = getString(R.string.master_effects_unavailable)
                } else {
                    container.addView(buildEditorTabs(instances, index))
                    container.addView(buildEditor(instances[index]))
                }
            }
            isRestoringState = false
        }
    }

    private fun buildEditorTabs(instances: List<EffectInstance>, selected: Int): View =
        android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(LinearLayout(this@EffectsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                instances.forEachIndexed { index, instance ->
                    val tab = android.widget.Button(this@EffectsActivity).apply {
                        text = friendlyEffectTitle(instance.descriptor.stableId)
                        isEnabled = instance.available
                        isSelected = index == selected
                        setOnClickListener {
                            startActivity(Intent(this@EffectsActivity, EffectsActivity::class.java)
                                .putExtra(EXTRA_EFFECT_SLOT, instance.slot))
                            finish()
                        }
                    }
                    addView(tab)
                }
            })
        }

    private fun buildEditor(instance: EffectInstance): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(this@EffectsActivity, R.drawable.card_frame)
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        panel.addView(TextView(this).apply {
            text = friendlyEffectTitle(instance.descriptor.stableId)
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 8, 0, 12)
        })
        val state = TextView(this).apply { textSize = 13f }
        val toggle = SwitchCompat(this).apply {
            text = getString(R.string.master_effects_enable)
            isChecked = instance.enabled
            isEnabled = instance.available
            setOnCheckedChangeListener { _, checked ->
                state.text = getString(if (checked) R.string.master_effects_enabled_state else R.string.master_effects_bypassed_state)
                CompletableFuture.runAsync({
                    try {
                        repository.setEnabled(instance.slot, checked)
                        prefs().edit().putBoolean(fxEnabledKey(instance.slot), checked).apply()
                    } catch (e: Exception) {
                        AppLogger.error("EffectsActivity", "Could not change ${instance.descriptor.displayName} state: ${e.message}")
                        runOnUiThread { state.text = getString(R.string.master_effects_error_state) }
                    }
                }, mainExecutor)
            }
        }
        state.text = getString(when { !instance.available -> R.string.master_effects_unavailable_state; instance.enabled -> R.string.master_effects_enabled_state; else -> R.string.master_effects_bypassed_state })
        panel.addView(toggle)
        panel.addView(state)
        instance.parameters.filter { it.writable }.forEach { p ->
            panel.addView(buildParamRow(instance.slot, p, p.value.rawValue, instance.available))
        }
        return panel
    }

    private fun friendlyEffectTitle(stableId: String): String {
        return when (stableId) {
            "lsp.parametric_eq" -> getString(R.string.master_effects_eq)
            "lsp.compressor"   -> getString(R.string.master_effects_compressor)
            "lsp.limiter"      -> getString(R.string.master_effects_limiter)
            else -> stableId
        }
    }

    private fun buildEffectCard(
        slot: Int,
        title: String,
        instance: EffectInstance
    ): View {
        val ctx = this
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(ctx, R.drawable.card_frame)
            val pad = (14 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = (10 * resources.displayMetrics.density).toInt()
            layoutParams = lp
        }

        // Compact title row and truthful enabled/bypassed state.
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = (2 * resources.displayMetrics.density).toInt()
            layoutParams = lp
        }
        val titleView = TextView(ctx).apply {
            text = title
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val stateLabel = TextView(ctx).apply {
            text = getString(when { !instance.available -> R.string.master_effects_unavailable_state; instance.enabled -> R.string.master_effects_enabled_state; else -> R.string.master_effects_bypassed_state })
            textSize = 12f
            setTextColor(ContextCompat.getColor(ctx, if (instance.available && instance.enabled) R.color.effects_accent else android.R.color.darker_gray))
        }
        val enableSwitch = SwitchCompat(ctx).apply {
            contentDescription = getString(R.string.master_effects_enable)
            isChecked = instance.enabled
            isEnabled = instance.available
            setOnCheckedChangeListener { _, isChecked ->
                if (isRestoringState) return@setOnCheckedChangeListener
                stateLabel.text = getString(if (isChecked) R.string.master_effects_enabled_state else R.string.master_effects_bypassed_state)
                stateLabel.setTextColor(ContextCompat.getColor(ctx, if (isChecked) R.color.effects_accent else android.R.color.darker_gray))
                CompletableFuture.runAsync({
                    try {
                        repository.setEnabled(slot, isChecked)
                        prefs().edit().putBoolean(fxEnabledKey(slot), isChecked).apply()
                    } catch (e: Exception) {
                        AppLogger.error("EffectsActivity", "Could not change ${instance.descriptor.displayName} state: ${e.message}")
                        runOnUiThread {
                            stateLabel.text = getString(R.string.master_effects_error_state)
                            stateLabel.setTextColor(ContextCompat.getColor(ctx, android.R.color.holo_red_dark))
                        }
                    }
                }, mainExecutor)
            }
        }
        header.addView(titleView)
        header.addView(enableSwitch)
        card.addView(header)

        card.addView(stateLabel)

        val editButton = android.widget.Button(ctx).apply {
            text = getString(R.string.master_effects_parameters, instance.parameters.size)
            isEnabled = instance.available
            setOnClickListener {
                startActivity(Intent(this@EffectsActivity, EffectsActivity::class.java).putExtra(EXTRA_EFFECT_SLOT, slot))
            }
        }
        card.addView(editButton)
        return card
    }

    private fun buildParamRow(slot: Int, p: EffectParameter, value: Float, enabled: Boolean): View {
        val ctx = this
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = (6 * resources.displayMetrics.density).toInt()
            layoutParams = lp
        }

        val label = TextView(ctx).apply {
            text = formatParamLabel(p, value)
            textSize = 13f
            isEnabled = enabled
        }
        row.addView(label)

        val seek = SeekBar(ctx).apply {
            isEnabled = enabled
            max = SEEKBAR_STEPS
            progress = valueToProgress(p, value)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    val v = progressToValue(p, progress)
                    label.text = formatParamLabel(p, v)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    val v = progressToValue(p, sb?.progress ?: 0)
                    if (service == null) return
                    CompletableFuture.runAsync({
                        try {
                            repository.setParameter(slot, p.stableId.toInt(), v)
                            prefs().edit().putFloat(fxParamKey(slot, p.stableId.toInt()), v).apply()
                        } catch (e: Exception) {
                            AppLogger.error("EffectsActivity", "Could not set ${p.displayName}: ${e.message}")
                            runOnUiThread { android.widget.Toast.makeText(ctx, R.string.master_effects_error, android.widget.Toast.LENGTH_SHORT).show() }
                        }
                    }, mainExecutor)
                }
            })
            // A wide seek bar.
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        row.addView(seek)
        return row
    }

    // ── Slider value ↔ progress mapping ──

    private fun valueToProgress(p: EffectParameter, value: Float): Int {
        val v = if (p.type == EffectParameterType.BOOLEAN) {
            if (value >= 0.5f) 1f else 0f
        } else if (p.logarithmic && p.minimum > 0f) {
            val lo = log10(p.minimum.toDouble())
            val hi = log10(p.maximum.toDouble())
            val clamped = min(p.maximum.toDouble(), max(p.minimum.toDouble(), value.toDouble()))
            ((log10(clamped) - lo) / (hi - lo)).toFloat()
        } else {
            val span = (p.maximum - p.minimum)
            if (span <= 0f) 0f else ((value - p.minimum) / span)
        }
        return (v.coerceIn(0f, 1f) * SEEKBAR_STEPS).toInt()
    }

    private fun progressToValue(p: EffectParameter, progress: Int): Float {
        val t = progress.toFloat() / SEEKBAR_STEPS
        var v: Float = if (p.type == EffectParameterType.BOOLEAN) {
            if (t >= 0.5f) 1f else 0f
        } else if (p.logarithmic && p.minimum > 0f) {
            val lo = log10(p.minimum.toDouble())
            val hi = log10(p.maximum.toDouble())
            (10.0.pow((lo + (hi - lo) * t))).toFloat()
        } else {
            p.minimum + (p.maximum - p.minimum) * t
        }
        if (p.type == EffectParameterType.INTEGER) v = kotlin.math.round(v)
        return v.coerceIn(p.minimum, p.maximum)
    }

    private fun formatParamLabel(p: EffectParameter, value: Float): String {
        val v = if (p.type == EffectParameterType.BOOLEAN) {
            if (value >= 0.5f) getString(R.string.master_effects_on) else getString(R.string.master_effects_off)
        } else if (p.type == EffectParameterType.INTEGER) {
            value.toInt().toString()
        } else {
            "%.3f".format(value)
        }
        return "${p.displayName}: $v"
    }

    // ── Persistence (lightweight; project format is Milestone 8) ──

    private fun prefs() = getSharedPreferences("piano_prefs", Context.MODE_PRIVATE)
    private fun fxEnabledKey(slot: Int) = "fx_enabled_$slot"
    private fun fxParamKey(slot: Int, paramId: Int) = "fx_param_${slot}_$paramId"

    companion object {
        private const val SEEKBAR_STEPS = 1000
        const val EXTRA_EFFECT_SLOT = "effect_slot"
    }
}
