package org.cortex.terminal.emulator

import android.view.KeyEvent

object KeyMapper {
    fun getEscapeSequence(keyCode: Int, isCtrl: Boolean, isAlt: Boolean, isAppCursor: Boolean = false): ByteArray? {
        // With Ctrl, xterm's form with a modifier (Ctrl+Left is CSI 1;5D, which readline takes as a word)
        if (isCtrl) modifiedSequence(keyCode, if (isAlt) 7 else 5)?.let { return it.toByteArray(Charsets.UTF_8) }
        val baseSeq = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> if (isAppCursor) "\u001bOA" else "\u001b[A"
            KeyEvent.KEYCODE_DPAD_DOWN -> if (isAppCursor) "\u001bOB" else "\u001b[B"
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (isAppCursor) "\u001bOC" else "\u001b[C"
            KeyEvent.KEYCODE_DPAD_LEFT -> if (isAppCursor) "\u001bOD" else "\u001b[D"
            KeyEvent.KEYCODE_MOVE_HOME -> if (isAppCursor) "\u001bOH" else "\u001b[H"
            KeyEvent.KEYCODE_MOVE_END -> if (isAppCursor) "\u001bOF" else "\u001b[F"
            KeyEvent.KEYCODE_PAGE_UP -> "\u001b[5~"
            KeyEvent.KEYCODE_PAGE_DOWN -> "\u001b[6~"
            KeyEvent.KEYCODE_FORWARD_DEL -> "\u001b[3~"
            KeyEvent.KEYCODE_INSERT -> "\u001b[2~"
            KeyEvent.KEYCODE_TAB -> "\t"
            KeyEvent.KEYCODE_ESCAPE -> "\u001b"
            KeyEvent.KEYCODE_ENTER -> "\r"
            KeyEvent.KEYCODE_DEL -> "\u007f"
            KeyEvent.KEYCODE_F1 -> "\u001bOP"
            KeyEvent.KEYCODE_F2 -> "\u001bOQ"
            KeyEvent.KEYCODE_F3 -> "\u001bOR"
            KeyEvent.KEYCODE_F4 -> "\u001bOS"
            in KeyEvent.KEYCODE_F5..KeyEvent.KEYCODE_F12 -> "\u001b[${functionKeyNumber(keyCode)}~"
            else -> null
        }

        if (baseSeq != null) {
            return if (isAlt) {
                ("\u001b" + baseSeq).toByteArray(Charsets.UTF_8)
            } else {
                baseSeq.toByteArray(Charsets.UTF_8)
            }
        }
        return null
    }

    // The numbers of F5 to F12 in their sequences (xterm leaves out 16 and 22)
    private fun functionKeyNumber(keyCode: Int): Int =
        intArrayOf(15, 17, 18, 19, 20, 21, 23, 24)[keyCode - KeyEvent.KEYCODE_F5]

    private fun modifiedSequence(keyCode: Int, modifier: Int): String? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> "\u001b[1;${modifier}A"
        KeyEvent.KEYCODE_DPAD_DOWN -> "\u001b[1;${modifier}B"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "\u001b[1;${modifier}C"
        KeyEvent.KEYCODE_DPAD_LEFT -> "\u001b[1;${modifier}D"
        KeyEvent.KEYCODE_MOVE_HOME -> "\u001b[1;${modifier}H"
        KeyEvent.KEYCODE_MOVE_END -> "\u001b[1;${modifier}F"
        KeyEvent.KEYCODE_INSERT -> "\u001b[2;$modifier~"
        KeyEvent.KEYCODE_FORWARD_DEL -> "\u001b[3;$modifier~"
        KeyEvent.KEYCODE_PAGE_UP -> "\u001b[5;$modifier~"
        KeyEvent.KEYCODE_PAGE_DOWN -> "\u001b[6;$modifier~"
        KeyEvent.KEYCODE_F1 -> "\u001b[1;${modifier}P"
        KeyEvent.KEYCODE_F2 -> "\u001b[1;${modifier}Q"
        KeyEvent.KEYCODE_F3 -> "\u001b[1;${modifier}R"
        KeyEvent.KEYCODE_F4 -> "\u001b[1;${modifier}S"
        in KeyEvent.KEYCODE_F5..KeyEvent.KEYCODE_F12 -> "\u001b[${functionKeyNumber(keyCode)};$modifier~"
        else -> null
    }

    fun getCharBytes(c: Char, isCtrl: Boolean, isAlt: Boolean): ByteArray {
        var charCode = c.code
        if (isCtrl) {
            charCode = if (charCode in 0x61..0x7a) {
                charCode - 0x60
            } else if (charCode in 0x41..0x5a) {
                charCode - 0x40
            } else if (c == '@' || c == '2' || c == ' ') {
                0
            } else if (c == '[' || c == '3') {
                27
            } else if (c == '\\' || c == '4') {
                28
            } else if (c == ']' || c == '5') {
                29
            } else if (c == '^' || c == '6') {
                30
            } else if (c == '_' || c == '7') {
                31
            } else {
                charCode
            }
        }

        val resultStr = if (isAlt) {
            "\u001b" + charCode.toChar()
        } else {
            charCode.toChar().toString()
        }
        return resultStr.toByteArray(Charsets.UTF_8)
    }
}
