package org.cortex.terminal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.preference.ListPreference
import org.cortex.terminal.emulator.TerminalColor
import org.cortex.terminal.view.Chrome

/**
 * The list of color schemes in the settings: each is shown as a small piece of terminal in its own
 * colors (a prompt and the six colors of the palette), so that it can be chosen by looking at it.
 */
object ThemePicker {

    fun show(context: Context, pref: ListPreference) {
        val names = pref.entries.map { it.toString() }
        val ids = pref.entryValues.map { it.toString() }
        val density = context.resources.displayMetrics.density
        val accent = Chrome.palette.accent

        val adapter = object : ArrayAdapter<String>(context, 0, names) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val scheme = TerminalColor.SCHEMES[ids[position]] ?: TerminalColor.SCHEMES.getValue("catppuccin")
                val selected = ids[position] == pref.value
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding((20 * density).toInt(), (8 * density).toInt(), (20 * density).toInt(), (8 * density).toInt())
                }
                val header = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                header.addView(TextView(context).apply {
                    text = names[position]
                    textSize = 15f
                    setTextColor(if (selected) accent else 0xFFE6E6E6.toInt())
                    typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (selected) {
                    header.addView(TextView(context).apply {
                        text = "✓"
                        textSize = 16f
                        setTextColor(accent)
                    })
                }
                row.addView(header)
                row.addView(
                    SwatchView(context, scheme, selected, accent),
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (52 * density).toInt()).apply {
                        topMargin = (6 * density).toInt()
                    }
                )
                return row
            }
        }

        AlertDialog.Builder(context)
            .setTitle(pref.title)
            .setAdapter(adapter) { dialog, which ->
                val id = ids[which]
                if (pref.callChangeListener(id)) pref.value = id
                dialog.dismiss()
                // The settings screen is in the colors of the scheme too
                (context as? android.app.Activity)?.recreate()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** A scheme as a bit of terminal: its background, a prompt and a command in its text color, and its six colors. */
    private class SwatchView(
        context: Context,
        private val scheme: TerminalColor.ColorScheme,
        private val selected: Boolean,
        private val accent: Int
    ) : View(context) {
        private val d = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE
            textSize = 13.5f * d
        }
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            val inset = 1.5f * d
            rect.set(inset, inset, width - inset, height - inset)
            val radius = 12 * d
            paint.style = Paint.Style.FILL
            paint.color = scheme.bg
            canvas.drawRoundRect(rect, radius, radius, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = if (selected) 2.5f * d else d
            paint.color = if (selected) accent else Chrome.mix(scheme.bg, scheme.fg, 0.22f)
            canvas.drawRoundRect(rect, radius, radius, paint)

            val baseline = height / 2f + 5 * d
            var x = 14 * d
            textPaint.color = scheme.ansi[2]
            canvas.drawText("~ $", x, baseline, textPaint)
            x += textPaint.measureText("~ $ ")
            textPaint.color = scheme.fg
            canvas.drawText("ls", x, baseline, textPaint)

            // The six colors of the palette, from the right
            paint.style = Paint.Style.FILL
            val dot = 6 * d
            var cx = width - 16 * d
            for (i in 6 downTo 1) {
                paint.color = scheme.ansi[i]
                canvas.drawCircle(cx, height / 2f, dot, paint)
                cx -= dot * 2.7f
            }
        }
    }
}
