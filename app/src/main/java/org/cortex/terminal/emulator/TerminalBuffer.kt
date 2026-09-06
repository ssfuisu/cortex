package org.cortex.terminal.emulator

import java.util.LinkedList

class TerminalBuffer(var rows: Int, var cols: Int, private val maxHistory: Int = 5000) {
    val history = LinkedList<TerminalRow>()
    var screen = Array(rows) { TerminalRow(cols) }

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

    fun resize(newRows: Int, newCols: Int) {
        if (newRows == rows && newCols == cols) return
        val newScreen = Array(newRows) { TerminalRow(newCols) }
        val copyRows = minOf(rows, newRows)
        for (r in 0 until copyRows) {
            newScreen[r].copyFrom(screen[r])
        }
        screen = newScreen
        rows = newRows
        cols = newCols
        scrollTop = 0
        scrollBottom = rows - 1
        cursorRow = cursorRow.coerceIn(0, rows - 1)
        cursorCol = cursorCol.coerceIn(0, cols - 1)
    }

    fun useAlternateScreen(enable: Boolean) {
        if (enable && !isAlternate) {
            alternateScreen = screen
            screen = Array(rows) { TerminalRow(cols) }
            savedCursorRow = cursorRow
            savedCursorCol = cursorCol
            isAlternate = true
        } else if (!enable && isAlternate) {
            alternateScreen?.let { screen = it }
            alternateScreen = null
            cursorRow = savedCursorRow.coerceIn(0, rows - 1)
            cursorCol = savedCursorCol.coerceIn(0, cols - 1)
            isAlternate = false
        }
    }

    fun writeChar(c: Char) {
        if (cursorCol >= cols) {
            newLine()
            cursorCol = 0
        }
        screen[cursorRow].setChar(cursorCol, c, currentFg, currentBg, currentStyle)
        cursorCol++
    }

    fun newLine() {
        if (cursorRow >= scrollBottom) {
            scrollUp(scrollTop, scrollBottom)
        } else {
            cursorRow++
        }
    }

    fun scrollUp(top: Int, bottom: Int) {
        val t = top.coerceIn(0, rows - 1)
        val b = bottom.coerceIn(t, rows - 1)

        // If top is 0 and not in alternate buffer, save top line to history
        if (t == 0 && !isAlternate) {
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
        val row = screen[cursorRow]
        when (mode) {
            0 -> { // Cursor to end of line
                for (c in cursorCol until cols) {
                    row.setChar(c, ' ', currentFg, currentBg, 0)
                }
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
        val totalHistory = history.size
        val effectiveIndex = screenRowIndex - scrollOffset
        return if (effectiveIndex < 0) {
            val historyIndex = totalHistory + effectiveIndex
            if (historyIndex in 0 until totalHistory) {
                history[historyIndex]
            } else {
                TerminalRow(cols)
            }
        } else if (effectiveIndex in 0 until rows) {
            screen[effectiveIndex]
        } else {
            TerminalRow(cols)
        }
    }

    fun getSelectedText(startRow: Int, startCol: Int, endRow: Int, endCol: Int): String {
        val sb = StringBuilder()
        for (r in startRow..endRow) {
            val row = (if (r < 0) {
                val hIdx = history.size + r
                if (hIdx in 0 until history.size) history[hIdx] else null
            } else if (r in 0 until rows) {
                screen[r]
            } else null) ?: continue

            val cStart = if (r == startRow) startCol.coerceIn(0, cols - 1) else 0
            val cEnd = if (r == endRow) endCol.coerceIn(0, cols - 1) else cols - 1

            for (c in cStart..cEnd) {
                sb.append(row.chars[c])
            }
            if (r != endRow) sb.append("\n")
        }
        return sb.toString()
    }
}
