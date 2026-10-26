package org.cortex.terminal.view

import android.content.DialogInterface
import android.graphics.drawable.InsetDrawable
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

/**
 * Dialogs in the interface's colors: a rounded card of the bar's color with a hairline outline, like the
 * drawer, and a title, message and buttons that read on it. The theme's own dialog is a plain gray
 * square, and a view with a card of its own in it (the download of an update) was a card on a card.
 */
object ChromeDialog {

    private val BUTTONS = intArrayOf(
        DialogInterface.BUTTON_POSITIVE,
        DialogInterface.BUTTON_NEGATIVE,
        DialogInterface.BUTTON_NEUTRAL
    )

    /** Gives [dialog] the interface's colors. Call it before the dialog is shown. */
    fun style(dialog: AlertDialog): AlertDialog {
        val p = Chrome.palette
        val density = dialog.context.resources.displayMetrics.density
        val hairline = density.toInt().coerceAtLeast(1)
        dialog.window?.setBackgroundDrawable(
            InsetDrawable(Chrome.rounded(p.bar, 20 * density, p.outline, hairline), (16 * density).toInt())
        )
        // The title, the message and the buttons are made when the dialog is shown
        dialog.setOnShowListener {
            dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.setTextColor(p.text)
            dialog.findViewById<TextView>(android.R.id.message)?.setTextColor(p.textDim)
            for (which in BUTTONS) dialog.getButton(which)?.setTextColor(p.accent)
        }
        return dialog
    }
}

/** [AlertDialog.Builder.show], with the dialog in the interface's colors. */
fun AlertDialog.Builder.showInChrome(): AlertDialog = ChromeDialog.style(create()).also { it.show() }
