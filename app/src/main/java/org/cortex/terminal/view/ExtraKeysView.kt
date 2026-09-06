package org.cortex.terminal.view

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.view.KeyEvent
import android.widget.Button
import android.widget.LinearLayout
import org.cortex.terminal.R

class ExtraKeysView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    var terminalView: TerminalView? = null
    var onMenuClick: (() -> Unit)? = null

    private var ctrlButton: Button? = null
    private var altButton: Button? = null

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.parseColor("#000000"))
        buildLayout()
    }

    private fun buildLayout() {
        val row1Keys = listOf(
            "ESC" to { terminalView?.sendKeySequence(KeyEvent.KEYCODE_ESCAPE) },
            "☰" to { onMenuClick?.invoke() },
            "↕" to { terminalView?.sendKeySequence(KeyEvent.KEYCODE_PAGE_DOWN) },
            "HOME" to { terminalView?.sendKeySequence(KeyEvent.KEYCODE_MOVE_HOME) },
            "↑" to { terminalView?.sendKeySequence(KeyEvent.KEYCODE_DPAD_UP) },
            "END" to { terminalView?.sendKeySequence(KeyEvent.KEYCODE_MOVE_END) },
            "PGUP" to { terminalView?.sendKeySequence(KeyEvent.KEYCODE_PAGE_UP) }
        )

        val row2Keys = listOf(
            "⇆" to { terminalView?.sendKeySequence(KeyEvent.KEYCODE_TAB) },
            "CTRL" to { toggleCtrl() },
            "ALT" to { toggleAlt() },
            "←" to { terminalView?.sendKeySequence(KeyEvent.KEYCODE_DPAD_LEFT) },
            "↓" to { terminalView?.sendKeySequence(KeyEvent.KEYCODE_DPAD_DOWN) },
            "→" to { terminalView?.sendKeySequence(KeyEvent.KEYCODE_DPAD_RIGHT) },
            "PGDN" to { terminalView?.sendKeySequence(KeyEvent.KEYCODE_PAGE_DOWN) }
        )

        addView(createRow(row1Keys))
        addView(createRow(row2Keys))
    }

    private fun createRow(keys: List<Pair<String, () -> Unit>>): LinearLayout {
        val rowLayout = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                (38 * resources.displayMetrics.density).toInt()
            )
        }

        val marginPx = (2 * resources.displayMetrics.density).toInt()

        for ((label, action) in keys) {
            val btn = Button(context).apply {
                text = label
                textSize = if (label.length > 3) 10f else 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#ffffff"))
                setBackgroundResource(R.drawable.key_button_bg)
                isAllCaps = false
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 0)

                val params = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
                    setMargins(marginPx, marginPx, marginPx, marginPx)
                }
                layoutParams = params

                setOnClickListener {
                    action()
                }
            }

            if (label == "CTRL") ctrlButton = btn
            if (label == "ALT") altButton = btn

            rowLayout.addView(btn)
        }

        return rowLayout
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

        ctrlButton?.let { btn ->
            if (view.isCtrlPressed) {
                btn.setBackgroundResource(R.drawable.key_button_active)
                btn.setTextColor(Color.parseColor("#181825"))
            } else {
                btn.setBackgroundResource(R.drawable.key_button_bg)
                btn.setTextColor(Color.parseColor("#ffffff"))
            }
        }

        altButton?.let { btn ->
            if (view.isAltPressed) {
                btn.setBackgroundResource(R.drawable.key_button_active)
                btn.setTextColor(Color.parseColor("#181825"))
            } else {
                btn.setBackgroundResource(R.drawable.key_button_bg)
                btn.setTextColor(Color.parseColor("#ffffff"))
            }
        }
    }
}
