package org.cortex.terminal.emulator

import android.graphics.Color
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A cell keeps the color the scheme had when it was written, so a change of scheme has to be carried
 * into the sessions that were there (an issue: after another scheme was chosen, the rows that
 * appeared when the keyboard closed were in the old one). Colors are real here: Color.rgb needs
 * Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TerminalSchemeChangeTest {

    @Before
    fun setUp() {
        TerminalColor.applyTheme("catppuccin")
        TerminalColor.takeRemap()
    }

    @After
    fun tearDown() {
        TerminalColor.applyTheme("catppuccin")
        TerminalColor.takeRemap()
    }

    private fun feed(emulator: TerminalEmulator, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        emulator.processInput(bytes, 0, bytes.size)
    }

    private fun allRows(emulator: TerminalEmulator): List<TerminalRow> =
        emulator.buffer.history.toList() + emulator.buffer.screen.toList()

    private fun rowStartingWith(emulator: TerminalEmulator, text: String): TerminalRow =
        allRows(emulator).first { it.getText().startsWith(text) }

    private fun feedSample(emulator: TerminalEmulator) {
        // The default colors, an ANSI one, a 256-color one and a 24-bit one, then enough lines to
        // push the first ones into the scrollback
        feed(emulator, "plain\r\n\u001b[31mred\u001b[0m\r\n\u001b[38;5;196mfixed\u001b[0m\r\n" +
            "\u001b[48;2;1;2;3mtrue\u001b[0m\r\n" + "filler\r\n".repeat(6))
    }

    @Test
    fun cellsOfTheOldSchemeTakeTheNewOne() {
        val emulator = TerminalEmulator(rows = 4, cols = 20)
        feedSample(emulator)
        assertTrue("the first lines are in the scrollback", emulator.buffer.history.size >= 3)
        val oldBg = TerminalColor.DEFAULT_BG
        val fixed = TerminalColor.COLOR_PALETTE[196]

        TerminalColor.applyTheme("nord")
        assertNotEquals(oldBg, TerminalColor.DEFAULT_BG)
        val remap = TerminalColor.takeRemap()
        assertNotNull(remap)
        emulator.remapColors(remap!!)

        val plain = rowStartingWith(emulator, "plain")
        assertEquals(TerminalColor.DEFAULT_FG, plain.fgColors[0])
        assertEquals(TerminalColor.DEFAULT_BG, plain.bgColors[0])
        assertEquals("the blank cells after the text too", TerminalColor.DEFAULT_BG, plain.bgColors[19])
        assertEquals(TerminalColor.ANSI_COLORS[1], rowStartingWith(emulator, "red").fgColors[0])
        assertEquals("a color of the 256 is the same in every scheme", fixed, rowStartingWith(emulator, "fixed").fgColors[0])
        assertEquals("a 24-bit color is not touched", Color.rgb(1, 2, 3), rowStartingWith(emulator, "true").bgColors[0])
        for (row in allRows(emulator)) {
            for (i in 0 until row.cols) {
                assertNotEquals("no cell keeps the old background", oldBg, row.bgColors[i])
            }
        }
        assertEquals(TerminalColor.DEFAULT_FG, emulator.buffer.currentFg)
        assertEquals(TerminalColor.DEFAULT_BG, emulator.buffer.currentBg)
    }

    @Test
    fun theSameSchemeChangesNothingAndTheChangeIsTakenOnce() {
        TerminalColor.applyTheme("catppuccin")
        assertNull(TerminalColor.takeRemap())
        TerminalColor.applyTheme("dracula")
        assertNotNull(TerminalColor.takeRemap())
        assertNull("it is handed out once", TerminalColor.takeRemap())
    }

    @Test
    fun aChangeMadeBySettingsIsStillCarriedIntoTheSessions() {
        // SettingsActivity applies the scheme itself, so when the terminal's activity applies it
        // again nothing changes in TerminalColor; the sessions have not been told yet
        val emulator = TerminalEmulator(rows = 4, cols = 20)
        feedSample(emulator)
        TerminalColor.applyTheme("gruvbox")
        TerminalColor.applyTheme("gruvbox")
        val remap = TerminalColor.takeRemap()
        assertNotNull(remap)
        emulator.remapColors(remap!!)
        assertEquals(TerminalColor.DEFAULT_BG, rowStartingWith(emulator, "plain").bgColors[0])
    }

    @Test
    fun theScreenThatWasSetAsideIsChangedToo() {
        val emulator = TerminalEmulator(rows = 4, cols = 20)
        feed(emulator, "main screen\r\n")
        feed(emulator, "\u001b[?1049h" + "full screen program")
        TerminalColor.applyTheme("solarized_dark")
        val remap = TerminalColor.takeRemap()
        assertNotNull(remap)
        emulator.remapColors(remap!!)
        feed(emulator, "\u001b[?1049l")
        val main = rowStartingWith(emulator, "main screen")
        assertEquals(TerminalColor.DEFAULT_BG, main.bgColors[0])
        assertEquals(TerminalColor.DEFAULT_FG, main.fgColors[0])
    }
}
