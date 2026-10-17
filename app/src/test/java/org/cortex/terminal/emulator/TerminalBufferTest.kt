package org.cortex.terminal.emulator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalBufferTest {

    @Test
    fun testBufferWriteAndScroll() {
        val buffer = TerminalBuffer(rows = 5, cols = 10, maxHistory = 100)

        // Write simple line
        for (ch in "Hello") {
            buffer.writeChar(ch)
        }
        assertEquals("Hello", buffer.screen[0].getText())

        // Fill 5 rows
        for (i in 1..4) {
            buffer.newLine()
            buffer.carriageReturn()
            for (ch in "Row$i") {
                buffer.writeChar(ch)
            }
        }
        assertEquals("Row4", buffer.screen[4].getText())

        // Scrolling one more line should push first line into history
        buffer.newLine()
        buffer.carriageReturn()
        for (ch in "Row5") {
            buffer.writeChar(ch)
        }

        assertEquals(1, buffer.history.size)
        val firstHistoryRow = buffer.history.first
        assertEquals("Hello", firstHistoryRow.getText())
        assertEquals("Row5", buffer.screen[4].getText())
    }

    @Test
    fun testHistoryRolloverInvalidatesHistoryArrayAndRowsCache() {
        val buffer = TerminalBuffer(rows = 2, cols = 10, maxHistory = 3)

        fun writeLine(text: String, newlineBefore: Boolean = true) {
            if (newlineBefore) {
                buffer.newLine()
                buffer.carriageReturn()
            }
            for (ch in text) {
                buffer.writeChar(ch)
            }
        }

        writeLine("Hist1", newlineBefore = false)
        writeLine("Hist2")
        writeLine("Hist3")
        writeLine("Screen0")
        writeLine("Screen1")

        // History is now at capacity (3: Hist1, Hist2, Hist3)
        assertEquals(3, buffer.history.size)
        // Prime both historyArrayCache and rowsCache at full capacity
        assertEquals("Hist1", buffer.getVisibleRow(0, scrollOffset = 3).getText())
        assertEquals("Hist1", buffer.rowForIndex(-3)?.getText())
        assertEquals("Hist3", buffer.rowForIndex(-1)?.getText())

        // Scroll one more line; history size remains 3 (Hist2, Hist3, Screen0)
        writeLine("Screen2")
        assertEquals(3, buffer.history.size)

        // Both caches must reflect the rolled-over history rather than stale Hist1
        assertEquals("Hist2", buffer.getVisibleRow(0, scrollOffset = 3).getText())
        assertEquals("Hist2", buffer.rowForIndex(-3)?.getText())
        assertEquals("Screen0", buffer.rowForIndex(-1)?.getText())
    }

    @Test
    fun testEraseInDisplay3InvalidatesHistoryCaches() {
        val buffer = TerminalBuffer(rows = 2, cols = 10, maxHistory = 3)

        fun writeLine(text: String, newlineBefore: Boolean = true) {
            if (newlineBefore) {
                buffer.newLine()
                buffer.carriageReturn()
            }
            for (ch in text) {
                buffer.writeChar(ch)
            }
        }

        writeLine("Old1", newlineBefore = false)
        writeLine("Old2")
        writeLine("Old3")
        assertEquals(1, buffer.history.size)
        assertEquals("Old1", buffer.getVisibleRow(0, scrollOffset = 1).getText())
        assertEquals("Old1", buffer.rowForIndex(-1)?.getText())

        // Clear screen and scrollback (CSI 3 J)
        buffer.eraseInDisplay(3)
        buffer.cursorRow = 0
        buffer.cursorCol = 0

        writeLine("New1", newlineBefore = false)
        writeLine("New2")
        writeLine("New3")
        assertEquals(1, buffer.history.size)
        assertEquals("New1", buffer.getVisibleRow(0, scrollOffset = 1).getText())
        assertEquals("New1", buffer.rowForIndex(-1)?.getText())
    }

    // ---- scrolling moves row objects; these pin down what must stay true ----

    private fun TerminalBuffer.line(text: String, newlineBefore: Boolean = true) {
        if (newlineBefore) {
            newLine()
            carriageReturn()
        }
        for (ch in text) writeChar(ch)
    }

    @Test
    fun scrolledOutLineIsHistoryAndNotSharedWithTheScreen() {
        val buffer = TerminalBuffer(rows = 3, cols = 8, maxHistory = 10)
        buffer.line("one", newlineBefore = false)
        buffer.line("two")
        buffer.line("three")
        buffer.line("four") // "one" scrolls into history

        val history = buffer.history.first
        assertEquals("one", history.getText())
        // the screen rows are different objects from the history row, and writing to them leaves it alone
        for (r in buffer.screen.indices) assertTrue(buffer.screen[r] !== history)
        buffer.cursorRow = 0
        buffer.cursorCol = 0
        buffer.writeChar('X')
        assertEquals("one", history.getText())
        assertEquals('X', buffer.screen[0].chars[0])
        assertEquals(listOf("Xwo", "three", "four"), buffer.screen.map { it.getText() })
    }

    @Test
    fun rowFlagsAndLinksTravelWithTheirTextIntoHistory() {
        val buffer = TerminalBuffer(rows = 2, cols = 6, maxHistory = 10)
        buffer.currentLink = "https://example.com"
        buffer.line("ab", newlineBefore = false)
        buffer.currentLink = null
        buffer.screen[0].isUrlLink[0] = true
        buffer.screen[0].isSearchMatch[1] = true
        buffer.screen[0].isWrapped = true
        buffer.line("cd")
        buffer.line("ef") // "ab" scrolls into history

        val moved = buffer.history.first
        assertEquals("ab", moved.getText())
        assertEquals("https://example.com", moved.linkAt(0))
        assertEquals("https://example.com", moved.linkAt(1))
        assertTrue(moved.isUrlLink[0])
        assertTrue(moved.isSearchMatch[1])
        assertTrue(moved.isWrapped)
        // and the new bottom line starts clean
        val bottom = buffer.screen[1]
        assertNull(bottom.links)
        assertFalse(bottom.isWrapped)
        assertTrue(bottom.isUrlLink.none { it })
    }

    @Test
    fun reusedHistoryRowComesBackBlank() {
        val buffer = TerminalBuffer(rows = 2, cols = 6, maxHistory = 2) // full after two scrolled lines
        buffer.currentFg = 0x00FF00
        buffer.currentBg = 0x0000FF
        buffer.currentLink = "https://example.com"
        for (i in 1..8) buffer.line("l$i", newlineBefore = i > 1)
        buffer.currentFg = TerminalColor.DEFAULT_FG
        buffer.currentBg = TerminalColor.DEFAULT_BG
        buffer.currentLink = null
        buffer.newLine() // the oldest of the two history rows is dropped and reused as the bottom line

        assertEquals(2, buffer.history.size)
        val bottom = buffer.screen[buffer.rows - 1]
        assertEquals("", bottom.getText())
        assertTrue(bottom.chars.all { it == ' ' })
        assertTrue(bottom.fgColors.all { it == TerminalColor.DEFAULT_FG })
        assertTrue(bottom.bgColors.all { it == TerminalColor.DEFAULT_BG })
        assertNull(bottom.links)
        assertFalse(bottom.isWrapped)
        val everyRow = buffer.history.toList() + buffer.screen.toList()
        assertEquals("a row object must never be in the history and on the screen at once", everyRow.size, everyRow.toSet().size)
    }

    @Test
    fun historyRowsOfAnOldWidthAreNotReusedAfterAResize() {
        val buffer = TerminalBuffer(rows = 2, cols = 6, maxHistory = 2)
        for (i in 1..4) buffer.line("w$i", newlineBefore = i > 1)
        buffer.resize(2, 9)
        for (i in 1..4) buffer.line("n$i")

        assertTrue(buffer.screen.all { it.cols == 9 })
        assertEquals(2, buffer.history.size)
    }

    @Test
    fun scrollRegionOnlyMovesRowsInsideIt() {
        val buffer = TerminalBuffer(rows = 6, cols = 6, maxHistory = 10)
        for (i in 0 until 6) {
            buffer.cursorRow = i
            buffer.cursorCol = 0
            for (ch in "r$i") buffer.writeChar(ch)
        }
        buffer.scrollTop = 1
        buffer.scrollBottom = 3
        buffer.cursorRow = 3
        buffer.newLine() // at the bottom of the region: scrolls rows 1..3 only

        assertEquals(listOf("r0", "r2", "r3", "", "r4", "r5"), buffer.screen.map { it.getText() })
        assertEquals("a region scroll does not feed the scrollback", 0, buffer.history.size)

        buffer.scrollDown(1, 3)
        assertEquals(listOf("r0", "", "r2", "r3", "r4", "r5"), buffer.screen.map { it.getText() })
    }

    @Test
    fun scrolledRowsAreClearedWithTheCurrentColours() {
        val buffer = TerminalBuffer(rows = 3, cols = 4, maxHistory = 10)
        buffer.currentBg = 0x112233
        buffer.scrollUp(0, 2)
        assertTrue(buffer.screen[2].bgColors.all { it == 0x112233 })
        buffer.currentBg = 0x445566
        buffer.scrollDown(0, 2)
        assertTrue(buffer.screen[0].bgColors.all { it == 0x445566 })
    }

    @Test
    fun rowLookupsFollowScrollingOfTheAlternateScreen() {
        // the alternate screen has no history to bump the cache generation: scrolling it must still
        // invalidate the flattened view that search and selection use
        val buffer = TerminalBuffer(rows = 3, cols = 6, maxHistory = 10)
        buffer.useAlternateScreen(true)
        buffer.line("a", newlineBefore = false)
        buffer.line("b")
        buffer.line("c")
        assertEquals("a", buffer.rowForIndex(0)?.getText()) // fills the lookup cache
        assertEquals(3, buffer.allRowsWithIndex().size)

        buffer.line("d") // scrolls: "a" is gone
        assertEquals(listOf("b", "c", "d"), (0..2).map { buffer.rowForIndex(it)?.getText() })
        assertEquals(listOf("b", "c", "d"), buffer.allRowsWithIndex().map { it.second.getText() })
        assertEquals(0, buffer.history.size)
    }
}
