package org.cortex.terminal.view

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import org.cortex.terminal.R

/**
 * The strip above the terminal with one tab for each session, in the colors of the terminal: the
 * session being shown is a rounded tab in the accent, with a close button; the others are plain text.
 * A button at the end starts a new session. It scrolls sideways when the tabs do not fit.
 */
class SessionTabsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    class Tab(val title: String, val isRoot: Boolean)

    var onSelect: ((Int) -> Unit)? = null
    var onClose: ((Int) -> Unit)? = null
    var onNew: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val scroll = HorizontalScrollView(context)
    private val strip = LinearLayout(context)
    private val newButton = TextView(context)
    private var tabs: List<Tab> = emptyList()
    private var active = -1

    private fun dp(v: Int): Int = (v * density + 0.5f).toInt()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL

        scroll.isHorizontalScrollBarEnabled = false
        scroll.overScrollMode = OVER_SCROLL_NEVER
        strip.orientation = HORIZONTAL
        strip.gravity = Gravity.CENTER_VERTICAL
        strip.setPadding(dp(6), 0, dp(2), 0)
        scroll.addView(strip, android.widget.FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        addView(scroll, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))

        newButton.text = "+"
        newButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        newButton.typeface = Typeface.MONOSPACE
        newButton.gravity = Gravity.CENTER
        newButton.contentDescription = context.getString(R.string.drawer_new_session)
        newButton.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            onNew?.invoke()
        }
        addView(newButton, LayoutParams(dp(46), LayoutParams.MATCH_PARENT))
        applyPalette()
    }

    /** Shows these tabs, the one at [activeIndex] being the session on screen. */
    fun update(newTabs: List<Tab>, activeIndex: Int) {
        val unchanged = activeIndex == active && newTabs.size == tabs.size &&
            newTabs.indices.all { newTabs[it].title == tabs[it].title && newTabs[it].isRoot == tabs[it].isRoot }
        tabs = newTabs
        val activeChanged = activeIndex != active
        active = activeIndex
        if (unchanged) return
        rebuild()
        if (activeChanged) scrollToActive()
    }

    /** Draws the tabs again in the colors of the scheme now in use. */
    fun applyPalette() {
        val p = Chrome.palette
        setBackgroundColor(p.bar)
        newButton.setTextColor(p.accent)
        newButton.background = Chrome.ripple(Chrome.rounded(0, dp(8).toFloat()), Chrome.withAlpha(p.accent, 60), dp(8).toFloat())
        rebuild()
    }

    private fun scrollToActive() {
        strip.post {
            val v = strip.getChildAt(active) ?: return@post
            scroll.smoothScrollTo(maxOf(0, v.left - dp(28)), 0)
        }
    }

    private fun rebuild() {
        strip.removeAllViews()
        for ((index, tab) in tabs.withIndex()) {
            strip.addView(tabView(index, tab, index == active))
        }
    }

    private fun text(label: String, sp: Float, color: Int, bold: Boolean): TextView =
        TextView(context).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(color)
            typeface = if (bold) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else Typeface.MONOSPACE
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
        }

    private fun tabView(index: Int, tab: Tab, isActive: Boolean): View {
        val p = Chrome.palette
        val radius = dp(9).toFloat()
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(if (isActive) 3 else 10), 0)
            background = Chrome.ripple(
                Chrome.rounded(if (isActive) p.accentSoft else 0, radius),
                Chrome.withAlpha(p.accent, 50),
                radius
            )
            isClickable = true
            isFocusable = true
            contentDescription = tab.title
            setOnClickListener {
                if (index != active) it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onSelect?.invoke(index)
            }
        }
        row.addView(text((index + 1).toString(), 11f, if (isActive) p.accent else p.textFaint, true))
        val title = text(tab.title, 13f, if (isActive) p.text else p.textDim, isActive).apply {
            maxWidth = dp(150)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
            setPadding(dp(7), 0, 0, 0)
        }
        row.addView(title)
        if (tab.isRoot) {
            row.addView(text(context.getString(R.string.session_root_badge), 9f, p.danger, true).apply {
                setPadding(dp(6), 0, 0, 0)
            })
        }
        if (isActive) {
            val close = text("✕", 12f, p.textDim, false).apply {
                gravity = Gravity.CENTER
                contentDescription = context.getString(R.string.close_session)
                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onClose?.invoke(index)
                }
                background = Chrome.ripple(Chrome.rounded(0, dp(8).toFloat()), Chrome.withAlpha(p.danger, 70), dp(8).toFloat())
            }
            row.addView(close, LayoutParams(dp(30), dp(26)).apply { marginStart = dp(2) })
        }
        row.layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, dp(30)).apply {
            marginStart = dp(2)
            marginEnd = dp(2)
        }
        return row
    }
}
