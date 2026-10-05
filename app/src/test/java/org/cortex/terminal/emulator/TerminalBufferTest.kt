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
}
