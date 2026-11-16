package org.cortex.terminal.view

import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import org.cortex.terminal.emulator.TerminalColor

/**
 * The colors of everything around the terminal (the tabs, the keys, the drawer, the system bars),
 * made from the terminal's color scheme so that the two always match: choosing Nord in the settings
 * changes the keys as much as the text. The terminal's background stays the brightest surface;
 * bars are a little darker, buttons a little lighter than the bars, and the one accent is the scheme's
 * bright blue.
 *
 * The arithmetic is plain integers (no android.graphics.Color), so that it can be tested on the JVM.
 */
object Chrome {

    class Palette(
        /** The terminal's own background and text. */
        val bg: Int,
        val fg: Int,
        /** The bars (tabs, keys, drawer): darker than the terminal. */
        val bar: Int,
        /** Buttons and cards on a bar, and what they become when pressed. */
        val raised: Int,
        val raisedHigh: Int,
        val outline: Int,
        val text: Int,
        val textDim: Int,
        val textFaint: Int,
        val accent: Int,
        /** The accent faintly on the terminal's background, for the selected tab or card. */
        val accentSoft: Int,
        /** Text on the accent. */
        val onAccent: Int,
        val danger: Int,
        val dangerSoft: Int,
        /** Whether the scheme is a dark one (light system bar icons). */
        val dark: Boolean,
        /** Slightly elevated surface for cards inside bars and dialogs. */
        val card: Int = raised,
        /** Semantic success and warning colors derived from the scheme's ANSI palette. */
        val ok: Int = accent,
        val okSoft: Int = accentSoft,
        val warn: Int = accent
    )

    @Volatile
    var palette: Palette = from(TerminalColor.SCHEMES.getValue("catppuccin"))
        private set

    /** Makes [scheme] the one the interface is drawn in. */
    fun apply(scheme: TerminalColor.ColorScheme) {
        palette = from(scheme)
    }

    fun from(scheme: TerminalColor.ColorScheme): Palette = from(scheme.bg, scheme.fg, scheme.ansi)

    fun from(bg: Int, fg: Int, ansi: IntArray): Palette {
        val dark = luminance(bg) < 0.5f
        val bar = if (dark) mix(bg, BLACK, 0.22f) else mix(bg, BLACK, 0.05f)
        val raised = mix(bar, fg, 0.075f)
        val raisedHigh = mix(bar, fg, 0.17f)
        val accentSoft = mix(bg, ansi[12], 0.20f)
        val dangerSoft = mix(bg, ansi[9], 0.18f)
        val card = if (dark) mix(bar, fg, 0.045f) else mix(bar, bg, 0.72f)
        val okSoft = mix(bg, ansi[10], 0.16f)

        // Text must read on every surface it sits on: the lightest of them on a dark scheme, the
        // darkest on a light one. A scheme with little contrast of their own (Solarized) keeps its
        // look; only the text of the interface is moved, as far as it needs, away from the surfaces.
        val away = if (dark) WHITE else BLACK
        val surfaces = listOf(bg, bar, raised, raisedHigh, card, accentSoft, dangerSoft, okSoft)
        val worst = if (dark) surfaces.maxByOrNull { luminance(it) }!! else surfaces.minByOrNull { luminance(it) }!!
        val accent = readable(ansi[12], worst, 3f, away)
        return Palette(
            bg = bg,
            fg = fg,
            bar = bar,
            raised = raised,
            raisedHigh = raisedHigh,
            outline = mix(bar, fg, 0.14f),
            text = readable(fg, worst, 4.5f, away),
            textDim = readable(mix(bg, fg, 0.68f), worst, 4.5f, away),
            textFaint = readable(mix(bg, fg, 0.42f), worst, 3f, away),
            accent = accent,
            accentSoft = accentSoft,
            onAccent = onColor(accent),
            danger = readable(ansi[9], worst, 3f, away),
            dangerSoft = dangerSoft,
            dark = dark,
            card = card,
            ok = readable(ansi[10], worst, 3f, away),
            okSoft = okSoft,
            warn = readable(ansi[11], worst, 3f, away)
        )
    }

    const val BLACK = 0xFF000000.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()

    private fun red(c: Int) = (c shr 16) and 0xff
    private fun green(c: Int) = (c shr 8) and 0xff
    private fun blue(c: Int) = c and 0xff

