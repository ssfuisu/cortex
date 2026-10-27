package org.cortex.terminal.view

/**
 * The block sextants (U+1FB00..U+1FB3B): a cell cut into two columns and three rows, with any of the six
 * parts filled. Full-screen programs draw big letters and pictures with them (the logo of Kilo), but few
 * fonts have them, and a character the fonts lack is a box with a cross. The terminal draws them itself,
 * from rectangles, as it is exact that way: the parts of neighbouring cells meet without a gap.
 */
internal object Sextants {

    const val FIRST = 0x1FB00
    const val LAST = 0x1FB3B

    fun isSextant(cp: Int): Boolean = cp in FIRST..LAST

    /**
     * The parts of the sextant [cp] that are filled, as bits: 1 upper left, 2 upper right, 4 middle left,
     * 8 middle right, 16 lower left, 32 lower right. The characters go through the 62 patterns in this
     * order, but not through the left half (21) and the right half (42), which have characters of their
     * own in the block elements.
     */
    fun mask(cp: Int): Int {
        var m = cp - FIRST + 1
        if (m >= 21) m++
        if (m >= 42) m++
        return m
    }
}
