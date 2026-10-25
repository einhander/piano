package com.piano.sequencer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Typeface
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
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
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
            stopSpectrumCapture()
            service = null
        }
    }

    private val mainExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "EffectsMain").apply { isDaemon = true }
    }

    private lateinit var repository: EffectRepository
    private var meterView: LevelMeterView? = null
    private var meterTask: ScheduledFuture<*>? = null
    @Volatile private var screenVisible = false
    private val meterUiUpdatePending = AtomicBoolean(false)
    private var spectrumView: SpectrumPlotView? = null
    @Volatile private var spectrumEnabled = false
    private var spectrumToggle: SwitchCompat? = null
    private var spectrumTask: ScheduledFuture<*>? = null
    private val spectrumExecutor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "EffectsSpectrum").apply { isDaemon = true }
    }
    private val spectrumReadBuffer = FloatArray(SPECTRUM_MAX_FRAMES * 2)
    private val spectrumLeft = FloatArray(SPECTRUM_FFT_SIZE)
    private val spectrumRight = FloatArray(SPECTRUM_FFT_SIZE)
    private val spectrumLeftBins = FloatArray(SPECTRUM_BINS)
    private val spectrumRightBins = FloatArray(SPECTRUM_BINS)
    private val spectrumAnalyzer = SpectrumAnalyzer(SPECTRUM_FFT_SIZE)
    private val spectrumUiUpdatePending = AtomicBoolean(false)
    private val spectrumGeneration = AtomicInteger(0)
    // Accessed only by spectrumExecutor.
    private var spectrumCaptureArmed = false
    private var spectrumSampleRate = 0

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
        stopMeterPolling()
        stopSpectrumCapture()
        spectrumExecutor.shutdown()
        mainExecutor.shutdown()
        super.onDestroy()
        try {
            unbindService(serviceConnection)
        } catch (_: Exception) {
            // ignore if not bound
        }
    }

    override fun onStart() {
        super.onStart()
        screenVisible = true
        startMeterPolling()
    }

    override fun onStop() {
        screenVisible = false
        stopMeterPolling()
        stopSpectrumCapture()
        super.onStop()
    }

    private fun startMeterPolling() {
        val view = meterView ?: return
        val slot = editorSlot ?: return
        if (!screenVisible || meterTask != null || isFinishing || isDestroyed) return
        meterTask = mainExecutor.scheduleAtFixedRate({
            val snapshot = try { repository.meterSnapshot(slot) } catch (_: Throwable) { null }
            if (screenVisible && !isFinishing && !isDestroyed && meterUiUpdatePending.compareAndSet(false, true)) {
                runOnUiThread {
                    if (meterView === view) view.setSnapshot(snapshot)
                    meterUiUpdatePending.set(false)
                }
            }
        }, 0, 50, TimeUnit.MILLISECONDS)
    }

    private fun stopMeterPolling() {
        meterTask?.cancel(true)
        meterTask = null
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
                        minimumHeight = (48 * resources.displayMetrics.density).toInt()
                        isAllCaps = false
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
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        panel.addView(TextView(this).apply {
            text = friendlyEffectTitle(instance.descriptor.stableId)
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 8, 0, 12)
        })
        val state = TextView(this).apply { textSize = 13f }
        val toggle = SwitchCompat(this).apply {
            minimumHeight = (48 * resources.displayMetrics.density).toInt()
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
        val stableId = instance.descriptor.stableId
        if (stableId == "lsp.compressor" || stableId == "lsp.limiter") {
            val meters = LevelMeterView(this, stableId == "lsp.compressor")
            meterView = meters
            panel.addView(meters, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (156 * resources.displayMetrics.density).toInt()
            ))
            startMeterPolling()
        } else if (stableId == "lsp.parametric_eq") {
            panel.addView(TextView(this).apply {
                text = getString(R.string.ui_live_spectrum)
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 16, 0, 6)
            })
            val analyzerToggle = SwitchCompat(this).apply {
                text = getString(R.string.ui_analyzer)
                minimumHeight = (48 * resources.displayMetrics.density).toInt()
                isChecked = spectrumEnabled
                spectrumToggle = this
                setOnCheckedChangeListener { _, checked ->
                    spectrumEnabled = checked
                    if (checked) startSpectrumCapture() else stopSpectrumCapture()
                }
            }
            panel.addView(analyzerToggle)
            val plot = SpectrumPlotView(this)
            spectrumView = plot
            panel.addView(plot, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (220 * resources.displayMetrics.density).toInt()
            ))
        }
        instance.parameters.filter { it.writable }.forEach { p ->
            panel.addView(buildParamRow(instance.slot, p, p.value.rawValue, instance.available))
        }
        return panel
    }

    private fun startSpectrumCapture() {
        if (!spectrumEnabled || !screenVisible || editorSlot == null || spectrumTask != null || isFinishing || isDestroyed) return
        val generation = spectrumGeneration.incrementAndGet()
        spectrumTask = spectrumExecutor.scheduleWithFixedDelay({
            if (!isSpectrumRequestCurrent(generation)) return@scheduleWithFixedDelay
            try {
                val repo = repository
                val actualRate = repo.getSampleRate()
                if (actualRate <= 0) return@scheduleWithFixedDelay
                if (spectrumSampleRate != actualRate) {
                    // Discard buffered samples from an old device rate before interpreting new frames.
                    if (spectrumCaptureArmed) repo.setSpectrumCaptureEnabled(false)
                    spectrumCaptureArmed = false
                    spectrumSampleRate = actualRate
                }
                if (!spectrumCaptureArmed) {
                    if (!isSpectrumRequestCurrent(generation)) return@scheduleWithFixedDelay
                    repo.setSpectrumCaptureEnabled(true)
                    spectrumCaptureArmed = true
                }
                if (!isSpectrumRequestCurrent(generation)) return@scheduleWithFixedDelay
                val frames = repo.readSpectrumCapture(spectrumReadBuffer, SPECTRUM_MAX_FRAMES).coerceIn(0, SPECTRUM_MAX_FRAMES)
                if (frames >= SPECTRUM_FFT_SIZE) {
                    val start = frames - SPECTRUM_FFT_SIZE
                    for (i in 0 until SPECTRUM_FFT_SIZE) {
                        spectrumLeft[i] = spectrumReadBuffer[(start + i) * 2]
                        spectrumRight[i] = spectrumReadBuffer[(start + i) * 2 + 1]
                    }
                    spectrumAnalyzer.spectrum(spectrumLeft, actualRate, spectrumLeftBins)
                    spectrumAnalyzer.spectrum(spectrumRight, actualRate, spectrumRightBins)
                    val left = spectrumLeftBins.copyOf()
                    val right = spectrumRightBins.copyOf()
                    if (isSpectrumRequestCurrent(generation) && spectrumUiUpdatePending.compareAndSet(false, true)) runOnUiThread {
                        try {
                            if (isSpectrumRequestCurrent(generation) && !isFinishing && !isDestroyed)
                                spectrumView?.setSpectrum(left, right, actualRate)
                        }
                        finally { spectrumUiUpdatePending.set(false) }
                    }
                }
            } catch (e: Throwable) {
                AppLogger.error("EffectsActivity", "Spectrum analyzer failed: ${e.message}")
            }
        }, 0, 50, TimeUnit.MILLISECONDS)
    }

    private fun isSpectrumRequestCurrent(generation: Int): Boolean =
        spectrumGeneration.get() == generation && spectrumEnabled && screenVisible && !isFinishing && !isDestroyed

    private fun stopSpectrumCapture() {
        spectrumGeneration.incrementAndGet()
        spectrumTask?.cancel(false)
        spectrumTask = null
        spectrumEnabled = false
        spectrumToggle?.isChecked = false
        if (!spectrumExecutor.isShutdown) {
            try {
                spectrumExecutor.execute {
                    if (!spectrumCaptureArmed) return@execute
                    try { repository.setSpectrumCaptureEnabled(false) }
                    catch (e: Throwable) { AppLogger.error("EffectsActivity", "Could not stop spectrum capture: ${e.message}") }
                    finally { spectrumCaptureArmed = false }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                // Executor already shutting down; queued stop normally runs before shutdown.
            }
        }
        spectrumView?.setSpectrum(null, null)
    }

    private class SpectrumPlotView(context: Context) : View(context) {
        private val density = resources.displayMetrics.density
        private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xffdce5e1.toInt(); strokeWidth = density }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xff52635c.toInt(); textSize = 10f * density }
        private val leftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xff008f70.toInt(); strokeWidth = 2f * density; style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
        private val rightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xffd16b38.toInt(); strokeWidth = 2f * density; style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
        private var left: FloatArray? = null
        private var right: FloatArray? = null
        private var sampleRate = 48000
        fun setSpectrum(l: FloatArray?, r: FloatArray?, rate: Int = sampleRate) { left = l; right = r; if (rate > 0) sampleRate = rate; invalidate() }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val x0 = 38f * density; val x1 = width - 8f * density
            val y0 = 10f * density; val y1 = height - 27f * density
            for (db in intArrayOf(0, -20, -40, -60, -80)) {
                val y = y0 + (-db / 80f) * (y1 - y0)
                canvas.drawLine(x0, y, x1, y, grid); canvas.drawText("${db} dBFS", 1f, y + 4f * density, label)
            }
            val nyquist = sampleRate / 2f
            val freqs = intArrayOf(20, 100, 1000, 10000).filter { it < nyquist }
            for (hz in freqs) {
                val x = x0 + (kotlin.math.ln(hz / 20f) / kotlin.math.ln(nyquist / 20f)) * (x1 - x0)
                canvas.drawLine(x, y0, x, y1, grid)
                canvas.drawText(if (hz >= 1000) "${hz / 1000}k" else "$hz", x - 8f * density, height - 5f * density, label)
            }
            val nyquistLabel = if (nyquist >= 1000f) "${(nyquist / 1000f).toInt()}k" else "${nyquist.toInt()}"
            canvas.drawText(nyquistLabel, x1 - label.measureText(nyquistLabel), height - 5f * density, label)
            fun draw(values: FloatArray?, paint: Paint) {
                if (values == null) return
                val path = android.graphics.Path()
                values.forEachIndexed { i, value ->
                    // Each trace sample represents center of its logarithmic band.
                    val x = x0 + (i + 0.5f) / values.size * (x1 - x0)
                    val y = y0 + ((0f - value.coerceIn(-80f, 0f)) / 80f) * (y1 - y0)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                canvas.drawPath(path, paint)
            }
            draw(left, leftPaint); draw(right, rightPaint)
            canvas.drawText("L", x0, height - 5f * density, leftPaint); canvas.drawText("R", x0 + 18f * density, height - 5f * density, rightPaint)
        }
    }

    /** Draws only the six native snapshot values; NaN remains explicitly unavailable. */
    private class LevelMeterView(context: Context, private val compressor: Boolean) : View(context) {
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xff36a878.toInt() }
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xffe5e9e7.toInt() }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xff24332d.toInt(); textSize = 12f * resources.displayMetrics.density }
        private var values: FloatArray? = null
        private val density = resources.displayMetrics.density

        fun setSnapshot(snapshot: FloatArray?) {
            values = snapshot?.takeIf { it.size >= 6 }?.copyOf(6)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val labels = arrayOf("Input", "Output", if (compressor) "Reduction" else "Gain reduction")
            val data = values
            val rowHeight = height / 3f
            val left = 76f * density
            val right = width - 48f * density
            val barHeight = 7f * density
            val dbFloor = -60f
            for (row in 0..2) {
                val top = row * rowHeight
                canvas.drawText(labels[row], 0f, top + 18f * density, textPaint)
                for (channel in 0..1) {
                    val y = top + (28f + channel * 20f) * density
                    val index = when (row) { 0 -> channel; 1 -> channel + 2; else -> channel + 4 }
                    val raw = data?.get(index) ?: Float.NaN
                    val db = if (row == 2) {
                        if (raw.isFinite() && raw in 0f..1f) if (raw <= 0f) 60f else (-20f * log10(raw.toDouble())).toFloat() else Float.NaN
                    } else {
                        if (raw.isFinite() && raw > 0f) (20f * log10(raw.toDouble())).toFloat() else if (raw == 0f) dbFloor else Float.NaN
                    }
                    val available = db.isFinite()
                    val label = if (!available) "—" else "%.1f dB".format(db)
                    canvas.drawRoundRect(RectF(left, y, right, y + barHeight), barHeight, barHeight, trackPaint)
                    if (available) {
                        val fraction = if (row == 2) (db / 60f).coerceIn(0f, 1f) else ((db - dbFloor) / -dbFloor).coerceIn(0f, 1f)
                        canvas.drawRoundRect(RectF(left, y, left + (right - left) * fraction, y + barHeight), barHeight, barHeight, fillPaint)
                    }
                    canvas.drawText("${if (channel == 0) "L" else "R"}  $label", right + 5f * density, y + barHeight + 2f * density, textPaint)
                }
            }
        }
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
            val pad = (12 * resources.displayMetrics.density).toInt()
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
            gravity = android.view.Gravity.CENTER_VERTICAL
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
            minimumWidth = (48 * resources.displayMetrics.density).toInt()
            minimumHeight = (48 * resources.displayMetrics.density).toInt()
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
        val editButton = android.widget.Button(ctx).apply {
            text = getString(R.string.ui_configure)
            isAllCaps = false
            minimumHeight = (48 * resources.displayMetrics.density).toInt()
            contentDescription = "$title · ${getString(R.string.ui_configure)}"
            isEnabled = instance.available
            setOnClickListener {
                startActivity(Intent(this@EffectsActivity, EffectsActivity::class.java).putExtra(EXTRA_EFFECT_SLOT, slot))
            }
        }
        header.addView(editButton)
        card.addView(header)
        card.addView(stateLabel)
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
            textSize = 14f
            isEnabled = enabled
        }
        row.addView(label)

        val seek = SeekBar(ctx).apply {
            minimumHeight = (48 * resources.displayMetrics.density).toInt()
            contentDescription = p.displayName
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
        val v = formatEffectParameterValue(p, value,
            getString(R.string.master_effects_on), getString(R.string.master_effects_off))
        return "${p.displayName}: $v"
    }

    // ── Persistence (lightweight; project format is Milestone 8) ──

    private fun prefs() = getSharedPreferences("piano_prefs", Context.MODE_PRIVATE)
    private fun fxEnabledKey(slot: Int) = "fx_enabled_$slot"
    private fun fxParamKey(slot: Int, paramId: Int) = "fx_param_${slot}_$paramId"

    companion object {
        private const val SEEKBAR_STEPS = 1000
        private const val SPECTRUM_MAX_FRAMES = 8192
        private const val SPECTRUM_FFT_SIZE = 2048
        private const val SPECTRUM_BINS = 96
        const val EXTRA_EFFECT_SLOT = "effect_slot"
    }
}