    fun rgb(r: Int, g: Int, b: Int): Int =
        (0xff shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    /** [a] with [t] (0 to 1) of [b] mixed in, opaque. */
    fun mix(a: Int, b: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        return rgb(
            Math.round(red(a) + (red(b) - red(a)) * f),
            Math.round(green(a) + (green(b) - green(a)) * f),
            Math.round(blue(a) + (blue(b) - blue(a)) * f)
        )
    }

    /** [color] with its opacity set to [alpha] (0 to 255). */
    fun withAlpha(color: Int, alpha: Int): Int = (alpha.coerceIn(0, 255) shl 24) or (color and 0xffffff)

    /** The relative luminance of a color, 0 (black) to 1 (white) (WCAG). */
    fun luminance(color: Int): Float {
        fun channel(v: Int): Float {
            val s = v / 255f
            return if (s <= 0.03928f) s / 12.92f else Math.pow(((s + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        }
        return 0.2126f * channel(red(color)) + 0.7152f * channel(green(color)) + 0.0722f * channel(blue(color))
    }

    /** The WCAG contrast ratio of two colors, 1 to 21. */
    fun contrast(a: Int, b: Int): Float {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
    }

    /**
     * [color] moved toward [toward] until it reads on [against] with at least [minContrast] (WCAG),
     * or as far as [toward] when even that is not enough. Schemes with little contrast of their own
     * (Solarized) keep their look, and only the small text of the interface is helped.
     */
    fun readable(color: Int, against: Int, minContrast: Float, toward: Int): Int {
        if (contrast(color, against) >= minContrast) return color
        var t = 0.05f
        while (t < 1f) {
            val c = mix(color, toward, t)
            if (contrast(c, against) >= minContrast) return c
            t += 0.05f
        }
        return toward
    }

    /** Black or white, whichever reads better on [background]. */
    fun onColor(background: Int): Int =
        if (contrast(background, BLACK) >= contrast(background, WHITE)) BLACK else WHITE

    // ---- Drawables -------------------------------------------------------------------------

    /** A rounded rectangle, with an outline when [strokePx] is more than 0. */
    fun rounded(color: Int, radiusPx: Float, strokeColor: Int = 0, strokePx: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(color)
            if (strokePx > 0) setStroke(strokePx, strokeColor)
        }

    /** [normal] that becomes [pressed] while it is pressed. */
    fun pressable(normal: Drawable, pressed: Drawable): StateListDrawable =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
            addState(intArrayOf(), normal)
        }

    /** [content] with a ripple of [rippleColor] over it, which stays inside the same rounded shape. */
    fun ripple(content: Drawable, rippleColor: Int, radiusPx: Float): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf(rippleColor), content, rounded(WHITE, radiusPx))

    /** A tactile keycap with a subtle bottom lip shadow and ripple feedback. */
    fun keyBackground(p: Palette, radiusPx: Float, strokePx: Int, fillColor: Int = p.raised): Drawable {
        val bevel = strokePx.coerceAtLeast(1)
        val lip = mix(fillColor, BLACK, if (p.dark) 0.42f else 0.14f)
        val normal = LayerDrawable(
            arrayOf(
                rounded(lip, radiusPx),
                rounded(fillColor, radiusPx, p.outline, strokePx)
            )
        ).apply {
            setLayerInset(1, 0, 0, 0, bevel)
        }
        val pressed = rounded(p.raisedHigh, radiusPx, mix(p.outline, p.accent, 0.45f), strokePx)
        return ripple(pressable(normal, pressed), withAlpha(p.accent, 45), radiusPx)
    }

    /** A key that is on (CTRL or ALT waiting for a key): the accent, with tactile depth. */
    fun activeKeyBackground(p: Palette, radiusPx: Float, strokePx: Int): Drawable =
        ripple(
            pressable(
                rounded(p.accent, radiusPx, p.accent, strokePx),
                rounded(mix(p.accent, p.fg, 0.25f), radiusPx, p.accent, strokePx)
            ),
            withAlpha(p.onAccent, 70),
            radiusPx
        )

    /** A button of the drawer or toolbar: the tonal card one, or the filled one with the accent. */
    fun buttonBackground(p: Palette, radiusPx: Float, strokePx: Int, filled: Boolean): Drawable =
        if (filled) {
            ripple(rounded(p.accent, radiusPx), withAlpha(p.onAccent, 70), radiusPx)
        } else {
            ripple(rounded(p.card, radiusPx, p.outline, strokePx), withAlpha(p.accent, 70), radiusPx)
        }

    /** Subtle outlined pill badge for status tags and mode chips. */
    fun chipBackground(fillColor: Int, strokeColor: Int, radiusPx: Float, strokePx: Int): Drawable =
        rounded(fillColor, radiusPx, strokeColor, strokePx)

    /** Builds a text label with a cleanly vertically centered leading vector icon (API 24+ safe). */
    fun iconText(
        context: android.content.Context,
        iconRes: Int,
        label: CharSequence,
        color: Int,
        iconDp: Int = 16
    ): CharSequence {
        val drawable = androidx.appcompat.content.res.AppCompatResources.getDrawable(context, iconRes)?.mutate() ?: return label
        val size = (iconDp * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        drawable.setBounds(0, 0, size, size)
        drawable.setTint(color)
        val span = object : android.text.style.ImageSpan(drawable) {
            override fun draw(
                canvas: android.graphics.Canvas,
                text: CharSequence,
                start: Int,
                end: Int,
                x: Float,
                top: Int,
                y: Int,
                bottom: Int,
                paint: android.graphics.Paint
            ) {
                val fm = paint.fontMetricsInt
                val centerY = y + (fm.ascent + fm.descent) / 2
                val transY = centerY - drawable.bounds.height() / 2
                canvas.save()
                canvas.translate(x, transY.toFloat())
                drawable.draw(canvas)
                canvas.restore()
            }
        }
        return android.text.SpannableStringBuilder("  ").apply {
            setSpan(span, 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append(label)
        }
    }
}
