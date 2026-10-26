package com.piano.sequencer.ui

import android.widget.Button
import com.piano.sequencer.R

/** Local View styling only: never replaces a control, listener or enabled state. */
object UiControlStyle {
    fun apply(button: Button, tonal: Boolean = false, destructive: Boolean = false) {
        val context = button.context
        fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
        button.backgroundTintList = null
        button.background = context.getDrawable(if (tonal) R.drawable.ui_control_tonal else R.drawable.ui_control_outline)
        button.setTextColor(context.getColorStateList(if (destructive) R.color.ui_control_danger_text else R.color.ui_control_text))
        button.isAllCaps = false
        button.textSize = 14f
        button.minHeight = dp(48)
        button.minimumHeight = dp(48)
        button.minWidth = dp(48)
        button.minimumWidth = dp(48)
        button.setPadding(dp(8), dp(4), dp(8), dp(4))
    }
}
