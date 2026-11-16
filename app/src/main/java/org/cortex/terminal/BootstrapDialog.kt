package org.cortex.terminal

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import org.cortex.terminal.view.Chrome

/**
 * What the first start shows while Ubuntu is unpacked: the mark and name of the app, what is going on
 * and a bar with the share of the archive done so far, in the colors of the terminal's scheme.
 */
class BootstrapDialog(activity: Activity) {

    private val view: View = activity.layoutInflater.inflate(R.layout.dialog_bootstrap, null)
    private val bar: ProgressBar = view.findViewById(R.id.bootstrapBar)
    private val percent: TextView = view.findViewById(R.id.bootstrapPercent)
    private val dialog: AlertDialog

    init {
        val p = Chrome.palette
        val density = activity.resources.displayMetrics.density
        val hairline = density.toInt().coerceAtLeast(1)
        view.background = Chrome.rounded(p.bar, 24 * density, p.outline, hairline)
        view.findViewById<FrameLayout>(R.id.bootstrapIconBg).background = Chrome.rounded(
            p.accentSoft, 12 * density, Chrome.withAlpha(p.accent, 110), hairline
        )
        view.findViewById<ImageView>(R.id.bootstrapIcon).setColorFilter(p.accent)
        view.findViewById<TextView>(R.id.bootstrapTitle).setTextColor(p.text)
        view.findViewById<TextView>(R.id.bootstrapMessage).setTextColor(p.textDim)
        percent.setTextColor(p.accent)
        percent.background = Chrome.rounded(p.accentSoft, 8 * density, Chrome.withAlpha(p.accent, 100), hairline)
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
