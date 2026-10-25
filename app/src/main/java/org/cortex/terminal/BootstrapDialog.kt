package org.cortex.terminal

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import org.cortex.terminal.view.Chrome

/**
 * What the first start shows while Ubuntu is unpacked: the mark and name of the app, what is going on
 * and a bar with the share of the archive done so far, in the colors of the terminal's scheme. The bar
 * just moves until the first figure arrives (and for an archive of unknown size).
 */
class BootstrapDialog(activity: Activity) {

    private val view: View = activity.layoutInflater.inflate(R.layout.dialog_bootstrap, null)
    private val bar: ProgressBar = view.findViewById(R.id.bootstrapBar)
    private val percent: TextView = view.findViewById(R.id.bootstrapPercent)
    private val dialog: AlertDialog

    init {
        val p = Chrome.palette
        val density = activity.resources.displayMetrics.density
        view.background = Chrome.rounded(p.bar, 24 * density, p.outline, density.toInt().coerceAtLeast(1))
        view.findViewById<TextView>(R.id.bootstrapMark).apply {
            background = Chrome.rounded(p.accent, 14 * density)
            setTextColor(p.onAccent)
        }
        view.findViewById<TextView>(R.id.bootstrapTitle).setTextColor(p.text)
        view.findViewById<TextView>(R.id.bootstrapSubtitle).setTextColor(p.textFaint)
        view.findViewById<TextView>(R.id.bootstrapMessage).setTextColor(p.textDim)
        percent.setTextColor(p.textDim)
        bar.progressTintList = ColorStateList.valueOf(p.accent)
        bar.progressBackgroundTintList = ColorStateList.valueOf(p.raisedHigh)
        bar.indeterminateTintList = ColorStateList.valueOf(p.accent)

        dialog = AlertDialog.Builder(activity)
            .setView(view)
            .setCancelable(false)
            .create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }

    var isIndeterminate: Boolean
        get() = bar.isIndeterminate
        set(value) {
            bar.isIndeterminate = value
            percent.visibility = if (value) View.INVISIBLE else View.VISIBLE
        }

    var progress: Int
        get() = bar.progress
        set(value) {
            bar.progress = value
            percent.text = "$value%"
        }

    val isShowing: Boolean get() = dialog.isShowing

    fun show() = dialog.show()

    fun dismiss() = dialog.dismiss()
}
