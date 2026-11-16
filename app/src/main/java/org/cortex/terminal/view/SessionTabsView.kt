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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.cortex.terminal.R

/**
 * The strip above the terminal with one tab for each session, in the colors of the terminal: the
 * session being shown is an elevated pill tab with an accent border, terminal glyph, and close button.
 * A pill button at the end starts a new session.
 */
class SessionTabsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    /**
     * [title] is what a screen reader says ("Session 2"); the tab itself shows the number, or the
     * [name] the user gave the session.
     */
    class Tab(val title: String, val isRoot: Boolean, val name: String? = null)

    var onSelect: ((Int) -> Unit)? = null
    /** A long press on a tab: rename its session */
    var onRename: ((Int) -> Unit)? = null
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
        strip.setPadding(dp(8), dp(4), dp(4), dp(4))
        scroll.addView(strip, android.widget.FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        addView(scroll, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))

        newButton.text = "+"
        newButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
        newButton.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        newButton.includeFontPadding = false
        newButton.gravity = Gravity.CENTER
        newButton.contentDescription = context.getString(R.string.drawer_new_session)
        newButton.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            onNew?.invoke()
        }
        addView(newButton, LayoutParams(dp(34), dp(30)).apply {
            marginStart = dp(2)
            marginEnd = dp(8)
        })
        applyPalette()
    }

    /** Shows these tabs, the one at [activeIndex] being the session on screen. */
    fun update(newTabs: List<Tab>, activeIndex: Int) {
        val unchanged = activeIndex == active && newTabs.size == tabs.size &&
            newTabs.indices.all { newTabs[it].title == tabs[it].title && newTabs[it].isRoot == tabs[it].isRoot && newTabs[it].name == tabs[it].name }
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
        val hairline = dp(1).coerceAtLeast(1)
        setBackgroundColor(p.bar)
        newButton.setTextColor(p.accent)
        newButton.background = Chrome.ripple(
            Chrome.rounded(p.card, dp(9).toFloat(), p.outline, hairline),
            Chrome.withAlpha(p.accent, 65),
            dp(9).toFloat()
        )
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
        val hairline = dp(1).coerceAtLeast(1)
        val radius = dp(10).toFloat()
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(if (isActive) 8 else 12), 0, dp(if (isActive) 4 else 12), 0)
            background = Chrome.ripple(
                if (isActive) {
                    Chrome.rounded(p.accentSoft, radius, Chrome.withAlpha(p.accent, 115), hairline)
                } else {
                    Chrome.rounded(Chrome.withAlpha(p.card, 160), radius, Chrome.withAlpha(p.outline, 130), hairline)
                },
                Chrome.withAlpha(p.accent, 55),
                radius
            )
            isClickable = true
            isFocusable = true
            // The ROOT badge is seen, so it is said as well
            contentDescription = if (tab.isRoot) "${tab.title}, ${context.getString(R.string.session_root_badge)}" else tab.title
            setOnClickListener {
                if (index != active) it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onSelect?.invoke(index)
            }
            setOnLongClickListener {
                val listener = onRename ?: return@setOnLongClickListener false
                it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                listener(index)
                true
            }
        }
        if (isActive) {
            row.addView(View(context).apply {
                background = Chrome.rounded(p.accent, dp(2).toFloat())
            }, LayoutParams(dp(3), dp(14)).apply { marginEnd = dp(6) })
            row.addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_terminal)
                setColorFilter(p.accent)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LayoutParams(dp(13), dp(13)).apply { marginEnd = dp(5) })
        }
        // The number of the session, or the name the user gave it
        val label = tab.name ?: (index + 1).toString()
        row.addView(text(label, 13f, if (isActive) p.text else p.textDim, isActive).apply {
            if (tab.name != null) {
                maxWidth = dp(140)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
        })
        if (tab.isRoot) {
            row.addView(text(context.getString(R.string.session_root_badge), 9f, p.danger, true).apply {
                background = Chrome.rounded(p.dangerSoft, dp(5).toFloat(), Chrome.withAlpha(p.danger, 140), hairline)
                setPadding(dp(5), dp(1), dp(5), dp(1))
            }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) })
        }
        if (isActive) {
            val close = text("✕", 11.5f, p.textDim, false).apply {
                gravity = Gravity.CENTER
                contentDescription = context.getString(R.string.close_session)
                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onClose?.invoke(index)
                }
                background = Chrome.ripple(Chrome.rounded(0, dp(7).toFloat()), Chrome.withAlpha(p.danger, 75), dp(7).toFloat())
            }
            row.addView(close, LayoutParams(dp(26), dp(24)).apply { marginStart = dp(3) })
        }
        row.layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, dp(30)).apply {
            marginStart = dp(3)
            marginEnd = dp(3)
        }
        return row
    }
}
