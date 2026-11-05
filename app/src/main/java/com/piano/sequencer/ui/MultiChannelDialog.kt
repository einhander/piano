package com.piano.sequencer.ui

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Button
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.piano.sequencer.PianoApp
import com.piano.sequencer.midi.MidiAction
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import com.piano.sequencer.R

/** Live additive routing and independent, persisted channel-volume controls. */
object MultiChannelDialog {
    fun show(
        context: Context,
        initialEnabled: Boolean,
        initialMask: Int,
        initialVolumes: IntArray,
        onVolumeChange: (channel: Int, value: Int) -> Unit,
        onChange: (enabled: Boolean, mask: Int) -> Unit
    ) {
        var enabled = initialEnabled
        var mask = initialMask and 0xFFFF
        fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
        }
        val toggle = SwitchCompat(context).apply {
            text = context.getString(R.string.multi_enable)
            minHeight = dp(48)
            isChecked = enabled
        }
        content.addView(toggle)
        val app = context.applicationContext as PianoApp
        val mapper = app.actionMidiMapper
        val prefs = context.getSharedPreferences("piano_prefs", Context.MODE_PRIVATE)
        val handler = Handler(Looper.getMainLooper())
        var ownsLearn = false
        var closed = false
        val status = TextView(context).apply { textSize = 13f; setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END }
        val learn = Button(context).apply { text = "Learn"; UiControlStyle.apply(this) }
        val unlearn = Button(context).apply { text = "Unlearn"; UiControlStyle.apply(this) }
        fun refreshMapping(message: String? = null) {
            val assignment = mapper.mapping(MidiAction.MULTI)?.let { midiAssignmentLabel(it.source, it.channel) }
            status.text = if (ownsLearn) message ?: "Move a MIDI control…" else assignment ?: message ?: "No MIDI assignment"
            learn.text = if (ownsLearn) "Cancel Learn" else "Learn"
            unlearn.isEnabled = ownsLearn || mapper.mapping(MidiAction.MULTI) != null
        }
        fun finishLearn(message: String? = null) {
            if (ownsLearn) {
                mapper.cancelLearn()
                app.setActionMidiLearnListener(null)
                ownsLearn = false
            }
            handler.removeCallbacksAndMessages(null)
            refreshMapping(message)
        }
        learn.setOnClickListener {
            if (ownsLearn) finishLearn("Learning cancelled.")
            else if (app.instrumentEffectCcController.isLearning() ||
                com.piano.sequencer.midi.MidiFileLearnState.getState() == com.piano.sequencer.midi.MidiFileLearnState.State.LEARNING) {
                refreshMapping("Finish current MIDI Learn first.")
            }
            else if (!mapper.startLearn(MidiAction.MULTI)) refreshMapping("Another MIDI Learn is active.")
            else {
                ownsLearn = true
                app.setActionMidiLearnListener { result ->
                    handler.post {
                        if (!closed && ownsLearn && (result.captured == MidiAction.MULTI || result.conflict != null)) {
                            finishLearn(if (result.captured == MidiAction.MULTI) "MIDI assignment saved." else "Already assigned to ${result.conflict?.name?.lowercase()}.")
                        }
                    }
                }
                refreshMapping()
                handler.postDelayed({ if (!closed && ownsLearn) finishLearn("Learning timed out.") }, 10_000L)
            }
        }
        unlearn.setOnClickListener {
            finishLearn()
            mapper.clear(MidiAction.MULTI)
            refreshMapping()
        }
        content.addView(status)
        content.addView(LinearLayout(context).apply { addView(learn); addView(unlearn) })
        refreshMapping()
        val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val hint = TextView(context).apply {
            text = context.getString(R.string.ui_multi_summary)
            textSize = 13f
            minimumHeight = dp(48)
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(context.getColor(R.color.ui_primary))
            background = context.getDrawable(R.drawable.ui_control_outline)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener {
                AlertDialog.Builder(context).setTitle(R.string.multi_title)
                    .setMessage(context.getString(R.string.multi_routing_hint) + "\n\n" + context.getString(R.string.multi_volume_hint))
                    .setPositiveButton(R.string.multi_close, null).show()
            }
        }
        content.addView(hint)
        val checks = ArrayList<CheckBox>(16)
        for (channel in 0..15) {
            if (channel > 0) rows.addView(android.view.View(context).apply {
                setBackgroundColor(context.getColor(R.color.ui_outline))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
            val group = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            val heading = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            val check = CheckBox(context).apply {
                text = context.getString(R.string.ui_multi_channel_short, channel + 1)
                contentDescription = context.getString(R.string.multi_add_channel, channel + 1)
                minHeight = dp(48)
                isChecked = mask and (1 shl channel) != 0
                isEnabled = enabled
                setOnCheckedChangeListener { _, checked ->
                    mask = if (checked) mask or (1 shl channel) else mask and (1 shl channel).inv()
                    onChange(enabled, mask)
                }
            }
            checks.add(check)
            heading.addView(check, LinearLayout.LayoutParams(dp(94), ViewGroup.LayoutParams.WRAP_CONTENT))
            val value = initialVolumes.getOrElse(channel) { 100 }.coerceIn(0, 127)
            val valueLabel = TextView(context).apply {
                text = context.getString(R.string.ui_volume_value, value)
                textSize = 14f
                minWidth = dp(32)
                gravity = Gravity.END
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            heading.addView(SeekBar(context).apply {
                max = 127
                progress = value
                minimumHeight = dp(48)
                contentDescription = context.getString(R.string.multi_channel_volume, channel + 1)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                        valueLabel.text = context.getString(R.string.ui_volume_value, progress)
                        if (fromUser) onVolumeChange(channel, progress)
                    }
                    override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(bar: SeekBar?) = Unit
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            heading.addView(valueLabel)
            group.addView(heading)
            rows.addView(group)
        }
        val scroll = ScrollView(context).apply {
            isFillViewport = false
            addView(rows)
        }
        content.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        var updating = false
        toggle.setOnCheckedChangeListener { _, checked ->
            enabled = checked
            checks.forEach { it.isEnabled = checked }
            if (!updating) onChange(enabled, mask)
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.multi_title)
            .setView(content)
            .setPositiveButton(R.string.multi_close, null)
            .create()
        val preferenceListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "multi_channel_enabled") handler.post {
                if (!closed) {
                    updating = true
                    toggle.isChecked = prefs.getBoolean("multi_channel_enabled", enabled)
                    updating = false
                }
            }
        }
        val lifecycle = (context as? LifecycleOwner)?.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) dialog.dismiss()
        }
        lifecycle?.addObserver(observer)
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        dialog.setOnDismissListener {
            closed = true
            finishLearn()
            prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
            lifecycle?.removeObserver(observer)
        }
        // Explanation scrolls with rows so landscape/large text still leaves usable list space.
        // Title, enable switch and Close stay fixed.
        dialog.setOnShowListener {
            val frame = android.graphics.Rect()
            dialog.window?.decorView?.getWindowVisibleDisplayFrame(frame)
            val metrics = context.resources.displayMetrics
            val available = frame.height().takeIf { it > 0 } ?: metrics.heightPixels
            dialog.window?.setLayout(minOf(metrics.widthPixels, dp(560)), (available * 0.9f).toInt())
        }
        dialog.show()
    }
}
