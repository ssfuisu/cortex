package org.cortex.terminal.emulator

import android.view.KeyEvent

object KeyMapper {
    fun getEscapeSequence(keyCode: Int, isCtrl: Boolean, isAlt: Boolean, isAppCursor: Boolean = false): ByteArray? {
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
