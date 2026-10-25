package org.cortex.terminal.view

import org.cortex.terminal.emulator.TerminalColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The colors of the interface around the terminal, which follow the terminal's color scheme. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChromeTest {

    @Test
    fun mixGoesFromOneColorToTheOther() {
        val a = Chrome.rgb(0x11, 0x22, 0x33)
        assertEquals(a, Chrome.mix(a, Chrome.WHITE, 0f))
        assertEquals(Chrome.WHITE, Chrome.mix(a, Chrome.WHITE, 1f))
        assertEquals(Chrome.rgb(0x80, 0x80, 0x80), Chrome.mix(Chrome.BLACK, Chrome.WHITE, 0.5f))
    }

    @Test
    fun luminanceAndContrastFollowWcag() {
        assertEquals(0f, Chrome.luminance(Chrome.BLACK), 0.0001f)
        assertEquals(1f, Chrome.luminance(Chrome.WHITE), 0.0001f)
        assertEquals(21f, Chrome.contrast(Chrome.BLACK, Chrome.WHITE), 0.01f)
        assertEquals(1f, Chrome.contrast(Chrome.WHITE, Chrome.WHITE), 0.0001f)
    }

    @Test
    fun textOnAColorIsBlackOrWhiteWhicheverReadsBetter() {
        assertEquals(Chrome.BLACK, Chrome.onColor(Chrome.rgb(0x89, 0xb4, 0xfa)))
        assertEquals(Chrome.WHITE, Chrome.onColor(Chrome.rgb(0x1e, 0x1e, 0x2e)))
    }

    @Test
    fun readableMovesAColorOnlyAsFarAsItNeeds() {
        val dark = Chrome.rgb(0x10, 0x10, 0x10)
        val already = Chrome.rgb(0xe0, 0xe0, 0xe0)
        assertEquals(already, Chrome.readable(already, dark, 4.5f, Chrome.WHITE))
        val dim = Chrome.rgb(0x30, 0x30, 0x30)
        val moved = Chrome.readable(dim, dark, 4.5f, Chrome.WHITE)
        assertTrue(Chrome.contrast(moved, dark) >= 4.5f)
        assertTrue("it is not pushed all the way to white", moved != Chrome.WHITE)
    }

    @Test
    fun barsAreDarkerThanTheTerminalAndButtonsLighterThanBars() {
        val ansi = IntArray(16) { Chrome.rgb(0x80, 0x80, 0x80) }
        val p = Chrome.from(Chrome.rgb(0x18, 0x18, 0x25), Chrome.rgb(0xcd, 0xd6, 0xf4), ansi)
        assertTrue(p.dark)
        assertTrue(Chrome.luminance(p.bar) < Chrome.luminance(p.bg))
        assertTrue(Chrome.luminance(p.raised) > Chrome.luminance(p.bar))
        assertTrue(Chrome.luminance(p.raisedHigh) > Chrome.luminance(p.raised))
    }

    @Test
    fun aLightSchemeGetsLightIconsOffAndBarsJustBelowTheTerminal() {
        val ansi = IntArray(16) { Chrome.rgb(0x40, 0x40, 0x40) }
        val p = Chrome.from(Chrome.rgb(0xfa, 0xfa, 0xfa), Chrome.rgb(0x20, 0x20, 0x20), ansi)
        assertTrue(!p.dark)
        assertTrue(Chrome.luminance(p.bar) < Chrome.luminance(p.bg))
    }

    /** Whatever the scheme, the text of the interface can be read on its bars and tabs. */
    @Test
    fun everySchemeIsReadableOnItsOwnBars() {
        for (scheme in TerminalColor.SCHEMES.values) {
            val p = Chrome.from(scheme)
            val name = scheme.displayName
            assertTrue("$name: text on the bar", Chrome.contrast(p.text, p.bar) >= 4.5f)
            assertTrue("$name: dim text on the bar", Chrome.contrast(p.textDim, p.bar) >= 4.5f)
            assertTrue("$name: faint text on the bar", Chrome.contrast(p.textFaint, p.bar) >= 3f)
            assertTrue("$name: the accent on the bar", Chrome.contrast(p.accent, p.bar) >= 3f)
            assertTrue("$name: the danger color on the bar", Chrome.contrast(p.danger, p.bar) >= 3f)
            assertTrue("$name: text on the key of the bar", Chrome.contrast(p.text, p.raised) >= 4.5f)
            assertTrue("$name: text on the selected tab", Chrome.contrast(p.text, p.accentSoft) >= 4.5f)
            assertTrue("$name: text on the accent", Chrome.contrast(p.onAccent, p.accent) >= 4.5f)
        }
    }

    @Test
    fun everySchemeIsListedInTheSettings() {
        // res/values/arrays.xml has one entry and one value for each scheme (this test reads the file)
        val candidates = listOf(
            java.io.File("src/main/res/values/arrays.xml"),
            java.io.File("app/src/main/res/values/arrays.xml")
        )
        val text = candidates.first { it.exists() }.readText()
        val values = Regex("""<string-array name="terminal_theme_values"[^>]*>(.*?)</string-array>""", RegexOption.DOT_MATCHES_ALL)
            .find(text)!!.groupValues[1]
        val listed = Regex("<item>([^<]+)</item>").findAll(values).map { it.groupValues[1] }.toList()
        assertEquals(TerminalColor.SCHEMES.keys.toList(), listed)
    }
}
