package org.cortex.terminal.emulator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalModesTest {

    private val esc = "\u001b"

    private fun emulator() = TerminalEmulator(rows = 5, cols = 40)

    private fun TerminalEmulator.feed(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        processInput(bytes, 0, bytes.size)
    }

    private fun TerminalEmulator.responses(): MutableList<String> {
        val got = mutableListOf<String>()
        onSendResponse = { got.add(it) }
        return got
    }

    // ---- Bracketed paste (2004) ----

    @Test
    fun bracketedPaste_isOffUntilTheProgramAsksForIt() {
        val e = emulator()
        assertNull(e.bracketedPaste("ls\n"))

        e.feed("$esc[?2004h")
        assertTrue(e.isBracketedPasteMode)
        assertEquals("$esc[200~echo a\recho b$esc[201~", e.bracketedPaste("echo a\necho b\n"))

        e.feed("$esc[?2004l")
        assertNull(e.bracketedPaste("ls"))
    }

    @Test
    fun bracketedPaste_keepsLinesAsCrAndDropsTrailingBreaks() {
        val e = emulator()
        e.feed("$esc[?2004h")
        assertEquals("$esc[200~a\rb\rc\r\rd\te$esc[201~", e.bracketedPaste("a\r\nb\rc\n\nd\te\r\n\n"))
        assertEquals("", e.bracketedPaste("\n\r\n"))
    }

    @Test
    fun bracketedPaste_removesControlsThatCouldEndTheBracketEarly() {
        val e = emulator()
        e.feed("$esc[?2004h")
        val evil = "safe$esc[201~rm -rf ~\u0003\u0000\u007f\u009b"
        assertEquals("$esc[200~safe[201~rm -rf ~$esc[201~", e.bracketedPaste(evil))
        assertEquals("$esc[200~ünicode ✓$esc[201~", e.bracketedPaste("ünicode ✓"))
    }

    // ---- Cursor style (DECSCUSR) ----

    @Test
    fun decscusr_setsShapeAndBlinking() {
        val e = emulator()
        assertEquals(TerminalEmulator.CursorShape.BLOCK, e.cursorShape)
        assertFalse(e.isCursorBlinking)

        val expected = listOf(
            1 to (TerminalEmulator.CursorShape.BLOCK to true),
            2 to (TerminalEmulator.CursorShape.BLOCK to false),
            3 to (TerminalEmulator.CursorShape.UNDERLINE to true),
            4 to (TerminalEmulator.CursorShape.UNDERLINE to false),
            5 to (TerminalEmulator.CursorShape.BAR to true),
            6 to (TerminalEmulator.CursorShape.BAR to false),
        )
        for ((ps, style) in expected) {
            e.feed("$esc[$ps q")
            assertEquals("shape for $ps", style.first, e.cursorShape)
            assertEquals("blinking for $ps", style.second, e.isCursorBlinking)
        }

        // 0 and no parameter restore Cortex's steady block.
        e.feed("$esc[5 q$esc[0 q")
        assertEquals(TerminalEmulator.CursorShape.BLOCK, e.cursorShape)
        assertFalse(e.isCursorBlinking)
        e.feed("$esc[6 q$esc[ q")
        assertEquals(TerminalEmulator.CursorShape.BLOCK, e.cursorShape)
    }

    @Test
    fun decscusr_ignoresUnknownStylesAndOtherQCommands() {
        val e = emulator()
        e.feed("$esc[6 q")
        e.feed("$esc[9 q") // unknown style
        e.feed("$esc[4q") // DECLL (keyboard LEDs), no space
        e.feed("$esc[>q") // XTVERSION query
        assertEquals(TerminalEmulator.CursorShape.BAR, e.cursorShape)
        assertFalse(e.isCursorBlinking)
    }

    // ---- Focus reporting (1004) ----

    @Test
    fun focusReport_onlyWhenEnabled() {
        val e = emulator()
        assertNull(e.focusReport(true))
        e.feed("$esc[?1004h")
        assertEquals("$esc[I", e.focusReport(true))
        assertEquals("$esc[O", e.focusReport(false))
        e.feed("$esc[?1004l")
        assertNull(e.focusReport(false))
    }

    // ---- Synchronized output (2026) ----

    @Test
    fun synchronizedUpdate_holdsTheFrameUntilItEndsOrTimesOut() {
        val e = emulator()
        var now = 1_000L
        e.monotonicMs = { now }
        assertEquals(0L, e.synchronizedUpdateRemainingMs())

        e.feed("$esc[?2026h")
        assertEquals(TerminalEmulator.SYNC_UPDATE_TIMEOUT_MS, e.synchronizedUpdateRemainingMs())
        now += 100
        e.feed("half a frame")
        assertEquals(TerminalEmulator.SYNC_UPDATE_TIMEOUT_MS - 100, e.synchronizedUpdateRemainingMs())

        e.feed(" and the rest$esc[?2026l")
        assertEquals(0L, e.synchronizedUpdateRemainingMs())

        // A frame the program never ends only holds the screen until the timeout.
        e.feed("$esc[?2026h")
        now += TerminalEmulator.SYNC_UPDATE_TIMEOUT_MS
        assertEquals(0L, e.synchronizedUpdateRemainingMs())
    }

    @Test
    fun synchronizedUpdate_drawsAFrameThatEndedEvenIfTheNextOneStarted() {
        val e = emulator()
        e.monotonicMs = { 5_000L }
        e.feed("$esc[?2026hframe 1")
        assertTrue(e.synchronizedUpdateRemainingMs() > 0L)

        // One read from the PTY holds the end of frame 1 and the start of frame 2.
        e.feed("$esc[?2026l$esc[?2026hframe 2")
        assertEquals(0L, e.synchronizedUpdateRemainingMs())

        // The next read only continues frame 2, which is not complete yet.
        e.feed(" continues")
        assertTrue(e.synchronizedUpdateRemainingMs() > 0L)
    }

    @Test
    fun fullReset_clearsTheNewModes() {
        val e = emulator()
        e.feed("$esc[?2004h$esc[?1004h$esc[?2026h$esc[3 q")
        e.feed("${esc}c")
        assertFalse(e.isBracketedPasteMode)
        assertFalse(e.isFocusReportingMode)
        assertEquals(0L, e.synchronizedUpdateRemainingMs())
        assertEquals(TerminalEmulator.CursorShape.BLOCK, e.cursorShape)
        assertFalse(e.isCursorBlinking)
    }

    // ---- Mode reports (DECRQM) ----

    @Test
    fun decrqm_reportsSupportedModes() {
        val e = emulator()
        val got = e.responses()

        e.feed("$esc[?2026\$p")
        e.feed("$esc[?2026h$esc[?2026\$p$esc[?2026l")
        e.feed("$esc[?2004h$esc[?2004\$p")
        e.feed("$esc[?1004\$p")
        e.feed("$esc[?25\$p")
        e.feed("$esc[?7\$p")
        e.feed("$esc[?9999\$p")
        e.feed("$esc[4\$p") // ANSI mode (insert), not supported

        assertEquals(
            listOf(
                "$esc[?2026;2\$y",
                "$esc[?2026;1\$y",
                "$esc[?2004;1\$y",
                "$esc[?1004;2\$y",
                "$esc[?25;1\$y",
                "$esc[?7;3\$y",
                "$esc[?9999;0\$y",
                "$esc[4;0\$y",
            ),
            got
        )
    }

    // ---- Sequences that share a final byte with older commands ----

    @Test
    fun modifyOtherKeys_isNotAColourChange() {
        val e = emulator()
        e.feed("$esc[>4;2m$esc[>4m")
        e.feed("x")
        assertEquals(0, e.buffer.screen[0].styles[0].toInt())
    }

    @Test
    fun kittyKeyboardQueries_doNotMoveTheCursor() {
        val e = emulator()
        e.feed("$esc[s") // saved at 1;1
        e.feed("$esc[3;6H")
        e.feed("$esc[?u$esc[>1u$esc[=5u$esc[<u$esc[?s")
        assertEquals(2, e.buffer.cursorRow)
        assertEquals(5, e.buffer.cursorCol)

        // The plain forms still save and restore.
        e.feed("$esc[u")
        assertEquals(0, e.buffer.cursorRow)
        assertEquals(0, e.buffer.cursorCol)
    }

    @Test
    fun intermediateBytes_doNotRunTheCommandWithTheSameFinalByte() {
        val e = emulator()
        e.feed("$esc[3;6H")
        e.feed("$esc[2 A") // SR (scroll right), not cursor up
        e.feed("$esc[1;2\$r") // DECCARA, not scroll margins (which would home the cursor)
        e.feed("$esc[?1r") // XTRESTORE, not scroll margins
        assertEquals(2, e.buffer.cursorRow)
        assertEquals(5, e.buffer.cursorCol)
    }
}
