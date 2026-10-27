package org.cortex.terminal.emulator

class TerminalRow(val cols: Int) {
    companion object {
        /**
         * The second cell of a wide character (Chinese, Korean, Japanese, fullwidth forms), which has
         * no character of its own: the first cell holds the character and is drawn over both. The cells
         * of an emoji (a pair of surrogates in UTF-16) hold the two halves instead.
         */
        const val WIDE_TAIL = '\u0000'
    }

    val chars = CharArray(cols) { ' ' }
    val fgColors = IntArray(cols) { TerminalColor.DEFAULT_FG }
    val bgColors = IntArray(cols) { TerminalColor.DEFAULT_BG }
    val styles = ByteArray(cols) { 0 } // bit 0: bold, bit 1: underline, bit 2: inverse
    val isSearchMatch = BooleanArray(cols) { false }
    val isSearchCurrent = BooleanArray(cols) { false }
    val isUrlLink = BooleanArray(cols) { false }
    var isWrapped: Boolean = false

    /** Whether the row has (or had) a wide character: only then the rest of the code looks for them. */
    var hasWide: Boolean = false
        private set

    /** Whether the cell at [col] is the second cell of a wide character. */
    fun isTail(col: Int): Boolean {
        if (col <= 0 || col >= cols) return false
        val ch = chars[col]
        return if (ch == WIDE_TAIL) {
            isWideHead(col - 1)
        } else {
            Character.isLowSurrogate(ch) && Character.isHighSurrogate(chars[col - 1])
        }
    }

    /**
     * Whether the cell at [col] is the first of the two cells of a wide character: a wide character
     * followed by [WIDE_TAIL], or the first half of an emoji followed by the second. (A character of
     * one cell is not, whatever follows it: a half left behind when cells moved is not a character.)
     */
    fun isWideHead(col: Int): Boolean {
        if (col < 0 || col + 1 >= cols) return false
        val ch = chars[col]
        val next = chars[col + 1]
        return if (next == WIDE_TAIL) {
            ch.code >= CharWidth.FIRST_NOT_NARROW && !Character.isSurrogate(ch) && CharWidth.of(ch) == 2
        } else {
            Character.isHighSurrogate(ch) && Character.isLowSurrogate(next)
        }
    }

    /**
     * Before the cells from [from] until [to] are overwritten: a wide character that they cut in two
     * leaves its other half, which is blanked (a half of a wide character shows as a stray glyph).
     */
    private fun breakWideAround(from: Int, to: Int) {
        if (from > 0 && isTail(from)) chars[from - 1] = ' '
        if (to < cols && isTail(to)) chars[to] = ' '
    }

    /** Blanks the halves of wide characters that are left without the other half (after cells moved). */
    private fun removeBrokenWide() {
        var i = 0
        while (i < cols) {
            val ch = chars[i]
            if (isWideHead(i)) {
                i += 2
                continue
            }
            if (ch == WIDE_TAIL || Character.isSurrogate(ch) ||
                (ch.code >= CharWidth.FIRST_NOT_NARROW && CharWidth.of(ch) == 2)
            ) {
                chars[i] = ' '
            }
            i++
        }
    }

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

    /** The colors of the cells under a new scheme (see [TerminalColor.Remap]). */
    fun remapColors(remap: TerminalColor.Remap) {
        if (cols == 0) return
        // Cells next to each other mostly have the same colors
        var lastFrom = fgColors[0]
        var lastTo = remap.mapFg(lastFrom)
        for (i in fgColors.indices) {
            val c = fgColors[i]
            if (c != lastFrom) {
                lastFrom = c
                lastTo = remap.mapFg(c)
            }
            fgColors[i] = lastTo
        }
        lastFrom = bgColors[0]
        lastTo = remap.mapBg(lastFrom)
        for (i in bgColors.indices) {
            val c = bgColors[i]
            if (c != lastFrom) {
                lastFrom = c
                lastTo = remap.mapBg(c)
            }
            bgColors[i] = lastTo
        }
    }

    fun clear(fg: Int = TerminalColor.DEFAULT_FG, bg: Int = TerminalColor.DEFAULT_BG) {
        // One pass per array: simple fills compile to wide stores, a single loop writing seven
        // arrays does not. Every scrolled line clears a row.
        chars.fill(' ')
        fgColors.fill(fg)
        bgColors.fill(bg)
        styles.fill(0)
        isSearchMatch.fill(false)
        isSearchCurrent.fill(false)
        isUrlLink.fill(false)
        hasUrlFlags = false
        links = null
        isWrapped = false
        hasWide = false
    }

