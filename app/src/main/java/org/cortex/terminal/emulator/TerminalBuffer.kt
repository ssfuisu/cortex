package org.cortex.terminal.emulator

import java.util.ArrayDeque

class TerminalBuffer(var rows: Int, var cols: Int, private val maxHistory: Int = 5000) {
    val history = ArrayDeque<TerminalRow>()
    private var historyArrayCache: Array<TerminalRow>? = null
    private var historyArrayCacheSize = -1

    private fun getHistoryRow(index: Int): TerminalRow? {
        if (index !in 0 until history.size) return null
        var cache = historyArrayCache
        if (cache == null || historyArrayCacheSize != history.size) {
            cache = history.toTypedArray()
            historyArrayCache = cache
            historyArrayCacheSize = history.size
        }
        return if (index in cache.indices) cache[index] else null
    }
    var screen = Array(rows) { TerminalRow(cols) }
        private set

    /** Bumped whenever the screen array identity is replaced (resize / alt-screen swap). */
    private var screenGeneration = 0

    var cursorRow = 0
    var cursorCol = 0
    var isCursorVisible = true

    var scrollTop = 0
    var scrollBottom = rows - 1

    var currentFg = TerminalColor.DEFAULT_FG
    var currentBg = TerminalColor.DEFAULT_BG
    var currentStyle: Byte = 0

    // Alternate screen buffer
    private var alternateScreen: Array<TerminalRow>? = null
    private var savedCursorRow = 0
    private var savedCursorCol = 0
    var isAlternate = false
        private set
    var isWrapPending = false

    fun resize(newRows: Int, newCols: Int) {
        if (newRows == rows && newCols == cols) return
        if (newRows <= 0 || newCols <= 0) return

        val newScreen = Array(newRows) { TerminalRow(newCols) }

        if (newRows < rows) {
            // Screen shrunk (keyboard shown). Preserve cursorRow and active lines above it.
            val linesToShift = if (cursorRow >= newRows) cursorRow - newRows + 1 else 0

            // Push lines above visible area to history so they are not lost
            if (!isAlternate) {
                for (r in 0 until linesToShift) {
                    val hRow = TerminalRow(cols)
                    hRow.copyFrom(screen[r])
                    history.addLast(hRow)
                    if (history.size > maxHistory) {
                        history.removeFirst()
                    }
                }
            }

            // Copy lines into new screen
            for (r in 0 until newRows) {
                val srcR = r + linesToShift
                if (srcR in 0 until rows) {
                    newScreen[r].copyFrom(screen[srcR])
                }
            }

            cursorRow = (cursorRow - linesToShift).coerceIn(0, newRows - 1)
        } else {
            // Screen expanded (keyboard dismissed).
            val linesToAdd = newRows - rows
            val restoredCount = if (!isAlternate) minOf(linesToAdd, history.size) else 0

            // Restore lines from history to the top of new screen
            for (r in 0 until restoredCount) {
                val hRow = history.removeLast()
                newScreen[restoredCount - 1 - r].copyFrom(hRow)
            }

            // Copy existing screen lines below restored lines
            for (r in 0 until rows) {
                newScreen[r + restoredCount].copyFrom(screen[r])
            }

            cursorRow = (cursorRow + restoredCount).coerceIn(0, newRows - 1)
        }

        if (isAlternate && alternateScreen != null) {
            val oldAlt = alternateScreen!!
            val newAlt = Array(newRows) { TerminalRow(newCols) }
            val minR = minOf(oldAlt.size, newRows)
            for (r in 0 until minR) {
                newAlt[r].copyFrom(oldAlt[r])
            }
            alternateScreen = newAlt
        }

        screen = newScreen
        screenGeneration++
        rows = newRows
        cols = newCols
        scrollTop = 0
        scrollBottom = rows - 1
        cursorCol = cursorCol.coerceIn(0, cols - 1)
    }

    fun useAlternateScreen(enable: Boolean) {
        if (enable && !isAlternate) {
            alternateScreen = screen
            screen = Array(rows) { TerminalRow(cols) }
            screenGeneration++
            savedCursorRow = cursorRow
            savedCursorCol = cursorCol
            cursorRow = 0
            cursorCol = 0
            scrollTop = 0
            scrollBottom = rows - 1
            isWrapPending = false
            isAlternate = true
        } else if (!enable && isAlternate) {
            alternateScreen?.let { screen = it; screenGeneration++ }
            alternateScreen = null
            cursorRow = savedCursorRow.coerceIn(0, rows - 1)
            cursorCol = savedCursorCol.coerceIn(0, cols - 1)
            scrollTop = 0
            scrollBottom = rows - 1
            isWrapPending = false
            isAlternate = false
        }
    }

