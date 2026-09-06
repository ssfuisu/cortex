package org.cortex.terminal.view

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.Gravity
import android.view.KeyEvent
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout

class ExtraKeysView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : HorizontalScrollView(context, attrs, defStyleAttr) {

    private val container = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    var terminalView: TerminalView? = null

    private var ctrlButton: Button? = null
    private var altButton: Button? = null

    init {
        isHorizontalScrollBarEnabled = false
        setBackgroundColor(Color.parseColor("#11111b"))
        addView(container)
        populateButtons()
    }

    private fun populateButtons() {
        val keys = listOf(
            "ESC" to { terminalView?.sendKeySequence(KeyEvent.KEYCODE_ESCAPE) },
            "TAB" to { terminalView?.sendKeySequence(KeyEvent.KEYCODE_TAB) },
            "CTRL" to { toggleCtrl() },
            "ALT" to { toggleAlt() },
            "-" to { terminalView?.sendChar('-') },
            "/" to { terminalView?.sendChar('/') },
            "|" to { terminalView?.sendChar('|') },
            "~" to { terminalView?.sendChar('~') },
            "UP" to { terminalView?.sendKeySequence(KeyEvent.KEYCODE_DPAD_UP) },
            "DOWN" to { terminalView?.sendKeySequence(KeyEvent.KEYCODE_DPAD_DOWN) },
            "LEFT" to { terminalView?.sendKeySequence(KeyEvent.KEYCODE_DPAD_LEFT) },
            "RIGHT" to { terminalView?.sendKeySequence(KeyEvent.KEYCODE_DPAD_RIGHT) }
        )

        for ((label, action) in keys) {
            val btn = Button(context).apply {
                text = label
                textSize = 12f
                setTextColor(Color.parseColor("#cdd6f4"))
                setBackgroundColor(Color.parseColor("#1e1e2e"))
                val pad = (8 * resources.displayMetrics.density).toInt()
                setPadding(pad, 0, pad, 0)
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    (36 * resources.displayMetrics.density).toInt()
                ).apply {
                    val margin = (4 * resources.displayMetrics.density).toInt()
                    setMargins(margin, margin, margin, margin)
                }
                layoutParams = params
                isAllCaps = false

                setOnClickListener {
                    action()
                }
            }

            if (label == "CTRL") ctrlButton = btn
            if (label == "ALT") altButton = btn

            container.addView(btn)
        }
    }

    private fun toggleCtrl() {
        val view = terminalView ?: return
        view.isCtrlPressed = !view.isCtrlPressed
        updateModifierStyles()
    }

    private fun toggleAlt() {
        val view = terminalView ?: return
        view.isAltPressed = !view.isAltPressed
        updateModifierStyles()
    }

    fun updateModifierStyles() {
        val view = terminalView ?: return
        ctrlButton?.setBackgroundColor(
            if (view.isCtrlPressed) Color.parseColor("#f38ba8") else Color.parseColor("#1e1e2e")
        )
        ctrlButton?.setTextColor(
            if (view.isCtrlPressed) Color.parseColor("#11111b") else Color.parseColor("#cdd6f4")
        )

        altButton?.setBackgroundColor(
            if (view.isAltPressed) Color.parseColor("#a6e3a1") else Color.parseColor("#1e1e2e")
        )
        altButton?.setTextColor(
            if (view.isAltPressed) Color.parseColor("#11111b") else Color.parseColor("#cdd6f4")
        )
    }
}
