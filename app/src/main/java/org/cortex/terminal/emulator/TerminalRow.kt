package org.cortex.terminal.emulator

class TerminalRow(val cols: Int) {
    val chars = CharArray(cols) { ' ' }
    val fgColors = IntArray(cols) { TerminalColor.DEFAULT_FG }
    val bgColors = IntArray(cols) { TerminalColor.DEFAULT_BG }
    val styles = ByteArray(cols) { 0 } // bit 0: bold, bit 1: underline, bit 2: inverse
    val isSearchMatch = BooleanArray(cols) { false }
    val isSearchCurrent = BooleanArray(cols) { false }
    val isUrlLink = BooleanArray(cols) { false }
    var isWrapped: Boolean = false

    /**
     * Per-cell OSC 8 hyperlink targets. Allocated lazily: rows that never carried a link (almost
     * all of them) pay for a single null reference. Cells of one link share one String instance.
     */
    var links: Array<String?>? = null
        private set

    fun linkAt(col: Int): String? {
        val l = links ?: return null
        return if (col in l.indices) l[col] else null
    }

    fun hasLinkAt(col: Int): Boolean = linkAt(col) != null

    private fun setLink(col: Int, link: String?) {
        var l = links
        if (l == null) {
            if (link == null) return
            l = arrayOfNulls(cols)
            links = l
        }
        if (col in l.indices) l[col] = link
    }

    fun clear(fg: Int = TerminalColor.DEFAULT_FG, bg: Int = TerminalColor.DEFAULT_BG) {
        for (i in 0 until cols) {
            chars[i] = ' '
            fgColors[i] = fg
            bgColors[i] = bg
            styles[i] = 0
            isSearchMatch[i] = false
            isSearchCurrent[i] = false
            isUrlLink[i] = false
        }
        links = null
        isWrapped = false
    }

    fun setChar(col: Int, char: Char, fg: Int, bg: Int, style: Byte = 0, link: String? = null) {
        if (col in 0 until cols) {
            chars[col] = char
            fgColors[col] = fg
            bgColors[col] = bg
            styles[col] = style
            isSearchMatch[col] = false
            isSearchCurrent[col] = false
            isUrlLink[col] = false
            setLink(col, link)
        }
    }

    fun clearSearchFlags() {
        for (i in 0 until cols) {
            isSearchMatch[i] = false
            isSearchCurrent[i] = false
        }
    }

    fun clearUrlFlags() {
        for (i in 0 until cols) {
            isUrlLink[i] = false
        }
    }

    fun copyFrom(other: TerminalRow) {
        val count = minOf(cols, other.cols)
        System.arraycopy(other.chars, 0, chars, 0, count)
        System.arraycopy(other.fgColors, 0, fgColors, 0, count)
        System.arraycopy(other.bgColors, 0, bgColors, 0, count)
        System.arraycopy(other.styles, 0, styles, 0, count)
        val mCount = minOf(cols, other.isSearchMatch.size)
        System.arraycopy(other.isSearchMatch, 0, isSearchMatch, 0, mCount)
        val cCount = minOf(cols, other.isSearchCurrent.size)
        System.arraycopy(other.isSearchCurrent, 0, isSearchCurrent, 0, cCount)
        val uCount = minOf(cols, other.isUrlLink.size)
        System.arraycopy(other.isUrlLink, 0, isUrlLink, 0, uCount)
        val otherLinks = other.links
        if (otherLinks == null) {
            links = null
        } else {
            val copy = arrayOfNulls<String>(cols)
            System.arraycopy(otherLinks, 0, copy, 0, minOf(cols, otherLinks.size))
            links = copy
        }
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
            links?.let { System.arraycopy(it, startCol + clampedCount, it, startCol, shiftCount) }
        }
        val blankStart = cols - clampedCount
        for (i in blankStart until cols) {
            chars[i] = ' '
            fgColors[i] = fg
            bgColors[i] = bg
            styles[i] = 0
            links?.let { l -> if (i in l.indices) l[i] = null }
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
            links?.let { System.arraycopy(it, startCol, it, startCol + clampedCount, shiftCount) }
        }
        for (i in startCol until minOf(cols, startCol + clampedCount)) {
            chars[i] = ' '
            fgColors[i] = fg
            bgColors[i] = bg
            styles[i] = 0
            links?.let { l -> if (i in l.indices) l[i] = null }
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
