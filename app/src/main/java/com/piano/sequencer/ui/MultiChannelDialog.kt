package com.piano.sequencer.ui

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat

/**
 * Multi-channel play mode dialog (live performance mode).
 *
 * A mode switch plus a 4×4 grid of channel checkboxes (1–16; bit i of the
 * mask = channel i). This is a LIVE app: there is no OK/apply button — every
 * change (switch or checkbox) calls [onChange] immediately with the new
 * (enabled, mask) pair; the host updates the live fields, persists both to
 * piano_prefs, and refreshes the toolbar button. The only button is Close.
 *
 * While the mode is off the channel grid is disabled (grayed) — the
 * selection only matters with the mode on. The mask is kept while the mode
 * is off, so re-enabling restores the previous selection.
 *
 * House style: all views created programmatically, no layout XML.
 */
object MultiChannelDialog {

    fun show(
        context: Context,
        initialEnabled: Boolean,
        initialMask: Int,
        onChange: (enabled: Boolean, mask: Int) -> Unit
    ) {
        var enabled = initialEnabled
        var mask = initialMask and 0xFFFF

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 8))
        }

        val checks = arrayOfNulls<CheckBox>(16)

        val toggle = SwitchCompat(context).apply {
            text = "Play on selected channels simultaneously"
            textSize = 14f
            isChecked = enabled
            setOnCheckedChangeListener { _, checked ->
                enabled = checked
                setGridEnabled(checks, checked)
                onChange(enabled, mask)
            }
        }
        content.addView(toggle)

        val grid = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(context, 8))
        }
        for (row in 0..3) {
            val rowLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            for (col in 0..3) {
                val ch = row * 4 + col
                val check = CheckBox(context).apply {
                    text = (ch + 1).toString()
                    textSize = 14f
                    gravity = Gravity.CENTER
                    isChecked = (mask shr ch) and 1 == 1
                    setOnCheckedChangeListener { _, _ ->
                        mask = readMask(checks)
                        onChange(enabled, mask)
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                    )
                }
                checks[ch] = check
                rowLayout.addView(check)
            }
            grid.addView(rowLayout)
        }
        content.addView(grid)
        setGridEnabled(checks, enabled)

        AlertDialog.Builder(context)
            .setTitle("Multi-channel play")
            .setView(content)
            .setPositiveButton("Close", null)
            .show()
    }

    /** Mask from the checkbox states (the single source of truth). */
    private fun readMask(checks: Array<CheckBox?>): Int {
        var m = 0
        for (c in 0..15) if (checks[c]!!.isChecked) m = m or (1 shl c)
        return m
    }

    /** Enable/gray the channel grid: the selection only matters with the mode on. */
    private fun setGridEnabled(checks: Array<CheckBox?>, on: Boolean) {
        for (c in 0..15) {
            checks[c]!!.isEnabled = on
            checks[c]!!.alpha = if (on) 1f else 0.4f
        }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
