package org.cortex.terminal.emulator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Chinese, Japanese and Korean text, the fullwidth forms and emoji take two cells (the first holds the
 * character, the second is [TerminalRow.WIDE_TAIL]), as they do for the programs that count columns
 * with wcwidth(). With one cell each, as before, the glyphs (which are two cells wide) were drawn over
 * the next character and the cursor was not where bash, ls or vim thought it was.
 */
class TerminalWideCharTest {

    private val tail = TerminalRow.WIDE_TAIL
    private val esc = "\u001b"

    private fun TerminalEmulator.feed(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        processInput(bytes, 0, bytes.size)
    }

    private fun TerminalEmulator.row(r: Int = 0) = buffer.screen[r]

    @Test
    fun aWideCharacterTakesTwoCellsAndTheCursorMovesTwo() {
        val e = TerminalEmulator(rows = 3, cols = 10)
        e.feed("a中b")
        val row = e.row()
        assertEquals("a", row.chars[0].toString())
        assertEquals('中', row.chars[1])
        assertEquals(tail, row.chars[2])
        assertEquals('b', row.chars[3])
        assertEquals(4, e.buffer.cursorCol)
        assertEquals("the text has each character once", "a中b", row.getText())
        assertTrue(row.isWideHead(1))
        assertTrue(row.isTail(2))
        assertFalse(row.isWideHead(0))
        assertFalse(row.isTail(3))
    }

    @Test
    fun aLineOfChineseIsTwiceAsLongAsItsCharacters() {
        val e = TerminalEmulator(rows = 3, cols = 10)
        e.feed("你好世界")
        assertEquals(8, e.buffer.cursorCol)
        assertEquals("你好世界", e.row().getText())
        e.feed("ab")
        assertEquals("the cursor waits at the last cell", 9, e.buffer.cursorCol)
        assertTrue(e.buffer.isWrapPending)
        assertEquals("你好世界ab", e.row().getText())
    }

    @Test
    fun theColorsAndStyleOfBothCellsAreTheSame() {
        val e = TerminalEmulator(rows = 3, cols = 10)
        e.feed("$esc[31;1m中$esc[0m")
        val row = e.row()
        assertEquals(row.fgColors[0], row.fgColors[1])
        assertEquals(row.styles[0], row.styles[1])
        assertEquals("bold", 1, row.styles[1].toInt() and 1)
    }

    @Test
    fun anEmojiTakesTwoCellsToo() {
        val e = TerminalEmulator(rows = 3, cols = 10)
        e.feed("x😀y")
        val row = e.row()
        assertTrue(Character.isHighSurrogate(row.chars[1]))
        assertTrue(Character.isLowSurrogate(row.chars[2]))
        assertEquals('y', row.chars[3])
        assertEquals(4, e.buffer.cursorCol)
        assertEquals("x😀y", row.getText())
        assertTrue(row.isWideHead(1))
        assertTrue(row.isTail(2))
    }

    @Test
    fun invisibleCharactersTakeNoCell() {
        val e = TerminalEmulator(rows = 3, cols = 10)
        e.feed("a​b️c")
        assertEquals("abc", e.row().getText())
        assertEquals(3, e.buffer.cursorCol)
    }

    @Test
    fun boxDrawingAndOtherCharactersOfOneCellStillTakeOne() {
        val e = TerminalEmulator(rows = 3, cols = 10)
        e.feed("┌─┐│█é€")
        assertEquals("┌─┐│█é€", e.row().getText())
        assertEquals(7, e.buffer.cursorCol)
        assertFalse(e.row().hasWide)
    }

    @Test
    fun aWideCharacterThatDoesNotFitAtTheEndOfALineGoesToTheNextLine() {
        val e = TerminalEmulator(rows = 3, cols = 5)
        e.feed("abcd中")
        assertEquals("abcd", e.row(0).getText())
        assertTrue("the line goes on", e.row(0).isWrapped)
        assertEquals("中", e.row(1).getText())
        assertEquals(2, e.buffer.cursorCol)
        assertEquals(1, e.buffer.cursorRow)
    }

    @Test
    fun aWideCharacterAtTheEndOfALineWaitsForTheWrap() {
        val e = TerminalEmulator(rows = 3, cols = 4)
        e.feed("ab中")
        assertEquals("ab中", e.row(0).getText())
        assertEquals(3, e.buffer.cursorCol)
        assertTrue(e.buffer.isWrapPending)
        e.feed("c")
        assertEquals("c", e.row(1).getText())
        assertTrue(e.row(0).isWrapped)
    }

    @Test
    fun writingOverTheFirstHalfBlanksTheSecond() {
        val e = TerminalEmulator(rows = 3, cols = 10)
        e.feed("中\ra")
        assertEquals("a", e.row().getText())
        assertEquals(' ', e.row().chars[1])
        assertFalse(e.row().isTail(1))
    }

    @Test
    fun writingOverTheSecondHalfBlanksTheFirst() {
        val e = TerminalEmulator(rows = 3, cols = 10)
        e.feed("中$esc[1;2Hb")
        assertEquals(" b", e.row().getText())
        assertEquals(' ', e.row().chars[0])
    }

    @Test
    fun writingAWideCharacterOverTwoCellsOfOthersLeavesNoHalves() {
        val e = TerminalEmulator(rows = 3, cols = 10)
        e.feed("中文$esc[1;2H好")
        // "好" covers cells 1 and 2: the halves of "中" and "文" next to it are blanked
        assertEquals(" 好", e.row().getText())
        assertEquals('好', e.row().chars[1])
        assertEquals(tail, e.row().chars[2])
        assertEquals(' ', e.row().chars[3])
    }

