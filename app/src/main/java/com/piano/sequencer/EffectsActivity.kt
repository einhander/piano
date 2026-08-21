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
 * Presents the fixed 3-effect chain (EQ → Compressor → Limiter) as cards, each
 * with an enable toggle and a slider per exposed parameter. Parameter ranges
 * and flags come from the native descriptor tables (queried via JNI), so the UI
 * never duplicates DSP metadata. All JNI calls run on a worker executor (direct
 * JNI calls, never the main thread). Persistence of user tweaks is kept in the
 * shared "piano_prefs" store (does not touch the project format — that is
 * Milestone 8).
 */
class EffectsActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private lateinit var statusText: TextView

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

    // ── Per-parameter slider model ──
    private data class ParamInfo(
        val slot: Int,
        val paramId: Int,
        val min: Float,
        val max: Float,
        val def: Float,
        val logarithmic: Boolean,
        val integer: Boolean,
        val toggled: Boolean,
        val name: String
    )

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
        if (!svc.isEngineInitialized()) {
            runOnUiThread {
                statusText.text = getString(R.string.master_effects_engine_not_ready)
            }
            return
        }
        val available = try { svc.isMasterEffectChainAvailable() } catch (e: Exception) { false }
        if (!available) {
            runOnUiThread {
                statusText.text = getString(R.string.master_effects_unavailable)
            }
            return
        }

        val count = try { svc.getMasterEffectCount() } catch (e: Exception) { 0 }
        if (count <= 0) {
            runOnUiThread {
                statusText.text = getString(R.string.master_effects_unavailable)
            }
            return
        }

        // Gather metadata + current values on the worker thread, then build
        // views on the main thread.
        val cards = ArrayList<ArrayList<ParamInfo>>(count)
        val enabledStates = BooleanArray(count)
        val currentValues = ArrayList<FloatArray>(count)
        val titles = ArrayList<String>(count)

        for (slot in 0 until count) {
            val n = svc.getMasterEffectParamCount(slot)
            val params = ArrayList<ParamInfo>(n)
            val values = FloatArray(n)
            for (index in 0 until n) {
                val info = svc.getMasterEffectParamInfo(slot, index)
                val name = svc.getMasterEffectParamName(slot, index)
                if (info == null || info.size < 7) continue
                val p = ParamInfo(
                    slot = slot,
                    paramId = info[0].toInt(),
                    min = info[1],
                    max = info[2],
                    def = info[3],
                    logarithmic = info[4] != 0f,
                    integer = info[5] != 0f,
                    toggled = info[6] != 0f,
                    name = name.ifEmpty { "Param ${info[0].toInt()}" }
                )
                params.add(p)
                val idx = params.size - 1
                values[idx] = svc.getMasterEffectParameter(slot, p.paramId)
            }
            cards.add(params)
            currentValues.add(values)
            enabledStates[slot] = try { svc.isMasterEffectEnabled(slot) } catch (e: Exception) { false }
            titles.add(friendlyEffectTitle(svc.getMasterEffectStableId(slot), slot))
        }

        runOnUiThread {
            statusText.text = getString(R.string.master_effects_available, count)
            isRestoringState = true
            for (slot in 0 until count) {
                container.addView(buildEffectCard(
                    slot, titles[slot], enabledStates[slot],
                    cards[slot], currentValues[slot]
                ))
            }
            isRestoringState = false
        }
    }

    private fun friendlyEffectTitle(stableId: String, slot: Int): String {
        return when (stableId) {
            "lsp.parametric_eq" -> getString(R.string.master_effects_eq)
            "lsp.compressor"   -> getString(R.string.master_effects_compressor)
            "lsp.limiter"      -> getString(R.string.master_effects_limiter)
            else -> "${slot + 1}. $stableId"
        }
    }

    private fun buildEffectCard(
        slot: Int,
        title: String,
        enabled: Boolean,
        params: ArrayList<ParamInfo>,
        values: FloatArray
    ): View {
        val ctx = this
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(ctx, R.drawable.card_frame)
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = (16 * resources.displayMetrics.density).toInt()
            layoutParams = lp
        }

        // Header row: title + enable switch.
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = (8 * resources.displayMetrics.density).toInt()
            layoutParams = lp
        }
        val titleView = TextView(ctx).apply {
            text = title
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val enableSwitch = SwitchCompat(ctx).apply {
            text = getString(R.string.master_effects_enable)
            isChecked = enabled
            setOnCheckedChangeListener { _, isChecked ->
                if (isRestoringState) return@setOnCheckedChangeListener
                val svc = service ?: return@setOnCheckedChangeListener
                CompletableFuture.runAsync({
                    svc.setMasterEffectEnabled(slot, isChecked)
                    prefs().edit().putBoolean(fxEnabledKey(slot), isChecked).apply()
                }, mainExecutor)
            }
        }
        header.addView(titleView)
        header.addView(enableSwitch)
        card.addView(header)

        // Persisted enable state takes precedence over the default (bypassed).
        prefs().edit().putBoolean(fxEnabledKey(slot), enabled).apply()

        // Parameter sliders.
        for (i in params.indices) {
            val p = params[i]
            val value = values[i]
            card.addView(buildParamRow(slot, p, value))
        }
        return card
    }

    private fun buildParamRow(slot: Int, p: ParamInfo, value: Float): View {
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
        }
        row.addView(label)

        val seek = SeekBar(ctx).apply {
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
                    val svc = service ?: return
                    CompletableFuture.runAsync({
                        svc.setMasterEffectParameter(slot, p.paramId, v)
                        prefs().edit().putFloat(fxParamKey(slot, p.paramId), v).apply()
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

    private fun valueToProgress(p: ParamInfo, value: Float): Int {
        val v = if (p.toggled) {
            if (value >= 0.5f) 1f else 0f
        } else if (p.logarithmic && p.min > 0f) {
            val lo = log10(p.min.toDouble())
            val hi = log10(p.max.toDouble())
            val clamped = min(p.max.toDouble(), max(p.min.toDouble(), value.toDouble()))
            ((log10(clamped) - lo) / (hi - lo)).toFloat()
        } else {
            val span = (p.max - p.min)
            if (span <= 0f) 0f else ((value - p.min) / span)
        }
        return (v.coerceIn(0f, 1f) * SEEKBAR_STEPS).toInt()
    }

    private fun progressToValue(p: ParamInfo, progress: Int): Float {
        val t = progress.toFloat() / SEEKBAR_STEPS
        var v: Float = if (p.toggled) {
            if (t >= 0.5f) 1f else 0f
        } else if (p.logarithmic && p.min > 0f) {
            val lo = log10(p.min.toDouble())
            val hi = log10(p.max.toDouble())
            (10.0.pow((lo + (hi - lo) * t))).toFloat()
        } else {
            p.min + (p.max - p.min) * t
        }
        if (p.integer) v = kotlin.math.round(v)
        return v.coerceIn(p.min, p.max)
    }

    private fun formatParamLabel(p: ParamInfo, value: Float): String {
        val v = if (p.toggled) {
            if (value >= 0.5f) getString(R.string.master_effects_on) else getString(R.string.master_effects_off)
        } else if (p.integer) {
            value.toInt().toString()
        } else {
            "%.3f".format(value)
        }
        return "${p.name}: $v"
    }

    // ── Persistence (lightweight; project format is Milestone 8) ──

    private fun prefs() = getSharedPreferences("piano_prefs", Context.MODE_PRIVATE)
    private fun fxEnabledKey(slot: Int) = "fx_enabled_$slot"
    private fun fxParamKey(slot: Int, paramId: Int) = "fx_param_${slot}_$paramId"

    companion object {
        private const val SEEKBAR_STEPS = 1000
    }
}