    fun setChar(col: Int, char: Char, fg: Int, bg: Int, style: Byte = 0, link: String? = null) {
        if (col in 0 until cols) {
            if (hasWide) breakWideAround(col, col + 1)
            if (char == WIDE_TAIL || Character.isSurrogate(char)) hasWide = true
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

    /**
     * A wide character into the cells [col] and the next: [head] in the first and [tail] ([WIDE_TAIL], or
     * the second surrogate of an emoji) in the second, with the same colors and style.
     */
    fun setWide(col: Int, head: Char, tail: Char, fg: Int, bg: Int, style: Byte, link: String?) {
        if (col < 0 || col + 1 >= cols) return
        if (hasWide) breakWideAround(col, col + 2)
        hasWide = true
        chars[col] = head
        chars[col + 1] = tail
        for (i in col..col + 1) {
            fgColors[i] = fg
            bgColors[i] = bg
            styles[i] = style
            isSearchMatch[i] = false
            isSearchCurrent[i] = false
            isUrlLink[i] = false
            setLink(i, link)
        }
    }

    /** [n] characters of [src] from [srcPos] into the cells from [col] on, as [setChar] does for each. */
    fun setChars(col: Int, src: CharArray, srcPos: Int, n: Int, fg: Int, bg: Int, style: Byte, link: String?) {
        val from = col.coerceAtLeast(0)
        val to = minOf(col + n, cols)
        if (from >= to) return
        if (hasWide) breakWideAround(from, to)
        System.arraycopy(src, srcPos + (from - col), chars, from, to - from)
        fgColors.fill(fg, from, to)
        bgColors.fill(bg, from, to)
        styles.fill(style, from, to)
        isSearchMatch.fill(false, from, to)
        isSearchCurrent.fill(false, from, to)
        isUrlLink.fill(false, from, to)
        if (link != null || links != null) {
            for (c in from until to) setLink(c, link)
        }
    }

    fun clearSearchFlags() {
        for (i in 0 until cols) {
            isSearchMatch[i] = false
            isSearchCurrent[i] = false
        }
    }

    /** Set by whoever marks link cells, so that clearing the rows without any costs nothing. */
    var hasUrlFlags: Boolean = false

    fun clearUrlFlags() {
        if (!hasUrlFlags) return
        isUrlLink.fill(false)
        hasUrlFlags = false
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
        hasUrlFlags = other.hasUrlFlags
        hasWide = other.hasWide
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
        if (hasWide) removeBrokenWide()
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
        if (hasWide) removeBrokenWide()
    }

    /** Appends the characters of the cells from [from] until [to] to [sb]: a wide character once. */
    fun appendText(sb: StringBuilder, from: Int, to: Int) {
        val start = from.coerceAtLeast(0)
        val end = to.coerceAtMost(cols)
        if (start >= end) return
        if (!hasWide) {
            sb.append(chars, start, end - start)
            return
        }
        for (i in start until end) {
            if (chars[i] != WIDE_TAIL) sb.append(chars[i])
        }
    }

    fun getText(): String {
        var lastNonSpace = cols - 1
        while (lastNonSpace >= 0 && chars[lastNonSpace] == ' ') {
            lastNonSpace--
        }
        if (lastNonSpace < 0) return ""
        if (!hasWide) return String(chars, 0, lastNonSpace + 1)
        val sb = StringBuilder(lastNonSpace + 1)
        appendText(sb, 0, lastNonSpace + 1)
        return sb.toString()
    }

    /**
     * The column of each character of [getText]: they are not the same once a wide character takes two
     * cells and one character. Null while the row has none, when the character at i is in column i.
     */
    fun textColumns(): IntArray? {
        if (!hasWide) return null
        var lastNonSpace = cols - 1
        while (lastNonSpace >= 0 && chars[lastNonSpace] == ' ') lastNonSpace--
        val out = IntArray(lastNonSpace + 1)
        var n = 0
        for (i in 0..lastNonSpace) {
            if (chars[i] != WIDE_TAIL) out[n++] = i
        }
        return out.copyOf(n)
    }
}
