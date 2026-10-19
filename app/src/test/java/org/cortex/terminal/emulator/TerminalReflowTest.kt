package org.cortex.terminal.emulator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalReflowTest {

    private val esc = "\u001b"

    private fun TerminalEmulator.feed(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        processInput(bytes, 0, bytes.size)
    }

    private fun TerminalEmulator.screenText(): List<String> = buffer.screen.map { it.getText() }

    @Test
    fun wideningJoinsAWrappedLine() {
        val e = TerminalEmulator(rows = 4, cols = 10)
        e.feed("abcdefghijKLMNO")
        assertTrue(e.buffer.screen[0].isWrapped)

        e.resize(4, 20)

        assertEquals(listOf("abcdefghijKLMNO", "", "", ""), e.screenText())
        assertFalse(e.buffer.screen[0].isWrapped)
        assertEquals(0, e.buffer.cursorRow)
        assertEquals(15, e.buffer.cursorCol)
        assertTrue(e.buffer.screen.all { it.cols == 20 })
    }

    @Test
    fun narrowingSplitsALongLineAndTheCursorFollows() {
        val e = TerminalEmulator(rows = 4, cols = 20)
        e.feed("hello world 12345")

        e.resize(4, 8)

        assertEquals(listOf("hello wo", "rld 1234", "5", ""), e.screenText())
        assertTrue(e.buffer.screen[0].isWrapped)
        assertTrue(e.buffer.screen[1].isWrapped)
        assertFalse(e.buffer.screen[2].isWrapped)
        assertEquals(2, e.buffer.cursorRow)
        assertEquals(1, e.buffer.cursorCol)
        e.feed("6")
        assertEquals("56", e.buffer.screen[2].getText())
    }

    @Test
    fun separateLinesStaySeparate() {
        val e = TerminalEmulator(rows = 4, cols = 10)
        e.feed("ab\r\ncd")

        e.resize(4, 5)
        assertEquals(listOf("ab", "cd", "", ""), e.screenText())
        e.resize(4, 30)
        assertEquals(listOf("ab", "cd", "", ""), e.screenText())
        assertEquals(1, e.buffer.cursorRow)
        assertEquals(2, e.buffer.cursorCol)
    }

    @Test
    fun aPendingWrapContinuesOnTheSameRowWhenThereIsRoom() {
        val e = TerminalEmulator(rows = 4, cols = 5)
        e.feed("abcde")
        assertTrue(e.buffer.isWrapPending)

        e.resize(4, 10)
        assertFalse(e.buffer.isWrapPending)
        assertEquals(5, e.buffer.cursorCol)
        e.feed("f")
        assertEquals(listOf("abcdef", "", "", ""), e.screenText())
    }

    @Test
    fun aCursorAtTheEndOfAFullRowMovesToTheNextRow() {
        val e = TerminalEmulator(rows = 4, cols = 10)
        e.feed("abcde")

        e.resize(4, 5)
        assertEquals(1, e.buffer.cursorRow)
        assertEquals(0, e.buffer.cursorCol)
        assertTrue(e.buffer.screen[0].isWrapped)
        e.feed("f")
        assertEquals(listOf("abcde", "f", "", ""), e.screenText())
    }

    @Test
    fun theTopOfTheScreenStaysAfterClear() {
        val e = TerminalEmulator(rows = 4, cols = 10)
        for (i in 1..10) e.feed("line$i\r\n")
        e.feed("$esc[2J$esc[H$ ")
        assertEquals(7, e.buffer.history.size)

        e.resize(4, 20)

        // the scrollback is not pulled back onto the cleared screen
        assertEquals(7, e.buffer.history.size)
        assertEquals(listOf("$", "", "", ""), e.screenText())
        assertEquals(0, e.buffer.cursorRow)
        assertEquals(2, e.buffer.cursorCol)
    }

    @Test
    fun theScrollbackIsRewrappedToo() {
        val e = TerminalEmulator(rows = 3, cols = 10)
        e.feed("0123456789ABCDE\r\nx\r\ny\r\nz")
        assertEquals(2, e.buffer.history.size)

        e.resize(3, 20)

        assertEquals(1, e.buffer.history.size)
        val first = e.buffer.history.first
        assertEquals("0123456789ABCDE", first.getText())
        assertFalse(first.isWrapped)
        assertEquals(20, first.cols)
        assertEquals(listOf("x", "y", "z"), e.screenText())
        assertEquals(2, e.buffer.cursorRow)
        assertEquals(1, e.buffer.cursorCol)
    }

    @Test
    fun theCursorStaysOnScreenWhenRowsShrinkToo() {
        val e = TerminalEmulator(rows = 6, cols = 10)
        e.feed("l1\r\nl2\r\nl3\r\nl4\r\nl5\r\n")

        e.resize(3, 20)

        assertEquals(listOf("l1", "l2", "l3"), e.buffer.history.map { it.getText() })
        assertEquals(listOf("l4", "l5", ""), e.screenText())
        assertEquals(2, e.buffer.cursorRow)
        assertEquals(0, e.buffer.cursorCol)
    }

    @Test
    fun rowsBelowTheCursorThatNoLongerFitGoIntoTheScrollback() {
        val e = TerminalEmulator(rows = 4, cols = 20)
        e.feed("aaaaaaaaaabbbbbbbbbbccccccccccddddddddddeeeeeeeeee")
        e.feed("$esc[2;1H") // the cursor in the middle of the line, on the "c"s

        e.resize(4, 10)

        // five rows now: one moves into the scrollback, none is dropped
        assertEquals(listOf("aaaaaaaaaa"), e.buffer.history.map { it.getText() })
        assertEquals(listOf("bbbbbbbbbb", "cccccccccc", "dddddddddd", "eeeeeeeeee"), e.screenText())
        assertEquals(1, e.buffer.cursorRow)
        assertEquals(0, e.buffer.cursorCol)
    }

    @Test
    fun coloursAndLinksMoveWithTheirCharacters() {
        val e = TerminalEmulator(rows = 4, cols = 10)
        e.feed("abcdefghij")
        e.buffer.currentFg = 0x100001
        e.feed("$esc]8;;https://example.com$esc\\K$esc]8;;$esc\\")

        e.resize(4, 20)

        val row = e.buffer.screen[0]
        assertEquals('K', row.chars[10])
        assertEquals(0x100001, row.fgColors[10])
        assertEquals("https://example.com", row.linkAt(10))
        assertEquals(null, row.linkAt(9))
    }

    @Test
    fun theAlternateScreenIsLeftToTheProgram() {
        val e = TerminalEmulator(rows = 4, cols = 10)
        e.feed("$esc[?1049habcdefghijKLMNO")

        e.resize(4, 20)

        assertEquals("abcdefghij", e.buffer.screen[0].getText())
        assertEquals("KLMNO", e.buffer.screen[1].getText())
    }

    @Test
    fun reflowingCanBeTurnedOff() {
        val e = TerminalEmulator(rows = 4, cols = 10)
        e.buffer.reflowOnResize = false
        e.feed("abcdefghijKLMNO")

        e.resize(4, 20)

        assertEquals("abcdefghij", e.buffer.screen[0].getText())
        assertEquals("KLMNO", e.buffer.screen[1].getText())
    }

    @Test
    fun aRowOnlyChangeKeepsTheOldBehaviour() {
        // the keyboard showing and hiding changes the rows only: nothing is rewrapped
        val e = TerminalEmulator(rows = 4, cols = 10)
        e.feed("abcdefghijKLMNO")

        e.resize(2, 10)

        assertEquals(listOf("abcdefghij", "KLMNO"), e.screenText())
        assertTrue(e.buffer.screen[0].isWrapped)
    }
}
