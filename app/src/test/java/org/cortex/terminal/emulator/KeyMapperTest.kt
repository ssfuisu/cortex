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

    private fun seq(keyCode: Int, ctrl: Boolean = false, alt: Boolean = false, shift: Boolean = false) =
        KeyMapper.getEscapeSequence(keyCode, isCtrl = ctrl, isAlt = alt, isShift = shift)?.let { String(it, Charsets.UTF_8) }

    @Test
    fun shiftWithCursorKeysAsXtermSendsThem() {
        // #47: the Shift of a hardware keyboard, alone and with Ctrl and Alt
        assertEquals("\u001b[1;2D", seq(android.view.KeyEvent.KEYCODE_DPAD_LEFT, shift = true))
        assertEquals("\u001b[1;2C", seq(android.view.KeyEvent.KEYCODE_DPAD_RIGHT, shift = true))
        assertEquals("\u001b[1;6D", seq(android.view.KeyEvent.KEYCODE_DPAD_LEFT, ctrl = true, shift = true))
        assertEquals("\u001b[1;4A", seq(android.view.KeyEvent.KEYCODE_DPAD_UP, alt = true, shift = true))
        assertEquals("\u001b[1;8B", seq(android.view.KeyEvent.KEYCODE_DPAD_DOWN, ctrl = true, alt = true, shift = true))
        assertEquals("\u001b[1;2H", seq(android.view.KeyEvent.KEYCODE_MOVE_HOME, shift = true))
        assertEquals("Shift+Tab is the back tab", "\u001b[Z", seq(android.view.KeyEvent.KEYCODE_TAB, shift = true))
        assertEquals("Shift does not change Enter", "\r", seq(android.view.KeyEvent.KEYCODE_ENTER, shift = true))
        assertEquals("without Shift, as before", "\u001b[D", seq(android.view.KeyEvent.KEYCODE_DPAD_LEFT))
    }

    @Test
    fun functionKeysAsXtermSendsThem() {
        assertEquals("\u001bOP", seq(android.view.KeyEvent.KEYCODE_F1))
        assertEquals("\u001bOS", seq(android.view.KeyEvent.KEYCODE_F4))
        assertEquals("\u001b[15~", seq(android.view.KeyEvent.KEYCODE_F5))
        assertEquals("\u001b[17~", seq(android.view.KeyEvent.KEYCODE_F6))
        assertEquals("\u001b[21~", seq(android.view.KeyEvent.KEYCODE_F10))
        assertEquals("\u001b[24~", seq(android.view.KeyEvent.KEYCODE_F12))
        assertEquals("Alt puts ESC in front", "\u001b\u001b[15~", seq(android.view.KeyEvent.KEYCODE_F5, alt = true))
    }

    @Test
    fun ctrlWithCursorAndEditingKeys() {
        assertEquals("\u001b[1;5D", seq(android.view.KeyEvent.KEYCODE_DPAD_LEFT, ctrl = true))
        assertEquals("\u001b[1;5C", seq(android.view.KeyEvent.KEYCODE_DPAD_RIGHT, ctrl = true))
        assertEquals("\u001b[1;7A", seq(android.view.KeyEvent.KEYCODE_DPAD_UP, ctrl = true, alt = true))
        assertEquals("\u001b[3;5~", seq(android.view.KeyEvent.KEYCODE_FORWARD_DEL, ctrl = true))
        assertEquals("\u001b[1;5P", seq(android.view.KeyEvent.KEYCODE_F1, ctrl = true))
        assertEquals("\u001b[24;5~", seq(android.view.KeyEvent.KEYCODE_F12, ctrl = true))
        assertEquals("keys without a modified form stay", "\t", seq(android.view.KeyEvent.KEYCODE_TAB, ctrl = true))
    }
}
