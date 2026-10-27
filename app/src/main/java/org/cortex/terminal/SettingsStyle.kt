package org.cortex.terminal

import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.preference.PreferenceFragmentCompat
import androidx.recyclerview.widget.RecyclerView
import org.cortex.terminal.view.Chrome

/**
 * The settings list in the colors of the terminal's scheme. The list is Android's own, whose texts
 * are light (the theme of the screen is a dark one) and whose accent is one fixed blue: on a light
 * scheme the texts could not be read, and on any other scheme the headings and the switches were of
 * another color than the rest of the app. The rows are colored when they come on screen; the
 * preferences, their keys and what they do are the library's as before.
 */
internal class SettingsStyle(private val fragment: PreferenceFragmentCompat) {

    fun install() {
        val p = Chrome.palette
        fragment.setDivider(ColorDrawable(p.outline))
        fragment.setDividerHeight(1)
        fragment.listView.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) = style(view)
            override fun onChildViewDetachedFromWindow(view: View) {}
        })
    }

    /** A color that is dimmer when the row is off (a setting that depends on another one that is off). */
    private fun forState(color: Int): ColorStateList = ColorStateList(
        arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
        intArrayOf(Chrome.withAlpha(color, 110), color)
    )

    private fun style(view: View) {
        val p = Chrome.palette
        val title = view.findViewById<TextView>(android.R.id.title)
        val summary = view.findViewById<TextView>(android.R.id.summary)
        val switch = view.findViewById<View>(androidx.preference.R.id.switchWidget) as? SwitchCompat
        // A heading has a title and no summary (every preference has one, even an empty one)
        if (summary == null) {
            title?.setTextColor(p.accent)
            return
        }
        title?.setTextColor(forState(p.text))
        summary.setTextColor(forState(p.textDim))
        if (switch != null) {
            val checked = intArrayOf(android.R.attr.state_checked)
            switch.thumbTintList = ColorStateList(arrayOf(checked, intArrayOf()), intArrayOf(p.accent, p.textFaint))
            switch.trackTintList = ColorStateList(
                arrayOf(checked, intArrayOf()),
                intArrayOf(Chrome.withAlpha(p.accent, 100), p.raisedHigh)
            )
        }
    }
}
