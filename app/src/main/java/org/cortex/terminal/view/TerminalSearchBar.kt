package org.cortex.terminal.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
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

    private val inputWrapper: LinearLayout
    private val searchIcon: ImageView
    private val input: EditText
    private val countText: TextView
    private val iconButtons = mutableListOf<ImageButton>()

    private var hasMatches = false
    private var swipeStartX = 0f
    private var swipeStartY = 0f
    private var swipeConsumed = false

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val pad = dp(6f)
        val horizPad = dp(10f)
        setPadding(horizPad, pad, horizPad, pad)
        visibility = GONE

        val inputHeight = dp(42f)
        inputWrapper = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(0, inputHeight, 1f).apply {
                marginEnd = dp(8f)
            }
            setPadding(dp(12f), 0, dp(8f), 0)
        }

        searchIcon = ImageView(context).apply {
            setImageResource(R.drawable.ic_search)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LayoutParams(dp(18f), dp(18f)).apply {
                marginEnd = dp(8f)
            }
        }
        inputWrapper.addView(searchIcon)

        input = EditText(context).apply {
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
            background = null
            hint = context.getString(R.string.search_hint)
            textSize = 14f
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(0, 0, dp(6f), 0)
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
        inputWrapper.addView(input)

        countText = TextView(context).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = 11.5f
            text = "0/0"
            setPadding(dp(8f), dp(3f), dp(8f), dp(3f))
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        }
        inputWrapper.addView(countText)
        addView(inputWrapper)

        val btnSize = dp(40f)
        val iconPad = dp(9f)
        fun navButton(iconRes: Int, descRes: Int, gapEnd: Int, action: () -> Unit): ImageButton =
            ImageButton(context).apply {
                layoutParams = LayoutParams(btnSize, btnSize).apply {
                    marginEnd = gapEnd
                }
                setImageResource(iconRes)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(iconPad, iconPad, iconPad, iconPad)
                contentDescription = context.getString(descRes)
                setOnClickListener { action() }
                iconButtons.add(this)
            }

        addView(navButton(R.drawable.ic_chevron_up, R.string.search_previous, dp(5f)) { onPrev?.invoke() })
        addView(navButton(R.drawable.ic_chevron_down, R.string.search_next, dp(5f)) { onNext?.invoke() })
        addView(navButton(R.drawable.ic_close, R.string.search_close, 0) {
            if (onRequestClose != null) {
                onRequestClose?.invoke()
            } else {
                hide()
            }
        })
        applyPalette()
    }

    /** Draws the bar in the colors of the scheme now in use. */
    fun applyPalette() {
        val p = Chrome.palette
        val d = resources.displayMetrics.density
        val hairline = d.toInt().coerceAtLeast(1)
        setBackgroundColor(p.bar)
        inputWrapper.background = Chrome.rounded(p.card, 21 * d, p.outline, hairline)
        searchIcon.setColorFilter(p.textDim)
        input.setTextColor(p.text)
        input.setHintTextColor(p.textFaint)
        applyCountBadgePalette()
        for (b in iconButtons) {
            b.background = Chrome.ripple(
                Chrome.rounded(p.card, 12 * d, p.outline, hairline),
                Chrome.withAlpha(p.accent, 70),
                12 * d
            )
            b.setColorFilter(p.text)
        }
    }

    private fun applyCountBadgePalette() {
        val p = Chrome.palette
        val d = resources.displayMetrics.density
        val hairline = d.toInt().coerceAtLeast(1)
        countText.setTextColor(if (hasMatches) p.accent else p.textDim)
        countText.background = Chrome.rounded(
            if (hasMatches) p.accentSoft else p.raised,
            8 * d,
            if (hasMatches) Chrome.withAlpha(p.accent, 110) else p.outline,
            hairline
        )
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
        hasMatches = total > 0
        countText.text = if (total <= 0) "0/0" else "${index + 1}/$total"
        applyCountBadgePalette()
    }
}
