package org.cortex.terminal.emulator

import org.junit.Assert.assertEquals
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
}
