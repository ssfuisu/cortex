package org.cortex.terminal

import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.preference.PreferenceFragmentCompat
import androidx.recyclerview.widget.RecyclerView
import org.cortex.terminal.view.Chrome

/**
 * Styles Android's preference list in the terminal's active color scheme: removes harsh dividers,
 * turns preference rows into rounded cards with ripple feedback, collapses empty icon gutters,
 * and styles category headings and switches across initial and recycled rows.
 */
internal class SettingsStyle(private val fragment: PreferenceFragmentCompat) {

    fun install() {
        val density = fragment.resources.displayMetrics.density
        val horizPad = (12 * density).toInt()
        val vertPad = (6 * density).toInt()
        fragment.setDivider(null)
        fragment.setDividerHeight(0)
        val list = fragment.listView
        list.setPadding(horizPad, vertPad, horizPad, vertPad)
        list.clipToPadding = false
        list.overScrollMode = View.OVER_SCROLL_NEVER

        fun restyleVisible() {
            for (i in 0 until list.childCount) style(list.getChildAt(i))
        }
        list.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) = style(view)
            override fun onChildViewDetachedFromWindow(view: View) {}
        })
        restyleVisible()
        list.post { if (fragment.isAdded) restyleVisible() }
    }

    /** A color that is dimmer when the row is disabled (a setting depending on another). */
    private fun forState(color: Int): ColorStateList = ColorStateList(
        arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
        intArrayOf(Chrome.withAlpha(color, 110), color)
    )

    private fun style(view: View) {
        val p = Chrome.palette
        val d = view.resources.displayMetrics.density
        val hairline = d.toInt().coerceAtLeast(1)
        val title = view.findViewById<TextView>(android.R.id.title)
        val summary = view.findViewById<TextView>(android.R.id.summary)
        val icon = view.findViewById<ImageView>(android.R.id.icon)
        val switch = view.findViewById<View>(androidx.preference.R.id.switchWidget) as? SwitchCompat
        val lp = view.layoutParams as? ViewGroup.MarginLayoutParams

        // Collapse unused empty icon_frame space so preference text aligns cleanly with the card edge
        if (icon != null) {
            (icon.parent as? View)?.let { frame ->
                if (frame !== view) frame.visibility = if (icon.drawable == null) View.GONE else View.VISIBLE
            }
        }

        // PreferenceCategory rows are non-clickable (isSelectable == false); clickable items include
        // preferences whose summary is populated asynchronously or in onResume (distro, desktop_open).
        val isCategory = !view.isClickable || summary == null
        if (isCategory) {
            view.background = null
            view.setPadding((6 * d).toInt(), (10 * d).toInt(), (6 * d).toInt(), (4 * d).toInt())
            lp?.setMargins(0, (4 * d).toInt(), 0, (2 * d).toInt())
            title?.apply {
                setTextColor(p.accent)
                textSize = 12f
                letterSpacing = 0.08f
                isAllCaps = true
            }
            return
        }

        val radius = 14 * d
        view.background = Chrome.ripple(
            Chrome.rounded(p.card, radius, p.outline, hairline),
            Chrome.withAlpha(p.accent, 50),
            radius
        )
        view.setPadding((14 * d).toInt(), (11 * d).toInt(), (14 * d).toInt(), (11 * d).toInt())
        lp?.setMargins(0, (2 * d).toInt(), 0, (2 * d).toInt())

        title?.apply {
            setTextColor(forState(p.text))
            textSize = 15f
            letterSpacing = 0f
            isAllCaps = false
        }
        summary?.setTextColor(forState(p.textDim))
        summary?.textSize = 13f
        if (switch != null) {
            val checked = intArrayOf(android.R.attr.state_checked)
            switch.thumbTintList = ColorStateList(arrayOf(checked, intArrayOf()), intArrayOf(p.accent, p.textFaint))
            switch.trackTintList = ColorStateList(
                arrayOf(checked, intArrayOf()),
                intArrayOf(Chrome.withAlpha(p.accent, 105), p.raisedHigh)
            )
        }
    }
}