    @Test
    fun theLineEditorsWayOfDeletingAWideCharacterWorks() {
        // readline: two backspaces, two spaces, two backspaces
        val e = TerminalEmulator(rows = 3, cols = 10)
        e.feed("ab中")
        e.feed("\b\b  \b\b")
        assertEquals("ab", e.row().getText())
        assertEquals(2, e.buffer.cursorCol)
        e.feed("c")
        assertEquals("abc", e.row().getText())
    }

    @Test
    fun erasingHalfOfAWideCharacterErasesIt() {
        val e = TerminalEmulator(rows = 3, cols = 10)
        e.feed("a中b$esc[1;3H$esc[K")
        // From the second cell of "中" to the end of the line
        assertEquals("a", e.row().getText())
    }

    @Test
    fun deletingAndInsertingCharactersLeavesNoHalves() {
        val e = TerminalEmulator(rows = 3, cols = 10)
        e.feed("中文$esc[1;1H$esc[P")
        assertEquals("the first cell went, so did the half left behind", " 文", e.row().getText())
        assertFalse(e.row().isTail(0))
        val f = TerminalEmulator(rows = 3, cols = 10)
        f.feed("中文$esc[1;2H$esc[@")
        // The blank goes between the halves of "中", which are both blanked; "文" moved one cell on
        assertEquals("   文", f.row().getText())
        assertTrue(f.row().isWideHead(3))
    }

    @Test
    fun copiedTextHasEachWideCharacterOnce() {
        val e = TerminalEmulator(rows = 3, cols = 12)
        e.feed("ab你好世界cd")
        assertEquals("你好", e.buffer.getSelectedText(0, 2, 0, 5))
        assertEquals("a half of the first one takes it whole", "你好", e.buffer.getSelectedText(0, 3, 0, 5))
        assertEquals("ab你好世界cd", e.buffer.getSelectedText(0, 0, 0, 11))
    }

    @Test
    fun copiedTextTakesAnEmojiWhicheverHalfTheSelectionEndsOn() {
        val e = TerminalEmulator(rows = 3, cols = 10)
        e.feed("a😀b")
        assertEquals("ends on the first half", "😀", e.buffer.getSelectedText(0, 1, 0, 1))
        assertEquals("ends on the second half", "😀", e.buffer.getSelectedText(0, 1, 0, 2))
        assertEquals("starts on the second half", "😀", e.buffer.getSelectedText(0, 2, 0, 2))
        assertEquals("a😀", e.buffer.getSelectedText(0, 0, 0, 1))
        assertEquals("😀b", e.buffer.getSelectedText(0, 2, 0, 3))
    }

    @Test
    fun copiedTextOfAWrappedLineIsTheLine() {
        val e = TerminalEmulator(rows = 3, cols = 6)
        e.feed("你好世界")
        assertTrue(e.row(0).isWrapped)
        assertEquals("你好世界", e.buffer.getSelectedText(0, 0, 1, 5))
        assertEquals("你好世界", e.buffer.transcript(10))
    }

    @Test
    fun searchFindsChineseAndMarksTheRightCells() {
        val e = TerminalEmulator(rows = 3, cols = 20)
        e.feed("a你好b你好")
        val hits = TerminalSearch.findAll(e.buffer, "你好")
        assertEquals(2, hits.size)
        assertEquals(1, hits[0].startCol)
        assertEquals("covers the second cell of 好 too", 4, hits[0].endCol)
        assertEquals(6, hits[1].startCol)
        assertEquals(9, hits[1].endCol)
        val one = TerminalSearch.findAll(e.buffer, "b")
        assertEquals(5, one[0].startCol)
        assertEquals(5, one[0].endCol)
    }

    @Test
    fun aLinkAfterChineseTextStartsAtItsColumn() {
        val e = TerminalEmulator(rows = 3, cols = 40)
        e.feed("见 https://example.com/a b")
        val hits = TerminalSearch.findUrls(e.buffer)
        assertEquals(1, hits.size)
        assertEquals("https://example.com/a", hits[0].url)
        assertEquals("见 takes two cells and the space one", 3, hits[0].startCol)
        assertEquals(3 + "https://example.com/a".length - 1, hits[0].endCol)
    }

    @Test
    fun rewrappingKeepsTheTextOfWideCharacters() {
        val e = TerminalEmulator(rows = 4, cols = 12)
        e.feed("你好世界你好世界")
        e.resize(4, 6)
        assertEquals("你好世界你好世界", e.buffer.transcript(10))
        e.resize(4, 20)
        assertEquals("你好世界你好世界", e.buffer.transcript(10))
        assertEquals("你好世界你好世界", e.row(0).getText())
        assertEquals("cells 0 and 1 are still one character", '你', e.row(0).chars[0])
        assertTrue(e.row(0).isWideHead(0))
    }

    @Test
    fun aRowWithoutWideCharactersKeepsTheOldFastPaths() {
        val e = TerminalEmulator(rows = 3, cols = 10)
        e.feed("hello")
        assertFalse(e.row().hasWide)
        assertEquals(null, e.row().textColumns())
        e.feed("中")
        assertTrue(e.row().hasWide)
        e.feed("$esc[2J$esc[H")
        e.feed("plain")
        assertEquals("plain", e.row().getText())
    }

    @Test
    fun scrollingKeepsWideCharactersInTheHistory() {
        val e = TerminalEmulator(rows = 2, cols = 10)
        e.feed("中文\r\nabc\r\ndef")
        assertEquals("中文", e.buffer.history.first.getText())
        assertTrue(e.buffer.history.first.isWideHead(0))
    }
}
