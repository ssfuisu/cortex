package org.cortex.terminal.emulator

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalEmulatorUtf8Test {

    @Test
    fun testMultiByteSplitAcrossPackets() {
        val emulator = TerminalEmulator(rows = 5, cols = 20)
        
        // "ö" in UTF-8 is 0xC3, 0xB6
        val utf8Bytes = "ö".toByteArray(Charsets.UTF_8)
        assertEquals(2, utf8Bytes.size)

        // Feed first byte only
        emulator.processInput(byteArrayOf(utf8Bytes[0]), 0, 1)

        // Feed second byte
        emulator.processInput(byteArrayOf(utf8Bytes[1]), 0, 1)

        // Verify emulator correctly combined and rendered 'ö'
        val firstChar = emulator.buffer.screen[0].chars[0]
        assertEquals('ö', firstChar)
    }

    @Test
    fun testAsciiStringProcessing() {
        val emulator = TerminalEmulator(rows = 5, cols = 20)
        val text = "Cortex".toByteArray(Charsets.UTF_8)
        emulator.processInput(text, 0, text.size)
        assertEquals("Cortex", emulator.buffer.screen[0].getText())
    }
}