    fun writeChar(c: Char) {
        if (isWrapPending) {
            if (cursorRow in 0 until rows) {
                screen[cursorRow].isWrapped = true
            }
            newLine()
            cursorCol = 0
            isWrapPending = false
        }
        val r = cursorRow.coerceIn(0, rows - 1)
        screen[r].setChar(cursorCol, c, currentFg, currentBg, currentStyle)
        if (cursorCol >= cols - 1) {
            isWrapPending = true
        } else {
            cursorCol++
        }
    }

    fun carriageReturn() {
        isWrapPending = false
        cursorCol = 0
    }

    fun newLine() {
        isWrapPending = false
        if (cursorRow in scrollTop..scrollBottom) {
            if (cursorRow == scrollBottom) {
                scrollUp(scrollTop, scrollBottom)
            } else {
                cursorRow++
            }
        } else {
            if (cursorRow >= rows - 1) {
                scrollUp(0, rows - 1)
            } else {
                cursorRow++
            }
        }
    }

    fun scrollUp(top: Int, bottom: Int) {
        val t = top.coerceIn(0, rows - 1)
        val b = bottom.coerceIn(t, rows - 1)

        // If full screen scrolling and not in alternate buffer, save top line to history
        if (t == 0 && b == rows - 1 && !isAlternate) {
            val historyRow = TerminalRow(cols)
            historyRow.copyFrom(screen[0])
            history.addLast(historyRow)
            if (history.size > maxHistory) {
                history.removeFirst()
            }
        }

        for (r in t until b) {
            screen[r].copyFrom(screen[r + 1])
        }
        screen[b].clear(currentFg, currentBg)
    }

    fun scrollDown(top: Int, bottom: Int) {
        val t = top.coerceIn(0, rows - 1)
        val b = bottom.coerceIn(t, rows - 1)
        for (r in b downTo t + 1) {
            screen[r].copyFrom(screen[r - 1])
        }
        screen[t].clear(currentFg, currentBg)
    }

    fun eraseInDisplay(mode: Int) {
        when (mode) {
            0 -> { // From cursor to end
                screen[cursorRow].let { row ->
                    for (c in cursorCol until cols) {
                        row.setChar(c, ' ', currentFg, currentBg, 0)
                    }
                }
                for (r in cursorRow + 1 until rows) {
                    screen[r].clear(currentFg, currentBg)
                }
            }
            1 -> { // From beginning to cursor
                for (r in 0 until cursorRow) {
                    screen[r].clear(currentFg, currentBg)
                }
                screen[cursorRow].let { row ->
                    for (c in 0..cursorCol.coerceAtMost(cols - 1)) {
                        row.setChar(c, ' ', currentFg, currentBg, 0)
                    }
                }
            }
            2, 3 -> { // Entire screen (3 clears scrollback too)
                for (r in 0 until rows) {
                    screen[r].clear(currentFg, currentBg)
                }
                if (mode == 3 && !isAlternate) {
                    history.clear()
                }
            }
        }
    }

    fun eraseInLine(mode: Int) {
        isWrapPending = false
        val r = cursorRow.coerceIn(0, rows - 1)
        val row = screen[r]
        when (mode) {
            0 -> { // Cursor to end of line
                for (c in cursorCol.coerceIn(0, cols - 1) until cols) {
                    row.setChar(c, ' ', currentFg, currentBg, 0)
                }
                row.isWrapped = false
            }
            1 -> { // Start to cursor
                for (c in 0..cursorCol.coerceAtMost(cols - 1)) {
                    row.setChar(c, ' ', currentFg, currentBg, 0)
                }
            }
            2 -> { // Entire line
                row.clear(currentFg, currentBg)
            }
        }
    }

    fun getVisibleRow(screenRowIndex: Int, scrollOffset: Int): TerminalRow {
        if (isAlternate) {
            return if (screenRowIndex in 0 until rows) screen[screenRowIndex] else TerminalRow(cols)
        }
        val totalHistory = history.size
        val effectiveIndex = screenRowIndex - scrollOffset
        return if (effectiveIndex < 0) {
            val historyIndex = totalHistory + effectiveIndex
            getHistoryRow(historyIndex) ?: TerminalRow(cols)
        } else if (effectiveIndex in 0 until rows) {
            screen[effectiveIndex]
        } else {
            TerminalRow(cols)
        }
    }

