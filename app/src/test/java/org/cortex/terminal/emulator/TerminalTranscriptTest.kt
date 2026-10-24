package org.cortex.terminal.emulator

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalTranscriptTest {

    private fun TerminalEmulator.feed(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        processInput(bytes, 0, bytes.size)
    }

    @Test
    fun theLinesOfScrollbackAndScreenWithWrappedRowsJoined() {
        val e = TerminalEmulator(rows = 3, cols = 10)
        e.feed("one\r\ntwo\r\nabcdefghijKLMNO\r\nlast")
        assertEquals("one\ntwo\nabcdefghijKLMNO\nlast", e.buffer.transcript(100))
        assertEquals("only the newest lines", "abcdefghijKLMNO\nlast", e.buffer.transcript(2))
    }

    @Test
    fun emptyRowsBelowTheLastLineAreLeftOut() {
        val e = TerminalEmulator(rows = 5, cols = 10)
        e.feed("\$ ls\r\na  b\r\n")
        assertEquals("\$ ls\na  b", e.buffer.transcript(100))
    }

    @Test
    fun aFullScreenProgramLeavesTheShellsText() {
        val e = TerminalEmulator(rows = 3, cols = 10)
        e.feed("\$ vim\r\n\u001b[?1049h\u001b[2Jvim screen")
        assertEquals("\$ vim", e.buffer.transcript(100))
    }
}
