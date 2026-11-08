package org.cortex.terminal.desktop

/**
 * X keysyms for the keys and characters of Android: what the desktop sends to the X server. Android's
 * key codes are written out here as numbers (KeyEvent.KEYCODE_*), so that this is plain Kotlin and
 * tested on a computer.
 */
object XKeySyms {
    const val BACKSPACE = 0xff08
    const val TAB = 0xff09
    const val RETURN = 0xff0d
    const val ESCAPE = 0xff1b
    const val DELETE = 0xffff
    const val HOME = 0xff50
    const val LEFT = 0xff51
    const val UP = 0xff52
    const val RIGHT = 0xff53
    const val DOWN = 0xff54
    const val PAGE_UP = 0xff55
    const val PAGE_DOWN = 0xff56
    const val END = 0xff57
    const val INSERT = 0xff63
    const val MENU = 0xff67
    const val F1 = 0xffbe
    const val SHIFT_L = 0xffe1
    const val SHIFT_R = 0xffe2
    const val CONTROL_L = 0xffe3
    const val CONTROL_R = 0xffe4
    const val CAPS_LOCK = 0xffe5
    const val ALT_L = 0xffe9
    const val ALT_R = 0xffea
    const val SUPER_L = 0xffeb
    const val SUPER_R = 0xffec

    /** Android key code to keysym, for the keys that are not characters; 0 for the others. */
    fun forKeyCode(keyCode: Int): Int = when (keyCode) {
        67 -> BACKSPACE // KEYCODE_DEL
        61 -> TAB
        66, 160 -> RETURN // KEYCODE_ENTER, KEYCODE_NUMPAD_ENTER
        111 -> ESCAPE
        112 -> DELETE // KEYCODE_FORWARD_DEL
        122 -> HOME // KEYCODE_MOVE_HOME
        123 -> END // KEYCODE_MOVE_END
        21 -> LEFT
        19 -> UP
        22 -> RIGHT
        20 -> DOWN
        92 -> PAGE_UP
        93 -> PAGE_DOWN
        124 -> INSERT
        82 -> MENU
        in 131..142 -> F1 + (keyCode - 131) // KEYCODE_F1..F12
        59 -> SHIFT_L
        60 -> SHIFT_R
        113 -> CONTROL_L
        114 -> CONTROL_R
        115 -> CAPS_LOCK
        57 -> ALT_L
        58 -> ALT_R
        117 -> SUPER_L // KEYCODE_META_LEFT
        118 -> SUPER_R
        62 -> 0x20 // KEYCODE_SPACE
        else -> 0
    }

    /**
     * The keysym of a character: Latin-1 ones are their own code, everything else Unicode's
     * (0x01000000 + code point), which TigerVNC's Xvnc maps onto a free key code by itself.
     */
    fun forCodePoint(codePoint: Int): Int = when {
        codePoint == '\n'.code || codePoint == '\r'.code -> RETURN
        codePoint == '\t'.code -> TAB
        codePoint == 0x08 -> BACKSPACE
        codePoint == 0x1b -> ESCAPE
        codePoint in 0x20..0x7e || codePoint in 0xa0..0xff -> codePoint
        codePoint < 0x20 || codePoint == 0x7f -> 0
        else -> 0x01000000 or codePoint
    }

    /** The keysyms of a text, one per character (code points outside the BMP as one). */
    fun forText(text: CharSequence): IntArray {
        val result = ArrayList<Int>(text.length)
        var i = 0
        while (i < text.length) {
            val cp = Character.codePointAt(text, i)
            val sym = forCodePoint(cp)
            if (sym != 0) result.add(sym)
            i += Character.charCount(cp)
        }
        return result.toIntArray()
    }
}