    data class SearchHit(val row: Int, val startCol: Int, val endCol: Int)

    fun clearSearchHighlights() {
        for (r in history) r.clearSearchFlags()
        for (r in screen) r.clearSearchFlags()
    }

    fun clearUrlHighlights() {
        for (r in history) r.clearUrlFlags()
        for (r in screen) r.clearUrlFlags()
    }

    /**
     * Single-pass, O(n) view over scrollback + screen.
     * The returned list is reused across calls to keep search cheap on 5000-row history.
     */
    /**
     * Flattened, ascending view over scrollback + screen.
     *
     * Buffer row indices are strictly increasing (-history.size .. rows-1), which lets
     * [rowForIndex] binary-search straight into this list instead of walking the
     * LinkedList, keeping full-scrollback search O(n) instead of O(n^2).
     */
    private var rowsCache: ArrayList<Pair<Int, TerminalRow>> = ArrayList()
    private var cachedHistorySize = -1
    private var cachedScreenRows = -1
    private var cachedGeneration = -1

    private fun rebuildRowsCacheIfNeeded() {
        val screenCount = minOf(rows, screen.size)
        if (cachedHistorySize == history.size &&
            cachedScreenRows == screenCount &&
            cachedGeneration == screenGeneration) return
        val needed = history.size + screenCount
        if (rowsCache.size != needed) {
            rowsCache = ArrayList(needed)
        } else {
            rowsCache.clear()
        }
        var idx = -history.size
        val it = history.iterator()
        while (it.hasNext()) {
            rowsCache.add(Pair(idx, it.next()))
            idx++
        }
        for (i in 0 until screenCount) {
            rowsCache.add(Pair(i, screen[i]))
        }
        cachedHistorySize = history.size
        cachedScreenRows = screenCount
        cachedGeneration = screenGeneration
    }

    fun allRowsWithIndex(): List<Pair<Int, TerminalRow>> {
        rebuildRowsCacheIfNeeded()
        return rowsCache
    }

    fun rowForIndex(bufferRow: Int): TerminalRow? {
        if (bufferRow < 0 && bufferRow < -history.size) return null
        if (bufferRow >= rows) return null
        rebuildRowsCacheIfNeeded()
        var lo = 0
        var hi = rowsCache.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val key = rowsCache[mid].first
            when {
                key == bufferRow -> return rowsCache[mid].second
                key < bufferRow -> lo = mid + 1
                else -> hi = mid - 1
            }
        }
        return null
    }

    fun markSearchHits(hits: List<SearchHit>, currentIndex: Int) {
        clearSearchHighlights()
        var lastRowIndex = Int.MIN_VALUE
        var cachedRow: TerminalRow? = null
        for ((idx, hit) in hits.withIndex()) {
            if (hit.row != lastRowIndex) {
                cachedRow = rowForIndex(hit.row)
                lastRowIndex = hit.row
            }
            val row = cachedRow ?: continue
            for (c in hit.startCol..hit.endCol) {
                if (c in 0 until row.cols) {
                    row.isSearchMatch[c] = true
                    if (idx == currentIndex) row.isSearchCurrent[c] = true
                }
            }
        }
    }

    fun getSelectedText(r1: Int, c1: Int, r2: Int, c2: Int): String {
        val (startRow, startCol, endRow, endCol) = if (r1 < r2 || (r1 == r2 && c1 <= c2)) {
            listOf(r1, c1, r2, c2)
        } else {
            listOf(r2, c2, r1, c1)
        }
        val sb = StringBuilder()
        for (r in startRow..endRow) {
            val row = (if (r < 0) {
                val hIdx = history.size + r
                getHistoryRow(hIdx)
            } else if (r in 0 until rows) {
                screen[r]
            } else null) ?: continue

            val cStart = if (r == startRow) startCol.coerceIn(0, cols - 1) else 0
            val cEnd = if (r == endRow) endCol.coerceIn(0, cols - 1) else cols - 1

            if (row.isWrapped) {
                for (c in cStart..cEnd) {
                    sb.append(row.chars[c])
                }
            } else {
                var lastChar = cEnd
                while (lastChar >= cStart && row.chars[lastChar] == ' ') {
                    lastChar--
                }
                for (c in cStart..lastChar) {
                    sb.append(row.chars[c])
                }
                if (r != endRow) {
                    sb.append("\n")
                }
            }
        }
        return sb.toString()
    }
}
