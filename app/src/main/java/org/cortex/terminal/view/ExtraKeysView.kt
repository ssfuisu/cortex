package org.cortex.terminal.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import org.cortex.terminal.R

class ExtraKeysView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    var onSearchToggle: (() -> Unit)? = null

    private var swipeDownX = 0f
    private var swipeDownY = 0f
    private var swipeConsumed = false

    // Deferred key dispatch: a press only fires its key after a tiny delay so a
    // horizontal swipe across the toolbar can cancel it instead of typing garbage.
    private var pendingKeyAction: Runnable? = null
    private var keyDownX = 0f
    private var keyDownY = 0f

    // Long-press repeat: holding an arrow key keeps sending it (cursor moves fast).
    // First fire stays deferred (55ms, swipe-safe), repeat starts 400ms after
    // ACTION_DOWN and ticks every 60ms until UP/CANCEL/slop/swipe.
    private val repeatHandler = Handler(Looper.getMainLooper())
    private var repeatStarter: Runnable? = null
    private var repeatTicker: Runnable? = null
    private var repeatArmedView: View? = null

    companion object {
        private const val KEY_DEFER_MS = 55L
        private const val REPEAT_START_MS = 400L
        private const val REPEAT_INTERVAL_MS = 60L
        private val REPEATABLE_KEYS = setOf("←", "→", "↑", "↓")
    }

    var terminalView: TerminalView? = null
        set(value) {
            field = value
            value?.onModifiersChanged = {
                post { updateModifierStyles() }
            }
            updateModifierStyles()
        }

    var onMenuClick: (() -> Unit)? = null

    private var ctrlButton: Button? = null
    private var altButton: Button? = null

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.parseColor("#161722"))
        val pad = (2 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad, pad, pad)
        buildLayout()
    }

    private fun buildLayout() {
        val row1Keys = listOf(
            "ESC" to { terminalView?.sendKeySequence(KeyEvent.KEYCODE_ESCAPE) },
            "☰" to { onMenuClick?.invoke() },
            "FIND" to { onSearchToggle?.invoke() },
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

    @SuppressLint("ClickableViewAccessibility")
    private fun createRow(keys: List<Pair<String, () -> Any?>>): LinearLayout {
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
                stateListAnimator = null

                val params = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
                    setMargins(marginPx, marginPx, marginPx, marginPx)
                }
                layoutParams = params

                val moveSlopPx = 22 * resources.displayMetrics.density
                var handledByTouch = false
                setOnClickListener {
                    if (!handledByTouch) {
                        try { action() } catch (_: Exception) {}
                    }
                }
                setOnTouchListener { v, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            v.isPressed = true
                            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            keyDownX = event.x
                            keyDownY = event.y
                            cancelPendingKey(v)
                            cancelRepeat()
                            val runnable = Runnable {
                                pendingKeyAction = null
                                pendingKeyView = null
                                try { action() } catch (_: Exception) {}
                                if (label in REPEATABLE_KEYS) armRepeat(v, action)
                            }
                            pendingKeyAction = runnable
                            pendingKeyView = v
                            v.postDelayed(runnable, KEY_DEFER_MS)
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val moved = kotlin.math.abs(event.x - keyDownX) > moveSlopPx ||
                                        kotlin.math.abs(event.y - keyDownY) > moveSlopPx
                            if (moved) {
                                cancelPendingKey(v)
                                cancelRepeat()
                                v.isPressed = false
                            }
                            true
                        }
                        MotionEvent.ACTION_UP -> {
                            v.isPressed = false
                            handledByTouch = true
                            try {
                                v.performClick()
                            } finally {
                                handledByTouch = false
                            }
                            // Fire immediately if the tap ended before the deferral elapsed.
                            // The manual run may arm repeat; UP always disarms, so taps
                            // never repeat while holds keep ticking until release.
                            pendingKeyAction?.let { r ->
                                cancelPendingKey(v)
                                try { r.run() } catch (_: Exception) {}
                            }
                            cancelRepeat()
                            true
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            v.isPressed = false
                            cancelPendingKey(v)
                            cancelRepeat()
                            true
                        }
                        else -> false
                    }
                }
            }

            if (label == "CTRL") ctrlButton = btn
            if (label == "ALT") altButton = btn

            rowLayout.addView(btn)
        }

        return rowLayout
    }

    private var pendingKeyView: View? = null

    private fun cancelPendingKey(v: View? = null) {
        val target = v ?: pendingKeyView
        pendingKeyAction?.let { r ->
            try { target?.removeCallbacks(r) } catch (_: Exception) {}
        }
        pendingKeyAction = null
        pendingKeyView = null
    }

    private fun armRepeat(v: View, action: () -> Any?) {
        cancelRepeat()
        repeatArmedView = v
        val starter = Runnable {
            repeatStarter = null
            if (repeatArmedView !== v) return@Runnable
            val tick = object : Runnable {
                override fun run() {
                    if (repeatArmedView !== v) return
                    try { action() } catch (_: Exception) {}
                    repeatTicker = this
                    try { repeatHandler.postDelayed(this, REPEAT_INTERVAL_MS) } catch (_: Exception) {}
                }
            }
            repeatTicker = tick
            try { repeatHandler.postDelayed(tick, REPEAT_INTERVAL_MS) } catch (_: Exception) {}
        }
        repeatStarter = starter
        // First fire already happened at KEY_DEFER_MS; start ticking at REPEAT_START_MS.
        try { repeatHandler.postDelayed(starter, REPEAT_START_MS - KEY_DEFER_MS) } catch (_: Exception) {}
    }

    private fun cancelRepeat() {
        repeatArmedView = null
        repeatStarter?.let { s ->
            try { repeatHandler.removeCallbacks(s) } catch (_: Exception) {}
        }
        repeatStarter = null
        repeatTicker?.let { t ->
            try { repeatHandler.removeCallbacks(t) } catch (_: Exception) {}
        }
        repeatTicker = null
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                swipeDownX = ev.x
                swipeDownY = ev.y
                swipeConsumed = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!swipeConsumed) {
                    val dx = ev.x - swipeDownX
                    val dy = kotlin.math.abs(ev.y - swipeDownY)
                    val density = resources.displayMetrics.density
                    // Right-to-left swipe across the toolbar opens terminal search
                    if (dx < -45 * density && kotlin.math.abs(dx) > dy * 1.3f) {
                        swipeConsumed = true
                        cancelPendingKey()
                        cancelRepeat()
                        try { onSearchToggle?.invoke() } catch (_: Exception) {}
                        return true
                    }
                }
            }
        }
        return super.onInterceptTouchEvent(ev)
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
                btn.setTextColor(Color.parseColor("#12131a"))
            } else {
                btn.setBackgroundResource(R.drawable.key_button_bg)
                btn.setTextColor(Color.parseColor("#f5f5f7"))
            }
        }

        altButton?.let { btn ->
            if (view.isAltPressed) {
                btn.setBackgroundResource(R.drawable.key_button_active)
                btn.setTextColor(Color.parseColor("#12131a"))
            } else {
                btn.setBackgroundResource(R.drawable.key_button_bg)
                btn.setTextColor(Color.parseColor("#f5f5f7"))
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelPendingKey()
        cancelRepeat()
    }
}
