package org.cortex.terminal.emulator

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalEmulatorTest {

    @Test
    fun testLargeInputExceeding16KBIsFullyConsumedWithoutDroppingBytes() {
        val emulator = TerminalEmulator(rows = 5, cols = 80)
        // 20,000 newlines/lines followed by a distinct marker on the last line
        val prefix = "A\r\n".repeat(7000) // 21,000 bytes (> 16,384 byte buffer)
        val marker = "FINAL_MARKER_OK"
        val payload = (prefix + marker).toByteArray(Charsets.UTF_8)

        emulator.processInput(payload, 0, payload.size)

        val currentRowText = emulator.buffer.screen[emulator.buffer.cursorRow].getText()
        assertEquals(marker, currentRowText)
    }

    @Test
    fun testSplitMultibyteUtf8FollowedByLargeChunkDoesNotCorruptBuffer() {
        val emulator = TerminalEmulator(rows = 5, cols = 80)
        // 'é' in UTF-8 is 0xC3 0xA9
        val firstByte = byteArrayOf(0xC3.toByte())
        emulator.processInput(firstByte, 0, 1)

        // Second chunk starts with 0xA9 to complete 'é', then 18,000 bytes (> 16 KB) across 250 lines (< 2000 maxHistory), ending with "TAIL_OK"
        val line70 = "B".repeat(70)
        val middle = "$line70\r\n".repeat(250).toByteArray(Charsets.UTF_8)
        val tail = "TAIL_OK".toByteArray(Charsets.UTF_8)
        val secondChunk = ByteArray(1 + middle.size + tail.size)
        secondChunk[0] = 0xA9.toByte()
        System.arraycopy(middle, 0, secondChunk, 1, middle.size)
        System.arraycopy(tail, 0, secondChunk, 1 + middle.size, tail.size)

        emulator.processInput(secondChunk, 0, secondChunk.size)

        val firstHistoryText = emulator.buffer.history.first.getText()
        assertEquals("é$line70", firstHistoryText)
        val lastRowText = emulator.buffer.screen[emulator.buffer.cursorRow].getText()
        assertEquals("TAIL_OK", lastRowText)
    }
}
