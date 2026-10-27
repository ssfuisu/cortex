package org.cortex.terminal.emulator

import org.cortex.terminal.view.Sextants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A character of the supplementary planes that wcwidth() gives one cell (the block sextants that Kilo draws
 * its logo with, the icons of the Nerd Fonts' Material Design set, mathematical letters) takes one cell. It
 * took two, the second half of the character in the second cell, so the programs that drew with them were
 * out of line by a cell for each one. A cell holds one UTF-16 character, so it holds [TerminalRow.ASTRAL] and
 * the character is kept next to it. Emoji, which are two cells wide, are as before.
 */
class TerminalAstralCharTest {

    private val sextant = "\uD83E\uDF3A" // U+1FB3A, SEXTANT-13456
    private val materialIcon = "\uDB80\uDC01" // U+F0001, the first of the Nerd Fonts' Material Design icons
    private val mathBold = "\uD835\uDC00" // U+1D400, MATHEMATICAL BOLD CAPITAL A
    private val fire = "\uD83D\uDD25" // U+1F525, an emoji

    private fun TerminalEmulator.feed(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        processInput(bytes, 0, bytes.size)
    }

    private fun TerminalEmulator.row(r: Int = 0) = buffer.screen[r]

    @Test
    fun theWidthsAreWhatWcwidthSays() {
        assertEquals(1, CharWidth.of(0x1FB3A))
        assertEquals(1, CharWidth.of(0xF0001))
        assertEquals(1, CharWidth.of(0x1D400))
        assertEquals(2, CharWidth.of(0x1F525))
        assertEquals("the mark is a character of one cell", 1, CharWidth.of(TerminalRow.ASTRAL))
    }

    @Test
    fun aSextantTakesOneCellAndTheCursorMovesOne() {
        val e = TerminalEmulator(rows = 3, cols = 10)
        e.feed("a${sextant}b")
        val row = e.row()
        assertEquals('a', row.chars[0])
        assertEquals(TerminalRow.ASTRAL, row.chars[1])
        assertEquals(0x1FB3A, row.astralAt(1))
        assertEquals('b', row.chars[2])
        assertEquals(3, e.buffer.cursorCol)
        assertFalse(row.isWideHead(1))
        assertFalse(row.isTail(2))
        assertEquals("the text has the character", "a${sextant}b", row.getText())
    }

    @Test
    fun aRowOfSextantsFillsAsManyCellsAsThereAreCharacters() {
        val e = TerminalEmulator(rows = 3, cols = 10)
        e.feed(sextant.repeat(10))
        assertEquals("the last cell, and the cursor waits there", 9, e.buffer.cursorCol)
        assertTrue(e.buffer.isWrapPending)
        assertEquals(sextant.repeat(10), e.row().getText())
        e.feed("x")
        assertEquals("x", e.row(1).getText())
        assertTrue(e.row(0).isWrapped)
    }

    @Test
    fun theIconsOfTheMaterialSetAndMathematicalLettersAreOneCellToo() {
        val e = TerminalEmulator(rows = 3, cols = 10)
        e.feed("$materialIcon $mathBold|")
        val row = e.row()
        assertEquals(TerminalRow.ASTRAL, row.chars[0])
        assertEquals(0xF0001, row.astralAt(0))
        assertEquals(' ', row.chars[1])
        assertEquals(TerminalRow.ASTRAL, row.chars[2])
        assertEquals(0x1D400, row.astralAt(2))
        assertEquals('|', row.chars[3])
        assertEquals(4, e.buffer.cursorCol)
    }

    @Test
    fun anEmojiStillTakesTwoCells() {
        val e = TerminalEmulator(rows = 3, cols = 10)
        e.feed("${fire}b")
        val row = e.row()
        assertTrue(row.isWideHead(0))
        assertEquals('b', row.chars[2])
        assertEquals(3, e.buffer.cursorCol)
    }

    @Test
    fun theColorsAndTheStyleAreKept() {
        val e = TerminalEmulator(rows = 3, cols = 10)
        e.feed("\u001b[31;1m$sextant\u001b[0m$sextant")
        val row = e.row()
        assertEquals(1, (row.styles[0].toInt() and 1))
        assertEquals(0, (row.styles[1].toInt() and 1))
        assertTrue(row.fgColors[0] != row.fgColors[1])
    }

    @Test
    fun theTextColumnsOfACharacterOfTwoUtf16CharactersAreItsCell() {
        val e = TerminalEmulator(rows = 3, cols = 10)
        e.feed("a${sextant}b")
        val row = e.row()
        assertEquals(listOf(0, 1, 1, 2), row.textColumns()!!.toList())
        assertEquals("a${sextant}b".length, row.getText().length)
    }

    @Test
    fun theSelectedTextHasTheCharacterOnce() {
        val e = TerminalEmulator(rows = 3, cols = 10)
        e.feed("a${sextant}${materialIcon}b")
        assertEquals("a${sextant}${materialIcon}b", e.buffer.getSelectedText(0, 0, 0, 3))
        assertEquals("$sextant$materialIcon", e.buffer.getSelectedText(0, 1, 0, 2))
    }

    @Test
    fun theTranscriptHasThem() {
        val e = TerminalEmulator(rows = 3, cols = 10)
        e.feed("a${sextant}b\r\nc")
        assertEquals("a${sextant}b\nc", e.buffer.transcript(10))
    }

    @Test
    fun searchFindsTextAroundThemAtTheirColumns() {
        val e = TerminalEmulator(rows = 3, cols = 20)
        e.feed("x${sextant}y needle")
        val hits = TerminalSearch.findAll(e.buffer, "needle")
        assertEquals(1, hits.size)
        assertEquals("one cell for the sextant: n is in the column 4", 4, hits[0].startCol)
        assertEquals(9, hits[0].endCol)
    }

    @Test
    fun overwritingTheCellLeavesOnlyTheNewCharacter() {
        val e = TerminalEmulator(rows = 3, cols = 10)
        e.feed("$sextant\rx")
        assertEquals("x", e.row().getText())
        assertEquals('x', e.row().chars[0])
    }

    @Test
    fun insertingAndDeletingCharactersMoveThemWithTheCells() {
        val e = TerminalEmulator(rows = 3, cols = 10)
        e.feed("ab$sextant")
        val row = e.row()
        row.insertChars(0, 2)
        assertEquals(TerminalRow.ASTRAL, row.chars[4])
        assertEquals(0x1FB3A, row.astralAt(4))
        row.deleteChars(0, 3)
        assertEquals(TerminalRow.ASTRAL, row.chars[1])
        assertEquals(0x1FB3A, row.astralAt(1))
    }

    @Test
    fun aNarrowerRowAndTheCopyOfARowKeepThem() {
        val a = TerminalRow(10)
        a.setAstral(3, 0xF0001, 1, 2)
        val b = TerminalRow(5)
        b.copyFrom(a)
        assertEquals(TerminalRow.ASTRAL, b.chars[3])
        assertEquals(0xF0001, b.astralAt(3))
        assertTrue(b.hasWide)
        a.clear()
        assertEquals("a cleared row has none", ' ', a.chars[3])
        assertEquals(0xFFFD, a.astralAt(3))
    }

    @Test
    fun reflowingKeepsThemAndTheirCells() {
        val e = TerminalEmulator(rows = 4, cols = 10)
        e.feed("ab$sextant${materialIcon}cdefgh")
        e.resize(4, 5)
        assertEquals("ab$sextant${materialIcon}c", e.row(0).getText())
        assertEquals("defgh", e.row(1).getText())
        assertEquals(TerminalRow.ASTRAL, e.row(0).chars[2])
        assertEquals(0x1FB3A, e.row(0).astralAt(2))
        assertEquals(0xF0001, e.row(0).astralAt(3))
        e.resize(4, 10)
        assertEquals("ab$sextant${materialIcon}cdefgh", e.row(0).getText())
        assertEquals(0x1FB3A, e.row(0).astralAt(2))
    }

    @Test
    fun theMarkItselfIsNotShownAsACharacterOfAProgram() {
        val e = TerminalEmulator(rows = 3, cols = 10)
        e.feed("a\uFFFFb")
        val row = e.row()
        assertEquals("a\uFFFDb", row.getText())
        assertEquals('\uFFFD', row.chars[1])
        assertEquals(3, e.buffer.cursorCol)
    }

    @Test
    fun theSextantsAreTheSixtyPatternsWithoutTheHalves() {
        assertEquals(1, Sextants.mask(0x1FB00)) // SEXTANT-1: upper left
        assertEquals(2, Sextants.mask(0x1FB01)) // SEXTANT-2: upper right
        assertEquals(3, Sextants.mask(0x1FB02)) // SEXTANT-12
        assertEquals(4, Sextants.mask(0x1FB03)) // SEXTANT-3: middle left
        assertEquals(6, Sextants.mask(0x1FB05)) // SEXTANT-23
        assertEquals(20, Sextants.mask(0x1FB00 + 19)) // SEXTANT-345
        assertEquals("21 (the left half) has a character of its own", 22, Sextants.mask(0x1FB00 + 20))
        assertEquals(41, Sextants.mask(0x1FB00 + 39))
        assertEquals("42 (the right half) has a character of its own", 43, Sextants.mask(0x1FB00 + 40))
        assertEquals(61, Sextants.mask(0x1FB3A)) // all but the upper right
        assertEquals(62, Sextants.mask(Sextants.LAST))
        val masks = (Sextants.FIRST..Sextants.LAST).map { Sextants.mask(it) }
        assertEquals(60, masks.toSet().size)
        assertTrue(masks.none { it == 0 || it == 21 || it == 42 || it == 63 })
        assertTrue(Sextants.isSextant(0x1FB3A))
        assertFalse(Sextants.isSextant(0x1FB3C))
        assertFalse(Sextants.isSextant(0x2588))
    }
}
