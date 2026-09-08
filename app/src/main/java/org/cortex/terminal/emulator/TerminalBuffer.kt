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

        screen = newScreen
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
        return sb.toString().trimEnd()
    }
}
