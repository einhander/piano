package com.piano.sequencer.ui

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
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
        val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val hint = TextView(context).apply {
            text = context.getString(R.string.ui_multi_summary)
            textSize = 13f
            minimumHeight = dp(48)
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(context.getColor(R.color.ui_primary))
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
                text = context.getString(R.string.multi_channel, channel + 1)
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
        toggle.setOnCheckedChangeListener { _, checked ->
            enabled = checked
            checks.forEach { it.isEnabled = checked }
            onChange(enabled, mask)
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.multi_title)
            .setView(content)
            .setPositiveButton(R.string.multi_close, null)
            .create()
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
