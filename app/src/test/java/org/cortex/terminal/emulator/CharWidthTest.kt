package org.cortex.terminal.emulator

import org.junit.Assert.assertEquals
import org.junit.Test

/** How many cells a character takes: what the C library's wcwidth() answers, which programs count with. */
class CharWidthTest {

    @Test
    fun ordinaryTextIsOneCell() {
        for (c in "aZ09 ~é€ß±¿ñ") assertEquals("$c", 1, CharWidth.of(c))
        // Box drawing, blocks, arrows, powerline: what full-screen programs draw with
        for (cp in intArrayOf(0x2500, 0x2502, 0x250C, 0x2588, 0x2192, 0xE0B0, 0x03A9)) assertEquals("U+${cp.toString(16)}", 1, CharWidth.of(cp))
    }

    @Test
    fun chineseJapaneseAndKoreanAreTwoCells() {
        for (c in "中文你好世界") assertEquals("$c", 2, CharWidth.of(c))
        for (c in "ひらがなカタカナ") assertEquals("$c", 2, CharWidth.of(c))
        for (c in "한국어") assertEquals("$c", 2, CharWidth.of(c))
        // Punctuation of the CJK block and the fullwidth forms
        for (c in "，。、！？（）　Ａ１") assertEquals("U+${c.code.toString(16)}", 2, CharWidth.of(c))
        assertEquals("a CJK ideograph of the supplementary planes", 2, CharWidth.of(0x20000))
    }

    @Test
    fun halfwidthFormsAreOneCell() {
        assertEquals(1, CharWidth.of('ｱ'))
        assertEquals(1, CharWidth.of('ﾊ'))
        assertEquals(1, CharWidth.of('ｦ'))
        assertEquals("the fullwidth won sign is a wide one", 2, CharWidth.of('\uFFE6'))
    }

    @Test
    fun emojiAreTwoCells() {
        assertEquals(2, CharWidth.of(0x1F600))
        assertEquals(2, CharWidth.of(0x1F680))
        assertEquals(2, CharWidth.of(0x1F9E1))
        assertEquals("a check mark button", 2, CharWidth.of('✅'))
        assertEquals("a star", 2, CharWidth.of('⭐'))
        assertEquals("a heart without its emoji presentation is a text symbol", 1, CharWidth.of('❤'))
    }

    @Test
    fun invisibleCharactersTakeNoCell() {
        for (cp in intArrayOf(0x200B, 0x200C, 0x200D, 0x200E, 0xFE0F, 0xFEFF, 0x2060)) {
            assertEquals("U+${cp.toString(16)}", 0, CharWidth.of(cp))
        }
    }

    @Test
    fun theTablesAreTheOnesTheScriptMakes() {
        // tests/scripts/gen_char_width.py --check compares them with Unicode's; here, a spot check of
        // the edges of ranges, where an off-by-one would be
        assertEquals(1, CharWidth.of(0x10FF))
        assertEquals(2, CharWidth.of(0x1100))
        assertEquals(2, CharWidth.of(0x115F))
        assertEquals(1, CharWidth.of(0x1160))
        assertEquals(2, CharWidth.of(0x4E00))
        assertEquals(2, CharWidth.of(0x9FFF))
        assertEquals(2, CharWidth.of(0xAC00))
        assertEquals(2, CharWidth.of(0xD7A3))
        assertEquals(1, CharWidth.of(0xD7A4))
        assertEquals(2, CharWidth.of(0xFF01))
        assertEquals(2, CharWidth.of(0xFF60))
        assertEquals(1, CharWidth.of(0xFF61))
    }
}
