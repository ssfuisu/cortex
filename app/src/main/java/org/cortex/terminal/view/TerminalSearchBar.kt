package org.cortex.terminal.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import org.cortex.terminal.R

class TerminalSearchBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    var onQueryChanged: ((String) -> Unit)? = null
    var onNext: (() -> Unit)? = null
    var onPrev: (() -> Unit)? = null
    var onClose: (() -> Unit)? = null
    var onSwipeRight: (() -> Unit)? = null
    var onRequestClose: (() -> Unit)? = null

    private val input: EditText
    private val countText: TextView

    private var swipeStartX = 0f
    private var swipeStartY = 0f
    private var swipeConsumed = false

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(Color.parseColor("#161722"))
        val pad = (6 * resources.displayMetrics.density).toInt()
        val horizPad = (10 * resources.displayMetrics.density).toInt()
        setPadding(horizPad, pad, horizPad, pad)
        visibility = GONE

        val inputHeight = (42 * resources.displayMetrics.density).toInt()
        input = EditText(context).apply {
            layoutParams = LayoutParams(0, inputHeight, 1f).apply {
                marginEnd = (8 * resources.displayMetrics.density).toInt()
            }
            setBackgroundResource(R.drawable.search_bar_bg)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#6c7086"))
            hint = "Search terminal…"
            textSize = 14f
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            val inputPad = (14 * resources.displayMetrics.density).toInt()
            setPadding(inputPad, 0, inputPad, 0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                    onQueryChanged?.invoke(s?.toString() ?: "")
                }
                override fun afterTextChanged(s: Editable?) {}
            })
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    onNext?.invoke()
                    true
                } else false
            }
        }
        addView(input)

        countText = TextView(context).apply {
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginEnd = (8 * resources.displayMetrics.density).toInt()
            }
            setTextColor(Color.parseColor("#a6adc8"))
            textSize = 12f
            text = "0/0"
        }
        addView(countText)

        val btnSize = (48 * resources.displayMetrics.density).toInt()
        val btnPrev = ImageButton(context).apply {
            layoutParams = LayoutParams(btnSize, btnSize).apply {
                marginEnd = (4 * resources.displayMetrics.density).toInt()
            }
            setImageResource(android.R.drawable.arrow_up_float)
            setBackgroundResource(R.drawable.icon_circle_bg)
            contentDescription = "Previous match"
            setColorFilter(Color.WHITE)
            setOnClickListener { onPrev?.invoke() }
        }
        addView(btnPrev)

        val btnNext = ImageButton(context).apply {
            layoutParams = LayoutParams(btnSize, btnSize).apply {
                marginEnd = (4 * resources.displayMetrics.density).toInt()
            }
            setImageResource(android.R.drawable.arrow_down_float)
            setBackgroundResource(R.drawable.icon_circle_bg)
            contentDescription = "Next match"
            setColorFilter(Color.WHITE)
            setOnClickListener { onNext?.invoke() }
        }
        addView(btnNext)

        val btnClose = ImageButton(context).apply {
            layoutParams = LayoutParams(btnSize, btnSize)
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setBackgroundResource(R.drawable.icon_circle_bg)
            contentDescription = "Close search"
            setColorFilter(Color.WHITE)
            setOnClickListener {
                if (onRequestClose != null) {
                    onRequestClose?.invoke()
                } else {
                    hide()
                }
            }
        }
        addView(btnClose)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                swipeStartX = ev.x
                swipeStartY = ev.y
                swipeConsumed = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!swipeConsumed) {
                    val dx = ev.x - swipeStartX
                    val dy = kotlin.math.abs(ev.y - swipeStartY)
                    val density = resources.displayMetrics.density
                    // Left-to-right swipe across the search bar (dx > 45dp) slides back to extra keys
                    if (dx > 45 * density && dx > dy * 1.3f) {
                        swipeConsumed = true
                        onSwipeRight?.invoke()
                        return true
                    }
                }
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                swipeStartX = ev.x
                swipeStartY = ev.y
                swipeConsumed = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!swipeConsumed) {
                    val dx = ev.x - swipeStartX
                    val dy = kotlin.math.abs(ev.y - swipeStartY)
                    val density = resources.displayMetrics.density
                    if (dx > 45 * density && dx > dy * 1.3f) {
                        swipeConsumed = true
                        onSwipeRight?.invoke()
                        return true
                    }
                }
            }
        }
        return super.onTouchEvent(ev)
    }

    fun focusInput() {
        input.requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }

    fun clearInputAndKeyboard() {
        input.setText("")
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        try { imm?.hideSoftInputFromWindow(windowToken, 0) } catch (_: Exception) {}
        try { imm?.hideSoftInputFromWindow(input.windowToken, 0) } catch (_: Exception) {}
    }

    fun show() {
        visibility = VISIBLE
        focusInput()
    }

    fun hide() {
        visibility = GONE
        clearInputAndKeyboard()
        try { onClose?.invoke() } catch (_: Exception) {}
    }

    fun isShowing(): Boolean = visibility == VISIBLE

    fun updateCount(index: Int, total: Int) {
        countText.text = if (total <= 0) "0/0" else "${index + 1}/$total"
    }
}
