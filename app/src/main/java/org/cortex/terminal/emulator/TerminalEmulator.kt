package org.cortex.terminal.emulator

import android.graphics.Color
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class TerminalEmulator(
    rows: Int,
    cols: Int,
    var onScreenUpdate: (() -> Unit)? = null
) {
    val lock = ReentrantLock()
    val buffer = TerminalBuffer(rows, cols)

    private enum class State {
        NORMAL, ESCAPE, CSI, OSC, CHARSET
    }

    private var state = State.NORMAL
    private val csiParams = ArrayList<Int>()
    private var csiCurrentParam = 0
    private var csiHasParam = false
    private var csiPrefix: Char? = null
    private var csiIntermediate: Char? = null
    private val oscBuffer = StringBuilder()

    var onTitleChange: ((String) -> Unit)? = null
    var onBell: (() -> Unit)? = null
    var onSendResponse: ((String) -> Unit)? = null
    var isApplicationCursorKeys = false

    enum class MouseMode {
        OFF,
        X10,          // 9
        VT200,        // 1000, 1001
        CELL_MOTION,  // 1002
        ALL_MOTION    // 1003
    }

    var mouseMode = MouseMode.OFF
    var isMouseSgr = false      // 1006
    var isMouseUrxvt = false    // 1015
    var isMouseUtf8 = false     // 1005

    val isMouseTrackingActive: Boolean
        get() = mouseMode != MouseMode.OFF

    fun getMouseScrollSequence(isUp: Boolean, col: Int, row: Int): String {
        val button = if (isUp) 64 else 65
        return if (isMouseSgr) {
            "\u001b[<${button};${col};${row}M"
        } else if (isMouseUrxvt) {
            "\u001b[${button + 32};${col};${row}M"
        } else {
            "\u001b[M" + (32 + button).toChar() + (32 + col).toChar() + (32 + row).toChar()
        }
    }

    fun getMouseClickSequence(button: Int, col: Int, row: Int): String {
        return if (isMouseSgr) {
            "\u001b[<${button};${col};${row}M\u001b[<${button};${col};${row}m"
        } else if (isMouseUrxvt) {
            "\u001b[${button + 32};${col};${row}M\u001b[${3 + 32};${col};${row}M"
        } else {
            val press = "\u001b[M" + (32 + button).toChar() + (32 + col).toChar() + (32 + row).toChar()
            val release = "\u001b[M" + (32 + 3).toChar() + (32 + col).toChar() + (32 + row).toChar()
            press + release
        }
    }

    fun resize(rows: Int, cols: Int) {
        lock.withLock {
            buffer.resize(rows, cols)
        }
        onScreenUpdate?.invoke()
    }

    fun processInput(bytes: ByteArray, offset: Int, length: Int) {
        lock.withLock {
            val text = String(bytes, offset, length, Charsets.UTF_8)
            for (ch in text) {
                processChar(ch)
            }
        }
        onScreenUpdate?.invoke()
    }

    private fun processChar(c: Char) {
        when (state) {
            State.NORMAL -> handleNormal(c)
            State.ESCAPE -> handleEscape(c)
            State.CSI -> handleCsi(c)
            State.OSC -> handleOsc(c)
            State.CHARSET -> handleCharset(c)
        }
    }

    private fun handleCharset(c: Char) {
        // Consumes the charset designator (e.g. 'B' for ASCII, '0' for DEC line drawing)
        state = State.NORMAL
    }

    private fun handleNormal(c: Char) {
        when (c) {
            '\u001b' -> state = State.ESCAPE
            '\r' -> buffer.carriageReturn()
            '\n' -> buffer.newLine()
            '\b' -> {
                buffer.cursorCol = (buffer.cursorCol - 1).coerceAtLeast(0)
                buffer.isWrapPending = false
            }
            '\t' -> {
                val nextTab = (buffer.cursorCol / 8 + 1) * 8
                buffer.cursorCol = nextTab.coerceAtMost(buffer.cols - 1)
                buffer.isWrapPending = false
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
                csiPrefix = null
                csiIntermediate = null
            }
            ']' -> {
                state = State.OSC
                oscBuffer.setLength(0)
            }
            '(', ')', '*', '+' -> {
                // Character set selection (e.g. \e(B for US-ASCII, \e(0 for line drawing)
                state = State.CHARSET
            }
            'c' -> { // Full reset (RIS)
                buffer.eraseInDisplay(2)
                buffer.cursorRow = 0
                buffer.cursorCol = 0
                isApplicationCursorKeys = false
                mouseMode = MouseMode.OFF
                isMouseSgr = false
                isMouseUrxvt = false
                isMouseUtf8 = false
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
                buffer.isWrapPending = false
                state = State.NORMAL
            }
            'M' -> { // Reverse Index (RI)
                if (buffer.cursorRow == buffer.scrollTop) {
                    buffer.scrollDown(buffer.scrollTop, buffer.scrollBottom)
                } else {
                    buffer.cursorRow = (buffer.cursorRow - 1).coerceAtLeast(0)
                }
                buffer.isWrapPending = false
                state = State.NORMAL
            }
            'D' -> { // Index (IND)
                buffer.newLine()
                state = State.NORMAL
            }
            'E' -> { // Next Line (NEL)
                buffer.carriageReturn()
                buffer.newLine()
                state = State.NORMAL
            }
            '=', '>' -> { // Application / Normal keypad mode
                state = State.NORMAL
            }
            'H' -> { // Horizontal Tab Set
                state = State.NORMAL
            }
            else -> {
                state = State.NORMAL
            }
        }
    }

    private fun handleCsi(c: Char) {
        // Parameter prefix character (e.g. '?' for DEC private, '>' for secondary DA, '=', '<', '!')
        if (csiParams.isEmpty() && !csiHasParam && (c == '?' || c == '>' || c == '=' || c == '<' || c == '!')) {
            csiPrefix = c
            return
        }

        if (c in '0'..'9') {
            csiCurrentParam = csiCurrentParam * 10 + (c - '0')
            csiHasParam = true
            return
        }

        if (c == ';' || c == ':') {
            csiParams.add(if (csiHasParam) csiCurrentParam else 0)
            csiCurrentParam = 0
            csiHasParam = false
            return
        }

        // Intermediate character (0x20 - 0x2F, e.g. space, $, ', ", *)
        if (c in ' '..'/' && c != ';') {
            csiIntermediate = c
            return
        }

        // Final character (0x40 - 0x7E)
        if (c in '@'..'~') {
            if (csiHasParam) {
                csiParams.add(csiCurrentParam)
            }
            executeCsi(c)
            state = State.NORMAL
            return
        }

        if (c == '\u001b') {
            state = State.ESCAPE
            return
        }

        // Any other character cancels CSI
        state = State.NORMAL
    }

    private fun executeCsi(cmd: Char) {
        val p1 = if (csiParams.isNotEmpty()) csiParams[0] else 0
        val p2 = if (csiParams.size > 1) csiParams[1] else 0

        when (cmd) {
            'A' -> { // Cursor Up
                val count = if (p1 == 0) 1 else p1
                buffer.cursorRow = (buffer.cursorRow - count).coerceAtLeast(0)
                buffer.isWrapPending = false
            }
            'B' -> { // Cursor Down
                val count = if (p1 == 0) 1 else p1
                buffer.cursorRow = (buffer.cursorRow + count).coerceAtMost(buffer.rows - 1)
                buffer.isWrapPending = false
            }
            'C' -> { // Cursor Forward
                val count = if (p1 == 0) 1 else p1
                buffer.cursorCol = (buffer.cursorCol + count).coerceAtMost(buffer.cols - 1)
                buffer.isWrapPending = false
            }
            'D' -> { // Cursor Back
                val count = if (p1 == 0) 1 else p1
                buffer.cursorCol = (buffer.cursorCol - count).coerceAtLeast(0)
                buffer.isWrapPending = false
            }
            'E' -> { // Cursor Next Line (CNL)
                val count = if (p1 == 0) 1 else p1
                buffer.cursorRow = (buffer.cursorRow + count).coerceAtMost(buffer.rows - 1)
                buffer.cursorCol = 0
                buffer.isWrapPending = false
            }
            'F' -> { // Cursor Previous Line (CPL)
                val count = if (p1 == 0) 1 else p1
                buffer.cursorRow = (buffer.cursorRow - count).coerceAtLeast(0)
                buffer.cursorCol = 0
                buffer.isWrapPending = false
            }
            'G' -> { // Cursor Character Absolute (CHA)
                val col = if (p1 == 0) 1 else p1
                buffer.cursorCol = (col - 1).coerceIn(0, buffer.cols - 1)
                buffer.isWrapPending = false
            }
            'H', 'f' -> { // Cursor Position (1-indexed)
                val row = if (p1 == 0) 1 else p1
                val col = if (p2 == 0) 1 else p2
                buffer.cursorRow = (row - 1).coerceIn(0, buffer.rows - 1)
                buffer.cursorCol = (col - 1).coerceIn(0, buffer.cols - 1)
                buffer.isWrapPending = false
            }
            'I' -> { // Cursor Forward Tabulation (CHT)
                val count = if (p1 == 0) 1 else p1
                for (k in 0 until count) {
                    val nextTab = (buffer.cursorCol / 8 + 1) * 8
                    buffer.cursorCol = nextTab.coerceAtMost(buffer.cols - 1)
                }
                buffer.isWrapPending = false
            }
            'J' -> { // Erase In Display
                buffer.isWrapPending = false
                buffer.eraseInDisplay(p1)
            }
            'K' -> { // Erase In Line
                buffer.isWrapPending = false
                buffer.eraseInLine(p1)
            }
            'L' -> { // Insert line
                val count = if (p1 == 0) 1 else p1
                for (i in 0 until count) {
                    buffer.scrollDown(buffer.cursorRow, buffer.scrollBottom)
                }
                buffer.isWrapPending = false
            }
            'M' -> { // Delete line
                val count = if (p1 == 0) 1 else p1
                for (i in 0 until count) {
                    buffer.scrollUp(buffer.cursorRow, buffer.scrollBottom)
                }
                buffer.isWrapPending = false
            }
            'P' -> { // Delete characters (DCH)
                val count = if (p1 == 0) 1 else p1
                buffer.isWrapPending = false
                val r = buffer.cursorRow.coerceIn(0, buffer.rows - 1)
                buffer.screen[r].deleteChars(buffer.cursorCol, count, buffer.currentFg, buffer.currentBg)
            }
            '@' -> { // Insert characters (ICH)
                val count = if (p1 == 0) 1 else p1
                buffer.isWrapPending = false
                val r = buffer.cursorRow.coerceIn(0, buffer.rows - 1)
                buffer.screen[r].insertChars(buffer.cursorCol, count, buffer.currentFg, buffer.currentBg)
            }
            'S' -> { // Scroll Up (SU)
                val count = if (p1 == 0) 1 else p1
                for (i in 0 until count) {
                    buffer.scrollUp(buffer.scrollTop, buffer.scrollBottom)
                }
            }
            'T' -> { // Scroll Down (SD)
                val count = if (p1 == 0) 1 else p1
                for (i in 0 until count) {
                    buffer.scrollDown(buffer.scrollTop, buffer.scrollBottom)
                }
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
                buffer.isWrapPending = false
            }
            'Z' -> { // Cursor Backward Tabulation (CBT)
                val count = if (p1 == 0) 1 else p1
                for (k in 0 until count) {
                    val prevTab = if (buffer.cursorCol % 8 == 0) buffer.cursorCol - 8 else (buffer.cursorCol / 8) * 8
                    buffer.cursorCol = prevTab.coerceAtLeast(0)
                }
                buffer.isWrapPending = false
            }
            'd' -> { // Line Position Absolute (VPA)
                val row = if (p1 == 0) 1 else p1
                buffer.cursorRow = (row - 1).coerceIn(0, buffer.rows - 1)
                buffer.isWrapPending = false
            }
            'm' -> { // SGR (Select Graphic Rendition)
                handleSgr()
            }
            'n' -> { // Device Status Report (DSR)
                if (csiPrefix == null) {
                    if (p1 == 6) { // Cursor Position Report (CPR)
                        val row = (buffer.cursorRow + 1).coerceIn(1, buffer.rows)
                        val col = (buffer.cursorCol + 1).coerceIn(1, buffer.cols)
                        onSendResponse?.invoke("\u001b[${row};${col}R")
                    } else if (p1 == 5) { // Status Report
                        onSendResponse?.invoke("\u001b[0n") // OK
                    }
                }
            }
            'c' -> { // Device Attributes (DA)
                if (csiPrefix == '>') { // Secondary DA
                    onSendResponse?.invoke("\u001b[>0;10;0c")
                } else { // Primary DA
                    onSendResponse?.invoke("\u001b[?62;1;2;6;7;8;9c")
                }
            }
            't' -> { // Window Manipulation
                if (p1 == 18) { // Report terminal size in characters
                    onSendResponse?.invoke("\u001b[8;${buffer.rows};${buffer.cols}t")
                }
            }
            'r' -> { // Set Scroll Margins (DECSTBM)
                if (csiParams.isEmpty()) {
                    buffer.scrollTop = 0
                    buffer.scrollBottom = buffer.rows - 1
                } else {
                    val top = (p1 - 1).coerceIn(0, buffer.rows - 1)
                    val bottom = if (p2 == 0) buffer.rows - 1 else (p2 - 1).coerceIn(top, buffer.rows - 1)
                    buffer.scrollTop = top
                    buffer.scrollBottom = bottom
                }
                buffer.cursorRow = 0
                buffer.cursorCol = 0
                buffer.isWrapPending = false
            }
            'h' -> { // Set Mode
                val params = if (csiParams.isEmpty()) listOf(0) else csiParams
                for (p in params) {
                    if (csiPrefix == '?') {
                        when (p) {
                            1 -> isApplicationCursorKeys = true
                            9 -> mouseMode = MouseMode.X10
                            25 -> buffer.isCursorVisible = true
                            47, 1047, 1049 -> buffer.useAlternateScreen(true)
                            1000, 1001 -> mouseMode = MouseMode.VT200
                            1002 -> mouseMode = MouseMode.CELL_MOTION
                            1003 -> mouseMode = MouseMode.ALL_MOTION
                            1005 -> isMouseUtf8 = true
                            1006 -> isMouseSgr = true
                            1015 -> isMouseUrxvt = true
                        }
                    }
                }
            }
            'l' -> { // Reset Mode
                val params = if (csiParams.isEmpty()) listOf(0) else csiParams
                for (p in params) {
                    if (csiPrefix == '?') {
                        when (p) {
                            1 -> isApplicationCursorKeys = false
                            9, 1000, 1001, 1002, 1003 -> mouseMode = MouseMode.OFF
                            25 -> buffer.isCursorVisible = false
                            47, 1047, 1049 -> buffer.useAlternateScreen(false)
                            1005 -> isMouseUtf8 = false
                            1006 -> isMouseSgr = false
                            1015 -> isMouseUrxvt = false
                        }
                    }
                }
            }
            's' -> { // Save Cursor
                savedCursorRow = buffer.cursorRow
                savedCursorCol = buffer.cursorCol
            }
            'u' -> { // Restore Cursor
                buffer.cursorRow = savedCursorRow.coerceIn(0, buffer.rows - 1)
                buffer.cursorCol = savedCursorCol.coerceIn(0, buffer.cols - 1)
                buffer.isWrapPending = false
            }
            'q' -> { // Cursor Style (DECSCUSR)
                // Ignored gracefully
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
