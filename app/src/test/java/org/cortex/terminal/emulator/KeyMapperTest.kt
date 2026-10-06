package org.cortex.terminal.emulator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class KeyMapperTest {

    @Test
    fun testArrowKeysStandard() {
        val up = KeyMapper.getEscapeSequence(android.view.KeyEvent.KEYCODE_DPAD_UP, isCtrl = false, isAlt = false, isAppCursor = false)
        assertNotNull(up)
        assertEquals("\u001b[A", String(up!!, Charsets.UTF_8))

        val down = KeyMapper.getEscapeSequence(android.view.KeyEvent.KEYCODE_DPAD_DOWN, isCtrl = false, isAlt = false, isAppCursor = false)
        assertNotNull(down)
        assertEquals("\u001b[B", String(down!!, Charsets.UTF_8))
    }

    @Test
    fun testArrowKeysApplicationCursorMode() {
        val up = KeyMapper.getEscapeSequence(android.view.KeyEvent.KEYCODE_DPAD_UP, isCtrl = false, isAlt = false, isAppCursor = true)
        assertNotNull(up)
        assertEquals("\u001bOA", String(up!!, Charsets.UTF_8))
    }

    @Test
    fun testCtrlCharEncoding() {
        // Ctrl+C -> 0x03
        val ctrlC = KeyMapper.getCharBytes('c', isCtrl = true, isAlt = false)
        assertEquals(1, ctrlC.size)
        assertEquals(3, ctrlC[0].toInt())

        // Ctrl+D -> 0x04
        val ctrlD = KeyMapper.getCharBytes('d', isCtrl = true, isAlt = false)
        assertEquals(1, ctrlD.size)
        assertEquals(4, ctrlD[0].toInt())

        // Ctrl+Z -> 0x1A (26)
        val ctrlZ = KeyMapper.getCharBytes('z', isCtrl = true, isAlt = false)
        assertEquals(1, ctrlZ.size)
        assertEquals(26, ctrlZ[0].toInt())
    }
}
