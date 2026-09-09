package com.piano.sequencer.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

/**
 * Track selection dialog for multi-track MIDI files.
 *
 * One row per track: a checkbox (play / skip), the track name (empty name →
 * "Track N", 1-based), and a per-track channel spinner with the same options
 * as the cell's channel spinner ("From file" / "Ch 1..16").
 *
 * House style: all views created programmatically, no layout XML. Colors are
 * hardcoded — the app theme is always light (Theme.MaterialComponents.Light),
 * and platform drawables would resolve against the device system theme.
 *
 * Initial state: [initialSelected] null = all tracks checked (the cell
 * default); a missing [initialChannels] entry = "From file"; a value v =
 * "Ch (v+1)".
 *
 * [onApply] receives the EXPLICIT checked list (never null — the user's
 * choice is stored verbatim; an empty list = the cell is silent) and a map
 * of track index → 0-15 for tracks set to a channel ("From file" tracks are
 * omitted).
 */
object TrackSelectionDialog {

    // Same options as the cell's channel spinner — shared definition
    // (MidiFilesPanel.CHANNEL_ITEMS, position 0 = "From file").

    fun show(
        context: Context,
        trackNames: List<String>,
        initialSelected: List<Int>?,
        initialChannels: Map<Int, Int>,
        onApply: (selectedTracks: List<Int>, trackChannels: Map<Int, Int>) -> Unit
    ) {
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 8))
        }

        val checks = arrayOfNulls<CheckBox>(trackNames.size)
        val spinners = arrayOfNulls<Spinner>(trackNames.size)

        for (i in trackNames.indices) {
            if (i > 0) list.addView(divider(context))

            val name = trackNames[i].ifEmpty { "Track ${i + 1}" }
            val checked = if (initialSelected == null) true else initialSelected.contains(i)
            val channel = initialChannels[i]

            val check = CheckBox(context).apply {
                isChecked = checked
                textSize = 14f
            }

            val nameText = TextView(context).apply {
                text = name
                textSize = 13f
                setTextColor(Color.BLACK)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply {
                    setMargins(dp(context, 4), 0, dp(context, 8), 0)
                }
            }

            val spinner = Spinner(context).apply {
                adapter = ArrayAdapter(
                    context, android.R.layout.simple_spinner_dropdown_item, MidiFilesPanel.CHANNEL_ITEMS
                )
                setSelection(if (channel == null) 0 else (channel + 1).coerceIn(0, 16))
            }

            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(context, 6), 0, dp(context, 6))
            }
            row.addView(check)
            row.addView(nameText)
            row.addView(spinner)

            checks[i] = check
            spinners[i] = spinner
            list.addView(row)
        }

        // Cap the list at 60% of the screen height (a file can have many
        // tracks); shorter lists size to their content.
        val dm = context.resources.displayMetrics
        val contentEstimate = trackNames.size * dp(context, 52)
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                if (contentEstimate > dm.heightPixels * 0.6) (dm.heightPixels * 0.6).toInt()
                else ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(list)
        }

        AlertDialog.Builder(context)
            .setTitle("Tracks")
            .setView(scroll)
            .setPositiveButton("OK") { _, _ ->
                val selected = ArrayList<Int>(trackNames.size)
                val channels = HashMap<Int, Int>()
                for (i in trackNames.indices) {
                    if (checks[i]!!.isChecked) selected.add(i)
                    val pos = spinners[i]!!.selectedItemPosition
                    if (pos > 0) channels[i] = pos - 1
                }
                onApply(selected, channels)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** 1dp divider between rows (hardcoded light gray — always-light theme). */
    private fun divider(context: Context): View {
        return View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 1)
            )
            background = ColorDrawable(0xFFE0E0E0.toInt())
        }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
