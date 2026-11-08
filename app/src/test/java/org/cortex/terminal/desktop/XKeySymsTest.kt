package org.cortex.terminal.desktop

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class XKeySymsTest {
    @Test
    fun keysWithoutCharacters() {
        assertEquals(XKeySyms.RETURN, XKeySyms.forKeyCode(66))
        assertEquals(XKeySyms.BACKSPACE, XKeySyms.forKeyCode(67))
        assertEquals(XKeySyms.ESCAPE, XKeySyms.forKeyCode(111))
        assertEquals(XKeySyms.LEFT, XKeySyms.forKeyCode(21))
        assertEquals(XKeySyms.F1, XKeySyms.forKeyCode(131))
        assertEquals(XKeySyms.F1 + 11, XKeySyms.forKeyCode(142))
        assertEquals(XKeySyms.CONTROL_L, XKeySyms.forKeyCode(113))
        assertEquals(0, XKeySyms.forKeyCode(29)) // KEYCODE_A is a character
    }

    @Test
    fun charactersAreLatin1OrUnicodeKeysyms() {
        assertEquals('a'.code, XKeySyms.forCodePoint('a'.code))
        assertEquals(0xe9, XKeySyms.forCodePoint('é'.code))
        assertEquals(0x0100015f, XKeySyms.forCodePoint('ş'.code))
        assertEquals(0x01000131, XKeySyms.forCodePoint('ı'.code))
        assertEquals(XKeySyms.RETURN, XKeySyms.forCodePoint('\n'.code))
        assertEquals(0, XKeySyms.forCodePoint(0x01))
        assertEquals(0x0101f600, XKeySyms.forCodePoint(0x1f600))
    }

    @Test
    fun textIsOneKeysymPerCharacter() {
        assertArrayEquals(intArrayOf('h'.code, 0x0100011f, XKeySyms.RETURN, 0x0101f600), XKeySyms.forText("hğ\n😀"))
    }
}
