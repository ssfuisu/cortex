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
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.preference.ListPreference
import org.cortex.terminal.emulator.TerminalColor
import org.cortex.terminal.view.Chrome
import org.cortex.terminal.view.ChromeDialog

/**
 * The list of color schemes in the settings: each is shown as a miniature terminal preview with
 * window controls, colored prompt syntax, and palette swatches inside an elevated card.
 */
object ThemePicker {

    fun show(context: Context, pref: ListPreference) {
        val names = pref.entries.map { it.toString() }
        val ids = pref.entryValues.map { it.toString() }
        val density = context.resources.displayMetrics.density
        val hairline = density.toInt().coerceAtLeast(1)
        val p = Chrome.palette

        val adapter = object : ArrayAdapter<String>(context, 0, names) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val scheme = TerminalColor.SCHEMES[ids[position]] ?: TerminalColor.SCHEMES.getValue("catppuccin")
                val selected = ids[position] == pref.value

                val card = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    background = Chrome.rounded(
                        if (selected) p.accentSoft else p.card,
                        14 * density,
                        if (selected) p.accent else p.outline,
                        if (selected) (1.5f * density).toInt().coerceAtLeast(1) else hairline
                    )
                    setPadding((12 * density).toInt(), (9 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
                }
                val header = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                header.addView(TextView(context).apply {
                    text = names[position]
                    textSize = 14.5f
                    setTextColor(if (selected) p.accent else p.text)
                    typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (selected) {
                    val checkBadge = FrameLayout(context).apply {
                        background = Chrome.rounded(p.accent, 99 * density)
                        addView(ImageView(context).apply {
                            setImageResource(R.drawable.ic_check)
                            setColorFilter(p.onAccent)
                            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        }, FrameLayout.LayoutParams((13 * density).toInt(), (13 * density).toInt(), Gravity.CENTER))
                    }
                    header.addView(checkBadge, LinearLayout.LayoutParams((20 * density).toInt(), (20 * density).toInt()))
                }
                card.addView(header)
                card.addView(
                    SwatchView(context, scheme, selected, p.accent),
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (48 * density).toInt()).apply {
                        topMargin = (7 * density).toInt()
                    }
                )
                return FrameLayout(context).apply {
                    setPadding((16 * density).toInt(), (4 * density).toInt(), (16 * density).toInt(), (4 * density).toInt())
                    addView(card)
                }
            }
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle(pref.title)
            .setAdapter(adapter) { dialog, which ->
                val id = ids[which]
                if (pref.callChangeListener(id)) pref.value = id
                dialog.dismiss()
                // The settings screen is in the colors of the scheme too
                (context as? android.app.Activity)?.recreate()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        ChromeDialog.style(dialog) {
            dialog.listView?.apply {
                divider = null
                dividerHeight = 0
                setPadding(0, (6 * density).toInt(), 0, (6 * density).toInt())
                clipToPadding = false
            }
        }.show()
    }

    /** Miniature terminal preview card: traffic-light dots, syntax prompt, and palette swatches. */
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
            textSize = 12.5f * d
        }
        private val rect = RectF()
        private val swatchRect = RectF()

        override fun onDraw(canvas: Canvas) {
            val inset = d
            rect.set(inset, inset, width - inset, height - inset)
            val radius = 10 * d
            paint.style = Paint.Style.FILL
            paint.color = scheme.bg
            canvas.drawRoundRect(rect, radius, radius, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = if (selected) 1.5f * d else d
            paint.color = if (selected) accent else Chrome.mix(scheme.bg, scheme.fg, 0.20f)
            canvas.drawRoundRect(rect, radius, radius, paint)

            // Traffic light dots & prompt syntax
            paint.style = Paint.Style.FILL
            val midY = height / 2f
            val dotR = 3.2f * d
            var dx = 12 * d
            for (ansiIdx in intArrayOf(1, 3, 2)) {
                paint.color = scheme.ansi[ansiIdx]
                canvas.drawCircle(dx, midY, dotR, paint)
                dx += 10 * d
            }

            val baseline = midY + 4.5f * d
            var x = dx + 4 * d
            textPaint.color = scheme.ansi[4]
            canvas.drawText("~", x, baseline, textPaint)
            x += textPaint.measureText("~ ")
            textPaint.color = scheme.ansi[2]
            canvas.drawText("$", x, baseline, textPaint)
            x += textPaint.measureText("$ ")
            textPaint.color = scheme.fg
            canvas.drawText("ls ", x, baseline, textPaint)
            x += textPaint.measureText("ls ")
            textPaint.color = scheme.ansi[6]
            canvas.drawText("-la", x, baseline, textPaint)

            // Crisp pill swatches of the 6 palette colors from the right
            val chipW = 9 * d
            val chipH = 16 * d
            var rx = width - 12 * d
            for (i in 6 downTo 1) {
                paint.color = scheme.ansi[i]
                swatchRect.set(rx - chipW, midY - chipH / 2f, rx, midY + chipH / 2f)
                canvas.drawRoundRect(swatchRect, 3.5f * d, 3.5f * d, paint)
                rx -= chipW + 3.5f * d
            }
        }
    }
}
