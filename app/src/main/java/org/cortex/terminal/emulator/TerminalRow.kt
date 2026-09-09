package org.cortex.terminal.emulator

class TerminalRow(val cols: Int) {
    val chars = CharArray(cols) { ' ' }
    val fgColors = IntArray(cols) { TerminalColor.DEFAULT_FG }
    val bgColors = IntArray(cols) { TerminalColor.DEFAULT_BG }
    val styles = ByteArray(cols) { 0 } // bit 0: bold, bit 1: underline, bit 2: inverse
    var isWrapped: Boolean = false

    fun clear(fg: Int = TerminalColor.DEFAULT_FG, bg: Int = TerminalColor.DEFAULT_BG) {
        for (i in 0 until cols) {
            chars[i] = ' '
            fgColors[i] = fg
            bgColors[i] = bg
            styles[i] = 0
        }
        isWrapped = false
    }

    fun setChar(col: Int, char: Char, fg: Int, bg: Int, style: Byte = 0) {
        if (col in 0 until cols) {
            chars[col] = char
            fgColors[col] = fg
            bgColors[col] = bg
            styles[col] = style
        }
    }

    fun copyFrom(other: TerminalRow) {
        val count = minOf(cols, other.cols)
        System.arraycopy(other.chars, 0, chars, 0, count)
        System.arraycopy(other.fgColors, 0, fgColors, 0, count)
        System.arraycopy(other.bgColors, 0, bgColors, 0, count)
        System.arraycopy(other.styles, 0, styles, 0, count)
        isWrapped = other.isWrapped
    }

    fun deleteChars(startCol: Int, count: Int, fg: Int = TerminalColor.DEFAULT_FG, bg: Int = TerminalColor.DEFAULT_BG) {
        if (startCol !in 0 until cols || count <= 0) return
        val clampedCount = minOf(count, cols - startCol)
        val shiftCount = cols - startCol - clampedCount
        if (shiftCount > 0) {
            System.arraycopy(chars, startCol + clampedCount, chars, startCol, shiftCount)
            System.arraycopy(fgColors, startCol + clampedCount, fgColors, startCol, shiftCount)
            System.arraycopy(bgColors, startCol + clampedCount, bgColors, startCol, shiftCount)
            System.arraycopy(styles, startCol + clampedCount, styles, startCol, shiftCount)
        }
        val blankStart = cols - clampedCount
        for (i in blankStart until cols) {
            chars[i] = ' '
            fgColors[i] = fg
            bgColors[i] = bg
            styles[i] = 0
        }
    }

    fun insertChars(startCol: Int, count: Int, fg: Int = TerminalColor.DEFAULT_FG, bg: Int = TerminalColor.DEFAULT_BG) {
        if (startCol !in 0 until cols || count <= 0) return
        val clampedCount = minOf(count, cols - startCol)
        val shiftCount = cols - startCol - clampedCount
        if (shiftCount > 0) {
            System.arraycopy(chars, startCol, chars, startCol + clampedCount, shiftCount)
            System.arraycopy(fgColors, startCol, fgColors, startCol + clampedCount, shiftCount)
            System.arraycopy(bgColors, startCol, bgColors, startCol + clampedCount, shiftCount)
            System.arraycopy(styles, startCol, styles, startCol + clampedCount, shiftCount)
        }
        for (i in startCol until minOf(cols, startCol + clampedCount)) {
            chars[i] = ' '
            fgColors[i] = fg
            bgColors[i] = bg
            styles[i] = 0
        }
    }

    fun getText(): String {
        var lastNonSpace = cols - 1
        while (lastNonSpace >= 0 && chars[lastNonSpace] == ' ') {
            lastNonSpace--
        }
        return if (lastNonSpace < 0) "" else String(chars, 0, lastNonSpace + 1)
    }
}
