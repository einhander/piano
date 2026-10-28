package com.piano.sequencer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.text.InputType
import androidx.appcompat.app.AlertDialog
import com.piano.sequencer.midi.EffectCcMapping
import com.piano.sequencer.midi.EffectCcScope
import com.piano.sequencer.midi.EffectCcLearnResult
import com.piano.sequencer.midi.CcDisposition
import com.piano.sequencer.midi.MidiFileMappingStore
import com.piano.sequencer.midi.SequencerCell
import com.piano.sequencer.midi.TRIGGER_CC
import java.lang.ref.WeakReference
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.piano.sequencer.service.PlaybackService
import java.util.concurrent.Executors
import kotlin.math.ln
import kotlin.math.exp
import kotlin.math.round

/** Channel-scoped editor. Opening/closing never starts, stops or reconfigures playback. */
class InstrumentEffectsActivity : AppCompatActivity() {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "InstrumentEffects").apply { isDaemon = true } }
    private var service: PlaybackService? = null
    private lateinit var container: LinearLayout
    private lateinit var status: TextView
    private var channel = -1
    private var bound = false
    private val expanded = mutableSetOf<String>()
    private val ccController get() = (application as PianoApp).instrumentEffectCcController
    private var learnDialog: AlertDialog? = null
    private var ownsLearn = false
    private val learnedSources = mutableMapOf<Pair<String, String>, String>()
    private var parameters = emptyMap<Pair<String, String>, EffectParameter>()
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
            if (isFinishing || isDestroyed) return
            service = (binder as PlaybackService.PlaybackBinder).getService()
            ccController.attach(this@InstrumentEffectsActivity, service!!)
            refresh()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service?.let { ccController.detach(this@InstrumentEffectsActivity, it) }
            service = null
            container.removeAllViews()
            status.text = getString(R.string.instrument_effects_disconnected)
            container.addView(status)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        channel = intent.getIntExtra(EXTRA_CHANNEL, -1)
        if (channel !in 0..15) { finish(); return }
        expanded.addAll((savedInstanceState?.getStringArrayList("expanded") ?: intent.getStringArrayListExtra("expanded")).orEmpty())
        setContentView(R.layout.activity_effects)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            title = getString(R.string.instrument_effects_channel_title, channel + 1)
            setDisplayHomeAsUpEnabled(true)
        }
        container = findViewById(R.id.effectsContainer)
        status = findViewById(R.id.effectsStatus)
        bound = bindService(Intent(this, PlaybackService::class.java), connection, Context.BIND_AUTO_CREATE)
        if (!bound) status.text = getString(R.string.instrument_effects_disconnected)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList("expanded", ArrayList(expanded))
        super.onSaveInstanceState(outState)
    }
    override fun onSupportNavigateUp(): Boolean { finish(); return true }
    override fun onDestroy() {
        // Controller has no observer reattachment API. End this owner's capture
        // explicitly rather than leave an invisible capture after recreation.
        if (ownsLearn) ccController.cancelLearn()
        learnDialog?.dismiss()
        service?.let { ccController.detach(this@InstrumentEffectsActivity, it) }
        service = null
        if (bound) unbindService(connection)
        worker.shutdown() // Accepted writes finish and persist, even when navigating back.
        super.onDestroy()
    }

    private fun refresh() = submit { svc -> renderSnapshot(svc) }

    private fun submit(action: (PlaybackService) -> Unit) {
        val svc = service ?: return
        if (worker.isShutdown) return
        worker.execute {
            try { action(svc) }
            catch (e: Exception) {
                AppLogger.error("InstrumentEffects", "Channel ${channel + 1}: ${e.message}")
                ui { status.text = getString(R.string.instrument_effects_error) }
            }
        }
    }

    private fun ui(action: () -> Unit) = runOnUiThread {
        if (!isFinishing && !isDestroyed && service != null) action()
    }

    // All service methods below are direct JNI calls, confined to worker.
    private fun renderSnapshot(svc: PlaybackService, failed: Boolean = false) {
        if (!svc.isEngineInitialized()) {
            ui { status.text = getString(R.string.master_effects_engine_not_ready) }
            return
        }
        val rack = svc.isInstrumentRackEnabled(channel)
        val instances = EFFECTS.mapIndexed { slot, (id, title) ->
            val available = svc.isInstrumentEffectAvailable(channel, id)
            val parameters = if (!available) emptyList() else (0 until svc.getInstrumentEffectParamCount(id)).mapNotNull { index ->
                val info = svc.getInstrumentEffectParamInfo(id, index) ?: return@mapNotNull null
                if (info.size < 7) return@mapNotNull null
                effectParameterFromNativeInfo(info, svc.getInstrumentEffectParamName(id, index),
                    svc.getInstrumentEffectParameter(channel, id, info[0].toInt()), id)?.let { instrumentPresentation(id, it) }
            }
            EffectInstance(slot, EffectDescriptor(id, getString(title), parameters),
                svc.isInstrumentEffectEnabled(channel, id), parameters, available)
        }
        val shared = listOf("native.delay", "fluidsynth.reverb").map { id ->
            val available = svc.isSharedFxAvailable(id)
            val controls = if (!available) emptyList() else sharedInstrumentControls(id,
                { index -> svc.getSharedFxParamInfo(id, index) },
                { index -> svc.getSharedFxParameter(id, index) },
                { svc.getDelaySend(channel) })
            EffectInstance(-1, EffectDescriptor(id, if (id == "native.delay") "Native Stereo Delay" else "FluidSynth Reverb", controls), false, controls, available)
        }
        val latency = instances.filter { it.available }.associate { it.descriptor.stableId to
            "${svc.getInstrumentEffectLatencyFrames(channel, it.descriptor.stableId)} frames · ${String.format(java.util.Locale.getDefault(), "%.2f", svc.getInstrumentEffectLatencyMillis(channel, it.descriptor.stableId))} ms" }
        ui { render(rack, instances, failed); renderShared(shared, latency) }
    }

    private fun change(action: (PlaybackService) -> Boolean) = submit { svc ->
        val success = action(svc)
        renderSnapshot(svc, !success)
    }

    private fun render(rack: Boolean, instances: List<EffectInstance>, failed: Boolean) {
        parameters = instances.flatMap { instance -> instance.parameters.map { (instance.descriptor.stableId to it.stableId) to it } }.toMap()
        container.removeAllViews()
        status.text = getString(if (failed) R.string.instrument_effects_error else R.string.instrument_effects_hint)
        container.addView(status)
        container.addView(Button(this).apply {
            com.piano.sequencer.ui.UiControlStyle.apply(this)
            text = "Instrument channel ${channel + 1} ▾"
            setOnClickListener {
                AlertDialog.Builder(this@InstrumentEffectsActivity).setTitle("Instrument channel")
                    .setSingleChoiceItems((1..16).map { "Channel $it" }.toTypedArray(), channel) { dialog, selected ->
                        dialog.dismiss()
                        if (selected != channel) {
                            startActivity(Intent(this@InstrumentEffectsActivity, InstrumentEffectsActivity::class.java)
                                .putExtra(EXTRA_CHANNEL, selected).putStringArrayListExtra("expanded", ArrayList(expanded)))
                            finish()
                        }
                    }.setNegativeButton("Cancel", null).show()
            }
        })
        container.addView(Button(this).apply {
            com.piano.sequencer.ui.UiControlStyle.apply(this)
            text = "MIDI CC mappings"
            setOnClickListener { showMappings() }
        })
        container.addView(SwitchCompat(this).apply {
            minimumHeight = dp(48)
            text = getString(R.string.instrument_effects_rack)
            isChecked = rack
            isEnabled = instances.any { it.available }
            setOnCheckedChangeListener { button, checked ->
                button.isEnabled = false
                change { it.setInstrumentRackEnabled(channel, checked) }
            }
        })
        if (!rack) container.addView(label(getString(R.string.instrument_effects_bypass)))
        instances.forEach { instance ->
            val id = instance.descriptor.stableId
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = ContextCompat.getDrawable(this@InstrumentEffectsActivity, R.drawable.card_frame)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
            }
            card.addView(SwitchCompat(this).apply {
                minimumHeight = dp(48)
                text = instance.descriptor.displayName
                textSize = 16f
                isChecked = instance.enabled
                isEnabled = instance.available
                setOnCheckedChangeListener { button, checked ->
                    button.isEnabled = false
                    change { svc -> svc.setInstrumentEffectEnabled(channel, id, checked).also {
                        if (it) ccController.manualParameterChanged(channel, id, "-1", if (checked) 1f else 0f)
                    } }
                }
            })
            card.addView(label(getString(when {
                !instance.available -> R.string.instrument_effects_unavailable
                instance.enabled -> R.string.master_effects_enabled_state
                else -> R.string.master_effects_bypassed_state
            })))
            if (instance.available) card.addView(parameterControl(id, EffectParameter("-1", "Insert enabled", EffectParameterValue(if (instance.enabled) 1f else 0f), 0f, 1f, 0f, EffectParameterType.BOOLEAN, false)))
            val controls = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = if (id in expanded) View.VISIBLE else View.GONE
            }
            instance.parameters.filter { it.writable }.forEach { p -> controls.addView(parameterControl(id, p)) }
            card.addView(Button(this).apply {
                com.piano.sequencer.ui.UiControlStyle.apply(this)
                text = getString(if (id in expanded) R.string.instrument_effects_hide else R.string.instrument_effects_configure)
                isEnabled = instance.available
                setOnClickListener {
                    if (!expanded.add(id)) expanded.remove(id)
                    controls.visibility = if (id in expanded) View.VISIBLE else View.GONE
                    text = getString(if (id in expanded) R.string.instrument_effects_hide else R.string.instrument_effects_configure)
                }
            })
            card.addView(controls)
            container.addView(card)
        }
    }

    private fun renderShared(shared: List<EffectInstance>, latency: Map<String, String>) {
        latency["lsp.clipper"]?.let { container.addView(label("Drive / Clipper latency: $it. Once armed (~0.5 ms), bypass retains latency.")) }
        container.addView(label("Shared FX · one global configuration across all 16 channels"))
        container.addView(label("Delay Send applies only to channel ${channel + 1}. Reverb is the existing shared FluidSynth return; no per-channel reverb send."))
        shared.forEach { instance ->
            val id = instance.descriptor.stableId
            parameters = parameters + instance.parameters.associateBy { id to it.stableId }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = ContextCompat.getDrawable(this@InstrumentEffectsActivity, R.drawable.card_frame)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
            }
            card.addView(label(instance.descriptor.displayName))
            if (!instance.available) card.addView(label("Unavailable · controls disabled"))
            instance.parameters.forEach { card.addView(parameterControl(id, it)) }
            if (id == "native.delay") {
                card.addView(label("Bounded native fallback, not LSP Artistic Delay. Sync follows current transport BPM; Tap is not offered. Time is manual when Sync is off."))
                card.addView(Button(this).apply {
                    com.piano.sequencer.ui.UiControlStyle.apply(this)
                    text = "Reset delay tail"
                    isEnabled = instance.available
                    setOnClickListener { change { it.resetSharedDelay() } }
                })
            }
            container.addView(card)
        }
    }

    private fun scope(id: String) = if (id in EffectCcMapping.GLOBAL_EFFECT_IDS) EffectCcScope.GLOBAL else EffectCcScope.INSTRUMENT

    private fun setParameter(svc: PlaybackService, id: String, p: EffectParameter, raw: Float): Boolean {
        val index = p.stableId.toInt()
        val success = when {
            scope(id) == EffectCcScope.GLOBAL -> {
                val accepted = if (index == 6) svc.setDelaySend(channel, raw) else svc.setSharedFxParameter(id, index, raw)
                accepted && (if (index == 6) EffectStateRestore.persistDelaySend(this, channel, raw)
                    else EffectStateRestore.persistSharedParameter(this, id, index, raw))
            }
            index == -1 -> svc.setInstrumentEffectEnabled(channel, id, raw >= .5f)
            else -> svc.setInstrumentEffectParameter(channel, id, index, raw)
        }
        if (success) ccController.manualParameterChanged(channel, id, p.stableId, raw, scope(id))
        return success
    }

    private fun parameterControl(id: String, p: EffectParameter): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(8), 0, 0)
        val value = label("${p.displayName}: ${instrumentValueLabel(p, p.value.rawValue)}")
        addView(value)
        val mapped = ccController.mappings().filter { (it.targetInstrumentChannel == channel || (it.scope == EffectCcScope.GLOBAL && it.parameterId != "6")) && it.effectId == id && it.parameterId == p.stableId && it.scope == scope(id) }
        mapped.forEach { mapping ->
            addView(label(mappingDescription(mapping)))
            addView(Button(this@InstrumentEffectsActivity).apply {
                com.piano.sequencer.ui.UiControlStyle.apply(this)
                text = "Edit / Unlearn mapping"
                setOnClickListener { editMapping(mapping, p) }
            })
        }
        val controlRow = LinearLayout(this@InstrumentEffectsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        addView(controlRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        if (p.enumValues.isNotEmpty()) {
            controlRow.addView(Button(this@InstrumentEffectsActivity).apply {
                com.piano.sequencer.ui.UiControlStyle.apply(this)
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                text = instrumentValueLabel(p, p.value.rawValue)
                setOnClickListener {
                    AlertDialog.Builder(this@InstrumentEffectsActivity).setTitle(p.displayName)
                        .setSingleChoiceItems(p.enumValues.map { it.second }.toTypedArray(), p.enumValues.indexOfFirst { it.first == p.value.rawValue }) { dialog, index ->
                            dialog.dismiss()
                            change { setParameter(it, id, p, p.enumValues[index].first) }
                        }.setNegativeButton("Cancel", null).show()
                }
            })
        } else if (p.stableId == "-1") {
            // Header switch owns insert enable; this row adds mapping controls only.
        } else if (p.type == EffectParameterType.BOOLEAN) {
            controlRow.addView(SwitchCompat(this@InstrumentEffectsActivity).apply {
                minimumHeight = dp(48)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = p.displayName
                isChecked = p.value.rawValue >= 0.5f
                setOnCheckedChangeListener { button, checked ->
                    button.isEnabled = false
                    change { svc -> setParameter(svc, id, p, if (checked) 1f else 0f) }
                }
            })
        } else {
            controlRow.addView(SeekBar(this@InstrumentEffectsActivity).apply {
                minimumHeight = dp(48)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                max = 1000
                progress = instrumentParameterProgress(p, p.value.rawValue)
                contentDescription = p.displayName
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    private var tracking = false
                    private fun save(progress: Int) {
                        val raw = instrumentParameterValue(p, progress)
                        submit { svc ->
                            if (!setParameter(svc, id, p, raw))
                                renderSnapshot(svc, true)
                        }
                    }
                    override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                        val raw = instrumentParameterValue(p, progress)
                        value.text = "${p.displayName}: ${instrumentValueLabel(p, raw)}"
                        // Keyboard/accessibility changes do not call onStopTrackingTouch.
                        if (fromUser && !tracking) save(progress)
                    }
                    override fun onStartTrackingTouch(bar: SeekBar?) { tracking = true }
                    override fun onStopTrackingTouch(bar: SeekBar?) {
                        tracking = false
                        save(bar?.progress ?: 0)
                    }
                })
            })
        }
        controlRow.addView(Button(this@InstrumentEffectsActivity).apply {
            com.piano.sequencer.ui.UiControlStyle.apply(this)
            text = "MIDI Learn"
            contentDescription = "MIDI Learn: ${p.displayName}"
            minWidth = 0
            minimumWidth = 0
            maxWidth = dp(112)
            isEnabled = p.maximum > p.minimum
            setOnClickListener { learn(id, p) }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun label(value: String) = TextView(this).apply { text = value; textSize = 14f }
    private fun mappingDescription(m: EffectCcMapping): String {
        val name = if (m.parameterId == "-1") "Insert enabled" else parameters[m.effectId to m.parameterId]?.displayName ?: "Parameter ${m.parameterId}"
        val effect = EFFECTS.firstOrNull { it.first == m.effectId }?.second?.let { getString(it) } ?: m.effectId
        val target = if (m.scope == EffectCcScope.GLOBAL && m.parameterId != "6") "Shared · all 16 channels" else "Instrument ch ${m.targetInstrumentChannel + 1}"
        return "${m.source ?: "Any source"} · MIDI ch ${m.inputChannel?.plus(1) ?: "Any"} · CC ${m.controller}\n$target · $effect · $name\nRaw ${m.minRaw}–${m.maxRaw}${if (m.invert) " · Inverted" else ""} · Soft takeover · ${if (m.disposition == CcDisposition.PASS) "Pass-through" else "Consume"}"
    }

    private fun learn(id: String, p: EffectParameter) {
        val owner = WeakReference(this)
        val started = ccController.startLearn(EffectCcMapping(null, null, 0, channel, id, p.stableId, p.minimum, p.maximum, scope = scope(id))) { result ->
            owner.get()?.runOnUiThread {
                owner.get()?.takeUnless { it.isFinishing || it.isDestroyed }?.onLearnResult(result)
            }
        }
        if (!started) {
            AlertDialog.Builder(this).setMessage("Finish MIDI-file trigger learning first.").setPositiveButton("OK", null).show()
            return
        }
        ownsLearn = true
        learnDialog?.dismiss()
        learnDialog = AlertDialog.Builder(this).setTitle(p.displayName)
            .setMessage("Waiting for MIDI CC…\nMove a controller within 10 seconds.")
            .setNegativeButton("Cancel") { _, _ -> ownsLearn = false; ccController.cancelLearn() }
            .setOnCancelListener { ownsLearn = false; ccController.cancelLearn() }.show()
    }

    private fun onLearnResult(result: EffectCcLearnResult) {
        ownsLearn = false
        learnDialog?.dismiss()
        learnDialog = null
        val mapping = result.mapping
        mapping?.source?.let { learnedSources[mapping.effectId to mapping.parameterId] = it }
        if (mapping != null && triggerConflicts(mapping).isNotEmpty()) {
            // Capture auto-adds when no effect conflict exists. Roll it back until
            // the user explicitly approves deleting overlapping trigger cells.
            if (result.conflict == null) ccController.remove(mapping)
            saveMapping(mapping)
            refresh()
            return
        }
        when {
            result.timedOut -> AlertDialog.Builder(this).setMessage("No MIDI CC received. Learning timed out.").setPositiveButton("OK", null).show()
            mapping != null && result.conflict != null -> confirmConflict(mapping, result.conflict.existing)
            mapping != null -> {
                refresh()
                AlertDialog.Builder(this).setTitle("MIDI CC captured").setMessage(mappingDescription(mapping) + ccWarning(mapping.controller))
                    .setPositiveButton("Edit mapping") { _, _ -> editMapping(mapping, parameters[mapping.effectId to mapping.parameterId]) }
                    .setNegativeButton("Done", null).show()
            }
            else -> AlertDialog.Builder(this).setMessage("Effect MIDI Learn cancelled.").setPositiveButton("OK", null).show()
        }
    }

    private fun confirmConflict(incoming: EffectCcMapping, existing: EffectCcMapping, old: EffectCcMapping? = null) {
        AlertDialog.Builder(this).setTitle("MIDI CC already mapped")
            .setMessage("New mapping:\n${mappingDescription(incoming)}\n\nExisting mapping:\n${mappingDescription(existing)}\n\nReplace the existing mapping?${ccWarning(incoming.controller)}")
            .setNegativeButton("Cancel", null).setPositiveButton("Replace") { _, _ ->
                saveMapping(incoming, old, existing)
            }.show()
    }

    private fun saveMapping(mapping: EffectCcMapping, old: EffectCcMapping? = null, replacing: EffectCcMapping? = null,
                            approvedTriggers: List<SequencerCell>? = null) {
        // Check collisions before removing an edited mapping; Cancel preserves it.
        val conflict = ccController.mappings().firstOrNull { it != old && it != replacing && it.controller == mapping.controller &&
            (it.source == null || mapping.source == null || it.source == mapping.source) &&
            (it.inputChannel == null || mapping.inputChannel == null || it.inputChannel == mapping.inputChannel) }
        if (conflict != null) { confirmConflict(mapping, conflict, old); return }
        val triggers = triggerConflicts(mapping)
        if (triggers.isNotEmpty() && (approvedTriggers == null || triggers.any { it !in approvedTriggers })) {
            AlertDialog.Builder(this).setTitle("CC assigned to sequencer triggers")
                .setMessage("Effect mapping:\n${mappingDescription(mapping)}\n\nOverlapping trigger cells:\n" +
                    triggers.joinToString("\n") { cell ->
                        val content = if (cell.mode == "CHORD") "Chord" else cell.filePath.substringAfterLast('/').ifEmpty { "Empty cell" }
                        "Cell ${cell.id} · $content · CC ${cell.ccNumber} · ${cell.triggerSource ?: "Any source"} · MIDI ch ${if (cell.triggerChannel < 0) "Any" else cell.triggerChannel + 1}"
                    } + "\n\nReplace deletes ALL listed sequencer cells and their trigger assignments, then saves this effect mapping. Cancel keeps existing trigger cells and any old edited effect mapping." + ccWarning(mapping.controller))
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Replace") { _, _ -> saveMapping(mapping, old, replacing, triggers) }.show()
            return
        }
        val raced = if (replacing == null) ccController.replace(mapping, old)
            else synchronized(ccController) {
                ccController.replace(ccController.mappings().filter { it != old && it != replacing } + mapping)
            }
        if (raced != null) {
            confirmConflict(mapping, raced.existing, old)
        } else {
            // Never delete cells before the effect-to-effect transaction succeeds.
            val store = MidiFileMappingStore.get(this)
            triggers.filter { it in approvedTriggers.orEmpty() }.forEach { cell ->
                // Do not remove a cell edited since the confirmation snapshot.
                if (store.get(cell.id) == cell) store.remove(cell.id)
            }
        }
        refresh()
    }

    private fun triggerConflicts(mapping: EffectCcMapping): List<SequencerCell> =
        MidiFileMappingStore.get(this).all().filter { cell ->
            cell.triggerType == TRIGGER_CC && cell.ccNumber == mapping.controller &&
                (cell.triggerSource == null || mapping.source == null || cell.triggerSource == mapping.source) &&
                (cell.triggerChannel < 0 || mapping.inputChannel == null || cell.triggerChannel == mapping.inputChannel)
        }

    private fun showMappings() {
        val mappings = ccController.mappings().filter { it.targetInstrumentChannel == channel || it.scope == EffectCcScope.GLOBAL }
        if (mappings.isEmpty()) {
            AlertDialog.Builder(this).setTitle("MIDI CC mappings").setMessage("No mappings for this instrument channel. Use MIDI Learn on a parameter.").setPositiveButton("Done", null).show()
            return
        }
        AlertDialog.Builder(this).setTitle("MIDI CC mappings · Ch ${channel + 1}")
            .setItems(mappings.map(::mappingDescription).toTypedArray()) { _, index ->
                val m = mappings[index]
                editMapping(m, parameters[m.effectId to m.parameterId])
            }.setNegativeButton("Done", null).show()
    }

    private fun editMapping(m: EffectCcMapping, p: EffectParameter?) {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        form.addView(label(mappingDescription(m)))
        fun selector(title: String, options: List<String>, selected: Int): Spinner {
            form.addView(label(title))
            return Spinner(this).apply {
                adapter = ArrayAdapter(this@InstrumentEffectsActivity, android.R.layout.simple_spinner_dropdown_item, options)
                setSelection(selected); form.addView(this)
            }
        }
        val source = m.source ?: learnedSources[m.effectId to m.parameterId]
        val sourceSelector = selector("Input source", listOf("Any source") + listOfNotNull(source?.let { "Learned source: $it" }), if (m.source == null) 0 else 1)
        if (source == null) form.addView(label("Learn again to capture a specific source/device."))
        val channelSelector = selector("Input MIDI channel", listOf("Any channel") + (1..16).map { "Channel $it" }, m.inputChannel?.plus(1) ?: 0)
        fun field(title: String, value: Float): EditText {
            form.addView(label(title))
            return EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                setText(value.toString()); form.addView(this)
            }
        }
        val minimum = field("Minimum raw value", m.minRaw)
        val maximum = field("Maximum raw value", m.maxRaw)
        val invert = SwitchCompat(this).apply { text = "Invert"; isChecked = m.invert; form.addView(this) }
        selector("Pickup mode", listOf("Soft takeover (current)"), 0)
        form.addView(label("Only Soft takeover is supported. Move the controller to the current value before changes apply. Immediate mode is unavailable."))
        val consume = SwitchCompat(this).apply { text = "Consume MIDI CC (off: Pass-through)"; isChecked = m.disposition == CcDisposition.CONSUME; form.addView(this) }
        val warning = label(ccWarning(m.controller).trim()).apply { visibility = if (text.isEmpty()) View.GONE else View.VISIBLE }
        form.addView(warning)
        consume.setOnCheckedChangeListener { _, _ -> warning.text = ccWarning(m.controller).trim() }
        form.addView(label("Pass-through also sends the CC to the instrument. Consume uses it only for this effect."))
        val dialog = AlertDialog.Builder(this).setTitle("Edit MIDI CC mapping")
            .setView(ScrollView(this).apply { addView(form) }).setNegativeButton("Cancel", null)
            .setNeutralButton("Unlearn") { _, _ -> ccController.remove(m); refresh() }
            .setPositiveButton("Save", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val lo = minimum.text.toString().toFloatOrNull()
                val hi = maximum.text.toString().toFloatOrNull()
                if (lo == null || hi == null || !lo.isFinite() || !hi.isFinite() || hi <= lo ||
                    (p != null && (lo < p.minimum || hi > p.maximum))) {
                    maximum.error = if (p != null) "Use ${p.minimum}–${p.maximum}; maximum must exceed minimum" else "Enter finite values; maximum must exceed minimum"
                } else {
                    dialog.dismiss()
                    saveMapping(m.copy(minRaw = lo, maxRaw = hi, invert = invert.isChecked,
                        source = if (sourceSelector.selectedItemPosition == 0) null else source,
                        inputChannel = (channelSelector.selectedItemPosition - 1).takeIf { it >= 0 },
                        disposition = if (consume.isChecked) CcDisposition.CONSUME else CcDisposition.PASS), m)
                }
            }
        }
        dialog.show()
    }
    private fun ccWarning(cc: Int): String = if (cc in setOf(0, 32, 6, 38, 7, 10, 11, 64) || cc in 120..127)
        "\n\nWarning: CC $cc can change bank, data entry, volume, pan, expression, sustain or channel mode. Pass-through may also affect the instrument; Consume prevents its normal instrument action. Choose deliberately." else ""
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    companion object {
        const val EXTRA_CHANNEL = "instrument_effects_channel"
        private val EFFECTS = listOf("lsp.parametric_eq" to R.string.master_effects_eq,
            "lsp.compressor" to R.string.master_effects_compressor, "lsp.limiter" to R.string.master_effects_limiter,
            "lsp.chorus" to R.string.instrument_effects_chorus, "lsp.clipper" to R.string.instrument_effects_clipper)
    }
}

internal fun instrumentParameterValue(p: EffectParameter, progress: Int): Float {
    val fraction = progress.coerceIn(0, 1000) / 1000.0
    val raw = if (p.logarithmic && p.minimum > 0f)
        exp(ln(p.minimum.toDouble()) + ln(p.maximum.toDouble() / p.minimum) * fraction).toFloat()
    else (p.minimum + (p.maximum - p.minimum) * fraction).toFloat()
    return (if (p.type == EffectParameterType.INTEGER) round(raw) else raw).coerceIn(p.minimum, p.maximum)
}

internal fun instrumentParameterProgress(p: EffectParameter, value: Float): Int {
    if (p.maximum <= p.minimum || !value.isFinite()) return 0
    val raw = value.coerceIn(p.minimum, p.maximum)
    val fraction = if (p.logarithmic && p.minimum > 0f)
        ln(raw.toDouble() / p.minimum) / ln(p.maximum.toDouble() / p.minimum)
    else ((raw - p.minimum) / (p.maximum - p.minimum)).toDouble()
    return round(fraction * 1000).toInt().coerceIn(0, 1000)
}
