package org.cortex.terminal.emulator

import android.graphics.Color
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class TerminalEmulator(
    rows: Int,
    cols: Int,
    val onScreenUpdate: () -> Unit
) {
    val lock = ReentrantLock()
    val buffer = TerminalBuffer(rows, cols)

    private enum class State {
        NORMAL, ESCAPE, CSI, OSC
    }

    private var state = State.NORMAL
    private val csiParams = ArrayList<Int>()
    private var csiCurrentParam = 0
    private var csiHasParam = false
    private var csiPrivate = false
    private val oscBuffer = StringBuilder()

    var onTitleChange: ((String) -> Unit)? = null
    var onBell: (() -> Unit)? = null

    fun resize(rows: Int, cols: Int) {
        lock.withLock {
            buffer.resize(rows, cols)
        }
        onScreenUpdate()
    }

    fun processInput(bytes: ByteArray, offset: Int, length: Int) {
        lock.withLock {
            val text = String(bytes, offset, length, Charsets.UTF_8)
            for (ch in text) {
                processChar(ch)
            }
        }
        onScreenUpdate()
    }

    private fun processChar(c: Char) {
        when (state) {
            State.NORMAL -> handleNormal(c)
            State.ESCAPE -> handleEscape(c)
            State.CSI -> handleCsi(c)
            State.OSC -> handleOsc(c)
        }
    }

    private fun handleNormal(c: Char) {
        when (c) {
            '\u001b' -> state = State.ESCAPE
            '\r' -> buffer.cursorCol = 0
            '\n' -> buffer.newLine()
            '\b' -> buffer.cursorCol = (buffer.cursorCol - 1).coerceAtLeast(0)
            '\t' -> {
                val nextTab = (buffer.cursorCol / 8 + 1) * 8
                buffer.cursorCol = nextTab.coerceAtMost(buffer.cols - 1)
            }
            '\u0007' -> onBell?.invoke()
            else -> {
                if (c >= ' ') {
                    buffer.writeChar(c)
                }
            }
        }
    }

    private var savedCursorRow = 0
    private var savedCursorCol = 0

    private fun handleEscape(c: Char) {
        when (c) {
            '[' -> {
                state = State.CSI
                csiParams.clear()
                csiCurrentParam = 0
                csiHasParam = false
                csiPrivate = false
            }
            ']' -> {
                state = State.OSC
                oscBuffer.setLength(0)
            }
            'c' -> { // Full reset
                buffer.eraseInDisplay(2)
                buffer.cursorRow = 0
                buffer.cursorCol = 0
                state = State.NORMAL
            }
            '7' -> { // Save cursor (DECSC)
                savedCursorRow = buffer.cursorRow
                savedCursorCol = buffer.cursorCol
                state = State.NORMAL
            }
            '8' -> { // Restore cursor (DECRC)
                buffer.cursorRow = savedCursorRow.coerceIn(0, buffer.rows - 1)
                buffer.cursorCol = savedCursorCol.coerceIn(0, buffer.cols - 1)
                state = State.NORMAL
            }
            else -> {
                state = State.NORMAL
            }
        }
    }

    private fun handleCsi(c: Char) {
        if (c == '?') {
            csiPrivate = true
            return
        }

        if (c in '0'..'9') {
            csiCurrentParam = csiCurrentParam * 10 + (c - '0')
            csiHasParam = true
            return
        }

        if (c == ';') {
            csiParams.add(if (csiHasParam) csiCurrentParam else 0)
            csiCurrentParam = 0
            csiHasParam = false
            return
        }

        // Final character of CSI
        if (csiHasParam) {
            csiParams.add(csiCurrentParam)
        }

        executeCsi(c)
        state = State.NORMAL
    }

    private fun executeCsi(cmd: Char) {
        val p1 = if (csiParams.isNotEmpty()) csiParams[0] else 0
        val p2 = if (csiParams.size > 1) csiParams[1] else 0

        when (cmd) {
            'A' -> { // Cursor Up
                val count = if (p1 == 0) 1 else p1
                buffer.cursorRow = (buffer.cursorRow - count).coerceAtLeast(buffer.scrollTop)
            }
            'B' -> { // Cursor Down
                val count = if (p1 == 0) 1 else p1
                buffer.cursorRow = (buffer.cursorRow + count).coerceAtMost(buffer.scrollBottom)
            }
            'C' -> { // Cursor Forward
                val count = if (p1 == 0) 1 else p1
                buffer.cursorCol = (buffer.cursorCol + count).coerceAtMost(buffer.cols - 1)
            }
            'D' -> { // Cursor Back
                val count = if (p1 == 0) 1 else p1
                buffer.cursorCol = (buffer.cursorCol - count).coerceAtLeast(0)
            }
            'H', 'f' -> { // Cursor Position (1-indexed)
                val row = if (p1 == 0) 1 else p1
                val col = if (p2 == 0) 1 else p2
                buffer.cursorRow = (row - 1).coerceIn(0, buffer.rows - 1)
                buffer.cursorCol = (col - 1).coerceIn(0, buffer.cols - 1)
            }
            'J' -> { // Erase In Display
                buffer.eraseInDisplay(p1)
            }
            'K' -> { // Erase In Line
                buffer.eraseInLine(p1)
            }
            'L' -> { // Insert line
                val count = if (p1 == 0) 1 else p1
                for (i in 0 until count) {
                    buffer.scrollDown(buffer.cursorRow, buffer.scrollBottom)
                }
            }
            'M' -> { // Delete line
                val count = if (p1 == 0) 1 else p1
                for (i in 0 until count) {
                    buffer.scrollUp(buffer.cursorRow, buffer.scrollBottom)
                }
            }
            'm' -> { // SGR (Select Graphic Rendition)
                handleSgr()
            }
            'r' -> { // Set Scroll Margins
                if (csiParams.isEmpty()) {
                    buffer.scrollTop = 0
                    buffer.scrollBottom = buffer.rows - 1
                } else {
                    val top = (p1 - 1).coerceIn(0, buffer.rows - 1)
                    val bottom = if (p2 == 0) buffer.rows - 1 else (p2 - 1).coerceIn(top, buffer.rows - 1)
                    buffer.scrollTop = top
                    buffer.scrollBottom = bottom
                }
            }
            'h' -> { // Set Mode
                if (csiPrivate) {
                    when (p1) {
                        25 -> buffer.isCursorVisible = true
                        47, 1047, 1049 -> buffer.useAlternateScreen(true)
                    }
                }
            }
            'l' -> { // Reset Mode
                if (csiPrivate) {
                    when (p1) {
                        25 -> buffer.isCursorVisible = false
                        47, 1047, 1049 -> buffer.useAlternateScreen(false)
                    }
                }
            }
            's' -> { // Save Cursor (ANSI.SYS)
                savedCursorRow = buffer.cursorRow
                savedCursorCol = buffer.cursorCol
            }
            'u' -> { // Restore Cursor (ANSI.SYS)
                buffer.cursorRow = savedCursorRow.coerceIn(0, buffer.rows - 1)
                buffer.cursorCol = savedCursorCol.coerceIn(0, buffer.cols - 1)
            }
            'G' -> { // Cursor Character Absolute (CHA)
                val col = if (p1 == 0) 1 else p1
                buffer.cursorCol = (col - 1).coerceIn(0, buffer.cols - 1)
            }
            'd' -> { // Line Position Absolute (VPA)
                val row = if (p1 == 0) 1 else p1
                buffer.cursorRow = (row - 1).coerceIn(0, buffer.rows - 1)
            }
            'X' -> { // Erase Characters (ECH)
                val count = if (p1 == 0) 1 else p1
                val r = buffer.cursorRow
                if (r in 0 until buffer.rows) {
                    val endCol = (buffer.cursorCol + count).coerceAtMost(buffer.cols)
                    for (c in buffer.cursorCol until endCol) {
                        buffer.screen[r].setChar(c, ' ', buffer.currentFg, buffer.currentBg, 0)
                    }
                }
            }
        }
    }

    private fun handleSgr() {
        if (csiParams.isEmpty()) {
            buffer.currentFg = TerminalColor.DEFAULT_FG
            buffer.currentBg = TerminalColor.DEFAULT_BG
            buffer.currentStyle = 0
            return
        }

        var i = 0
        while (i < csiParams.size) {
            when (val code = csiParams[i]) {
                0 -> {
                    buffer.currentFg = TerminalColor.DEFAULT_FG
                    buffer.currentBg = TerminalColor.DEFAULT_BG
                    buffer.currentStyle = 0
                }
                1 -> buffer.currentStyle = (buffer.currentStyle.toInt() or 1).toByte() // Bold
                4 -> buffer.currentStyle = (buffer.currentStyle.toInt() or 2).toByte() // Underline
                7 -> buffer.currentStyle = (buffer.currentStyle.toInt() or 4).toByte() // Reverse
                22 -> buffer.currentStyle = (buffer.currentStyle.toInt() and 1.inv()).toByte() // Bold off
                24 -> buffer.currentStyle = (buffer.currentStyle.toInt() and 2.inv()).toByte() // Underline off
                27 -> buffer.currentStyle = (buffer.currentStyle.toInt() and 4.inv()).toByte() // Reverse off
                in 30..37 -> buffer.currentFg = TerminalColor.ANSI_COLORS[code - 30]
                38 -> { // Extended foreground
                    if (i + 2 < csiParams.size && csiParams[i + 1] == 5) {
                        val colorIdx = csiParams[i + 2].coerceIn(0, 255)
                        buffer.currentFg = TerminalColor.COLOR_PALETTE[colorIdx]
                        i += 2
                    } else if (i + 4 < csiParams.size && csiParams[i + 1] == 2) {
                        val r = csiParams[i + 2].coerceIn(0, 255)
                        val g = csiParams[i + 3].coerceIn(0, 255)
                        val b = csiParams[i + 4].coerceIn(0, 255)
                        buffer.currentFg = Color.rgb(r, g, b)
                        i += 4
                    }
                }
                39 -> buffer.currentFg = TerminalColor.DEFAULT_FG
                in 40..47 -> buffer.currentBg = TerminalColor.ANSI_COLORS[code - 40]
                48 -> { // Extended background
                    if (i + 2 < csiParams.size && csiParams[i + 1] == 5) {
                        val colorIdx = csiParams[i + 2].coerceIn(0, 255)
                        buffer.currentBg = TerminalColor.COLOR_PALETTE[colorIdx]
                        i += 2
                    } else if (i + 4 < csiParams.size && csiParams[i + 1] == 2) {
                        val r = csiParams[i + 2].coerceIn(0, 255)
                        val g = csiParams[i + 3].coerceIn(0, 255)
                        val b = csiParams[i + 4].coerceIn(0, 255)
                        buffer.currentBg = Color.rgb(r, g, b)
                        i += 4
                    }
                }
                49 -> buffer.currentBg = TerminalColor.DEFAULT_BG
                in 90..97 -> buffer.currentFg = TerminalColor.ANSI_COLORS[code - 90 + 8]
                in 100..107 -> buffer.currentBg = TerminalColor.ANSI_COLORS[code - 100 + 8]
            }
            i++
        }
    }

    private fun handleOsc(c: Char) {
        if (c == '\u0007' || (c == '\\' && oscBuffer.endsWith("\u001b"))) {
            val fullStr = oscBuffer.toString().removeSuffix("\u001b")
            val parts = fullStr.split(";", limit = 2)
            if (parts.size == 2 && (parts[0] == "0" || parts[0] == "2")) {
                onTitleChange?.invoke(parts[1])
            }
            state = State.NORMAL
        } else {
            oscBuffer.append(c)
        }
    }
}
