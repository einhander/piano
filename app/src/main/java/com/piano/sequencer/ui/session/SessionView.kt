package com.piano.sequencer.ui.session

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.GridLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

class SessionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GridLayout(context, attrs) {

    private val clipButtons = mutableMapOf<Pair<Int, Int>, Button>()
    private val trackLabels = mutableListOf<TextView>()
    private val sceneHighlightViews = mutableListOf<TextView>()
    private val recordArmButtons = mutableListOf<Button>()

    private var trackCount = 0
    private var sceneCount = 0

    init {
        orientation = GridLayout.EXACTLY
    }

    fun setupGrid(trackCount: Int, sceneCount: Int) {
        this.trackCount = trackCount
        this.sceneCount = sceneCount

        columnCount = sceneCount + 2  // +2 for track labels + record arm column
        rowCount = trackCount

        // Clear existing children
        removeAllViews()
        clipButtons.clear()
        trackLabels.clear()
        sceneHighlightViews.clear()
        recordArmButtons.clear()

        // Create track labels (first column)
        for (t in 0 until trackCount) {
            val label = TextView(context).apply {
                text = "T${t + 1}"
                setTextColor(Color.WHITE)
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(8, 4, 8, 4)
                background = ContextCompat.getDrawable(context, android.R.drawable.btn_default)
            }
            addView(label, GridLayout.spec(t, 1, Gravity.CENTER))
            trackLabels.add(label)
        }

        // Create record arm buttons (second-to-last column)
        for (t in 0 until trackCount) {
            val armButton = Button(context).apply {
                text = "\u25CF"  // Circle character
                setBackgroundColor(Color.rgb(80, 80, 80))
                setPadding(4, 4, 4, 4)
                setOnClickListener {
                    onRecordArmClick?.invoke(t)
                }
            }
            recordArmButtons.add(armButton)
            addView(armButton, GridLayout.spec(trackCount, 1, Gravity.CENTER))
        }

        // Create scene header labels (top row)
        for (s in 0 until sceneCount) {
            val header = TextView(context).apply {
                text = "S${s + 1}"
                setTextColor(Color.WHITE)
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(4, 4, 4, 4)
            }
            addView(header, GridLayout.spec(GridLayout.TOP, GridLayout.spec(s + 1, 1f)))
            sceneHighlightViews.add(header)
        }

        // Create clip buttons
        for (t in 0 until trackCount) {
            for (s in 0 until sceneCount) {
                val button = Button(context).apply {
                    text = ""
                    setBackgroundColor(Color.rgb(51, 51, 51))
                    setPadding(2, 2, 2, 2)
                    setOnClickListener {
                        // Clip launch callback — caller can observe state changes
                        onClipClick?.invoke(t, s)
                    }
                }
                clipButtons[t to s] = button
                addView(button, GridLayout.spec(t, GridLayout.spec(s + 1, 1f)))
            }
        }

        // Set layout parameters for proper sizing
        for (button in clipButtons.values) {
            val params = button.layoutParams as GridLayout.LayoutParams
            params.setGravity(Gravity.CENTER)
            params.width = 0
            params.height = 0
            params.setMargins(2, 2, 2, 2)
        }

        // Set layout parameters for record arm buttons
        for (button in recordArmButtons) {
            val params = button.layoutParams as GridLayout.LayoutParams
            params.setGravity(Gravity.CENTER)
        }
    }

    fun setClipState(trackId: Int, sceneId: Int, isPlaying: Boolean, isQueued: Boolean) {
        val button = clipButtons[trackId to sceneId] ?: return
        button.post {
            button.setBackgroundColor(
                when {
                    isPlaying -> Color.rgb(0, 200, 0)
                    isQueued -> Color.rgb(255, 200, 0)
                    else -> Color.rgb(51, 51, 51)
                }
            )
        }
    }

    fun setSceneActive(sceneId: Int) {
        for ((index, header) in sceneHighlightViews.withIndex()) {
            header.post {
                if (index == sceneId) {
                    header.setTextColor(Color.rgb(0, 255, 0))
                } else {
                    header.setTextColor(Color.WHITE)
                }
            }
        }
    }

    fun setRecordArmState(trackId: Int, armed: Boolean) {
        val button = recordArmButtons[trackId] ?: return
        button.post {
            button.setBackgroundColor(if (armed) Color.rgb(220, 20, 20) else Color.rgb(80, 80, 80))
        }
    }

    fun setActiveSceneColor(color: Int) {
        for (header in sceneHighlightViews) {
            header.setTextColor(color)
        }
    }

    var onClipClick: ((trackId: Int, sceneId: Int) -> Unit)? = null
    var onRecordArmClick: ((trackId: Int) -> Unit)? = null
}