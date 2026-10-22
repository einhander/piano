package com.piano.sequencer.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.ImageView
import androidx.appcompat.app.AlertDialog
import com.piano.sequencer.midi.TRACK_INSTRUMENT_FILE
import com.piano.sequencer.midi.TRACK_INSTRUMENT_MAIN
import com.piano.sequencer.R

/**
 * Track selection dialog for multi-track MIDI files.
 *
 * Compact, scrollable track cards with legible names and labeled controls.
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
        initialInstrumentSets: Map<Int, String> = emptyMap(),
        fileSetName: String? = null,
        onApply: (selectedTracks: List<Int>, trackChannels: Map<Int, Int>, trackInstrumentSets: Map<Int, String>) -> Unit
    ) {
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 16), dp(context, 4), dp(context, 16), dp(context, 4))
        }

        val checks = arrayOfNulls<CheckBox>(trackNames.size)
        val spinners = arrayOfNulls<Spinner>(trackNames.size)
        val setSpinners = arrayOfNulls<Spinner>(trackNames.size)
        val headers = arrayOfNulls<LinearLayout>(trackNames.size)
        val details = arrayOfNulls<LinearLayout>(trackNames.size)

        for (i in trackNames.indices) {
            if (i > 0) list.addView(divider(context))

            val name = trackNames[i].ifEmpty { "Track ${i + 1}" }
            val checked = if (initialSelected == null) true else initialSelected.contains(i)
            val channel = initialChannels[i]

            val nameText = TextView(context).apply {
                text = name
                textSize = 15f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.BLACK)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                contentDescription = "Track ${i + 1}: $name"
            }
            val spinner = Spinner(context).apply {
                adapter = ArrayAdapter(
                    context, android.R.layout.simple_spinner_dropdown_item, MidiFilesPanel.CHANNEL_ITEMS
                )
                setSelection(if (channel == null) 0 else (channel + 1).coerceIn(0, 16))
                contentDescription = "Output channel for $name"
            }
            val check = CheckBox(context).apply {
                text = "Play track"
                isChecked = checked
                textSize = 13f
                contentDescription = "Play track ${i + 1}"
            }
            val sets = listOf("MAIN instruments", "FILE: ${fileSetName ?: "File set"}")
            val setSpinner = Spinner(context).apply {
                adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, sets)
                val initialSet = initialInstrumentSets[i]
                setSelection(if (initialSet == TRACK_INSTRUMENT_FILE ||
                    (initialSet == null && fileSetName != null)) 1 else 0)
            }
            setSpinner.contentDescription = "Instrument set for $name"
            val setSummary = TextView(context).apply {
                text = if (setSpinner.selectedItemPosition == 1) "FILE" else "MAIN"
                textSize = 11f
                setTextColor(0xFF454545.toInt())
            }
            val channelSummary = TextView(context).apply {
                textSize = 11f
                setTextColor(0xFF454545.toInt())
                setPadding(dp(context, 8), 0, 0, 0)
            }
            fun updateChannelSummary() {
                val selected = spinner.selectedItemPosition - 1
                channelSummary.text = if (selected in 0..15) "Ch ${selected + 1}" else "File ch."
            }
            updateChannelSummary()
            setSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) { setSummary.text = "MAIN" }
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    setSummary.text = if (position == 1) "FILE" else "MAIN"
                }
            }
            spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) { updateChannelSummary() }
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) { updateChannelSummary() }
            }

            val row = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(context, 4), 0, dp(context, 4))
            }
            val chevron = ImageView(context).apply {
                setImageResource(R.drawable.ic_track_chevron_down)
                contentDescription = "Expand track settings"
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val header = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                setPadding(dp(context, 4), dp(context, 8), dp(context, 4), dp(context, 8))
                addView(nameText, LinearLayout.LayoutParams(0, -2, 1f))
                addView(check)
                addView(setSummary)
                addView(channelSummary)
                addView(chevron, LinearLayout.LayoutParams(dp(context, 24), dp(context, 24)))
            }
            val detail = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(setting(context, "Instrument source", setSpinner))
                addView(setting(context, "Output channel", spinner))
                visibility = View.GONE
            }
            header.contentDescription = "Track ${i + 1}: $name. Collapsed. Double tap to expand settings"
            header.setOnClickListener {
                val nowExpanded = detail.visibility != View.VISIBLE
                detail.visibility = if (nowExpanded) View.VISIBLE else View.GONE
                chevron.setImageResource(if (nowExpanded) R.drawable.ic_track_chevron_up else R.drawable.ic_track_chevron_down)
                header.contentDescription = "Track ${i + 1}: $name. ${if (nowExpanded) "Expanded" else "Collapsed"}. Double tap to ${if (nowExpanded) "collapse" else "expand"} settings"
            }
            row.addView(header)
            row.addView(detail)
            headers[i] = header
            details[i] = detail

            checks[i] = check
            spinners[i] = spinner
            setSpinners[i] = setSpinner
            list.addView(row)
        }

        // Cap the list at 60% of the screen height (a file can have many
        // tracks); shorter lists size to their content.
        val dm = context.resources.displayMetrics
        val contentEstimate = trackNames.size * dp(context, 56)
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                if (contentEstimate > dm.heightPixels * 0.58) (dm.heightPixels * 0.58).toInt()
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
                val instruments = HashMap<Int, String>()
                for (i in trackNames.indices) {
                    if (checks[i]!!.isChecked) selected.add(i)
                    val pos = spinners[i]!!.selectedItemPosition
                    if (pos > 0) channels[i] = pos - 1
                    instruments[i] = if (setSpinners[i]!!.selectedItemPosition == 1) TRACK_INSTRUMENT_FILE else TRACK_INSTRUMENT_MAIN
                }
                onApply(selected, channels, instruments)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setting(context: Context, label: String, control: View): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val caption = TextView(context).apply {
                text = label
                textSize = 11f
                setTextColor(0xFF626262.toInt())
            }
            addView(caption)
            control.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(control)
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
