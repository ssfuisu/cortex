package org.cortex.terminal.view

import android.content.DialogInterface
import android.graphics.Typeface
import android.graphics.drawable.InsetDrawable
import android.util.TypedValue
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

/**
 * Dialogs in the interface's colors: an elevated rounded card with a crisp hairline outline, refined
 * typography, and pill-tinted action buttons that read cleanly on every color scheme.
 */
object ChromeDialog {

    private val BUTTONS = intArrayOf(
        DialogInterface.BUTTON_POSITIVE,
        DialogInterface.BUTTON_NEGATIVE,
        DialogInterface.BUTTON_NEUTRAL
    )

    /** Gives [dialog] the interface's colors. Call it before the dialog is shown. */
    fun style(dialog: AlertDialog, onShown: (() -> Unit)? = null): AlertDialog {
        val p = Chrome.palette
        val density = dialog.context.resources.displayMetrics.density
        val hairline = density.toInt().coerceAtLeast(1)
        val margin = (16 * density).toInt()
        dialog.window?.setBackgroundDrawable(
            InsetDrawable(Chrome.rounded(p.bar, 24 * density, p.outline, hairline), margin)
        )
        // The title, the message and the buttons are made when the dialog is shown
        dialog.setOnShowListener {
            dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.apply {
                setTextColor(p.text)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            }
            dialog.findViewById<TextView>(android.R.id.message)?.apply {
                setTextColor(p.textDim)
                setLineSpacing(0f, 1.2f)
            }
            val pillRadius = 12 * density
            val hPad = (16 * density).toInt()
            val vPad = (8 * density).toInt()
            for (which in BUTTONS) {
                dialog.getButton(which)?.apply {
                    setTextColor(p.accent)
                    isAllCaps = false
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    background = if (which == DialogInterface.BUTTON_POSITIVE) {
                        Chrome.ripple(
                            Chrome.rounded(p.accentSoft, pillRadius, Chrome.withAlpha(p.accent, 90), hairline),
                            Chrome.withAlpha(p.accent, 70),
                            pillRadius
                        )
                    } else {
                        Chrome.ripple(Chrome.rounded(0, pillRadius), Chrome.withAlpha(p.accent, 50), pillRadius)
                    }
                    setPadding(hPad, vPad, hPad, vPad)
                }
            }
            onShown?.invoke()
        }
        return dialog
    }
}

/** [AlertDialog.Builder.show], with the dialog in the interface's colors. */
fun AlertDialog.Builder.showInChrome(): AlertDialog = ChromeDialog.style(create()).also { it.show() }
