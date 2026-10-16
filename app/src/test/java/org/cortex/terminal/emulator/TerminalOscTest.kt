package org.cortex.terminal.emulator

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class TerminalOscTest {

    private val esc = "\u001b"
    private val bel = "\u0007"
    private val st = "\u001b\\"

    private fun emulator() = TerminalEmulator(rows = 5, cols = 80)

    private fun TerminalEmulator.feed(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        processInput(bytes, 0, bytes.size)
    }

    private fun b64(text: String) = Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

    // ---- Base64Lenient ----

    @Test
    fun base64_decodesStandardUrlSafeAndUnpadded() {
        assertArrayEquals("hello".toByteArray(), Base64Lenient.decode("aGVsbG8="))
        assertArrayEquals("hello".toByteArray(), Base64Lenient.decode("aGVsbG8"))
        assertArrayEquals("hello world".toByteArray(), Base64Lenient.decode("aGVsbG8g\nd29ybGQ="))
        assertArrayEquals(byteArrayOf(0xfb.toByte(), 0xff.toByte()), Base64Lenient.decode("-_8"))
        assertArrayEquals(byteArrayOf(0xfb.toByte(), 0xff.toByte()), Base64Lenient.decode("+/8="))
    }

    @Test
    fun base64_rejectsGarbageAndOversize() {
        assertNull(Base64Lenient.decode("not base64!"))
        assertNull(Base64Lenient.decode("aGVsbG8=", maxBytes = 3))
        assertEquals(0, Base64Lenient.decode("")!!.size)
    }

    // ---- OSC 52 clipboard ----

    @Test
    fun osc52_setsClipboardWithBelAndStTerminators() {
        val e = emulator()
        val got = mutableListOf<String>()
        e.onClipboardSet = { got.add(it) }

        e.feed("${esc}]52;c;${b64("first ünicode ✓")}$bel")
        e.feed("${esc}]52;;${b64("second")}$st")

        assertEquals(listOf("first ünicode ✓", "second"), got)
    }

    @Test
    fun osc52_neverAnswersClipboardQueriesAndIgnoresBadPayloads() {
        val e = emulator()
        var called = 0
        e.onClipboardSet = { called++ }

        e.feed("${esc}]52;c;?$bel")
        e.feed("${esc}]52;c;$bel")
        e.feed("${esc}]52;c;***not-base64***$bel")
        e.feed("${esc}]52;$bel")

        assertEquals(0, called)
    }

    @Test
    fun osc52_acceptsLargePayloadsButDropsOversizedOnes() {
        val e = emulator()
        val got = mutableListOf<String>()
        e.onClipboardSet = { got.add(it) }

        val big = "x".repeat(100_000) // far above the generic 8 KiB OSC limit
        e.feed("${esc}]52;c;${b64(big)}$bel")
        assertEquals(1, got.size)
        assertEquals(big.length, got[0].length)

        val tooBig = "y".repeat(300_000)
        e.feed("${esc}]52;c;${b64(tooBig)}$bel")
        assertEquals("an oversized clipboard payload must be dropped, not truncated", 1, got.size)

        // the stream is still in sync afterwards
        e.feed("ok")
        assertEquals("ok", e.buffer.screen[e.buffer.cursorRow].getText())
    }

    @Test
    fun osc52_overflowDoesNotLeakIntoTheNextSequence() {
        val e = emulator()
        val got = mutableListOf<String>()
        e.onClipboardSet = { got.add(it) }

        e.feed("${esc}]52;c;${"A".repeat(400_000)}$bel")
        e.feed("${esc}]52;c;${b64("after")}$bel")

        assertEquals(listOf("after"), got)
    }

    // ---- window title keeps working ----

    @Test
    fun osc0And2_stillSetTheTitle() {
        val e = emulator()
        val titles = mutableListOf<String>()
        e.onTitleChange = { titles.add(it) }

        e.feed("${esc}]0;one$bel${esc}]2;two; with semicolon$st")

        assertEquals(listOf("one", "two; with semicolon"), titles)
    }

    // ---- OSC 8 hyperlinks ----

    @Test
    fun osc8_linksOnlyTheTextBetweenOpenAndClose() {
        val e = emulator()
        e.feed("a${esc}]8;;https://example.com/x?a=1;b=2$st" + "link" + "${esc}]8;;$st" + "z")
        val row = e.buffer.screen[0]

        assertNull(row.linkAt(0))
        for (c in 1..4) assertEquals("https://example.com/x?a=1;b=2", row.linkAt(c))
        assertNull(row.linkAt(5))
        assertFalse(row.hasLinkAt(5))
        assertEquals("alinkz", row.getText())
    }

    @Test
    fun osc8_acceptsParamsAndRejectsUnsafeTargets() {
        val e = emulator()
        e.feed("${esc}]8;id=abc;https://a.example$bel" + "A" + "${esc}]8;;$bel")
        assertEquals("https://a.example", e.buffer.screen[0].linkAt(0))

        val e2 = emulator()
        e2.feed("${esc}]8;;https://a.example/\u0001evil$bel" + "B")
        assertNull("control characters in a target disable the link", e2.buffer.screen[0].linkAt(0))

        val e3 = emulator()
        e3.feed("${esc}]8;;https://a.example/${"x".repeat(5000)}$bel" + "C")
        assertNull("over-long targets are ignored", e3.buffer.screen[0].linkAt(0))
    }

    @Test
    fun osc8_cellsOfOneLinkShareOneStringAndRowsWithoutLinksStayLean() {
        val e = emulator()
        e.feed("plain\r\n${esc}]8;;https://example.com$st" + "abc" + "${esc}]8;;$st")

        assertNull("rows that never had a link allocate nothing", e.buffer.screen[0].links)
        val links = e.buffer.screen[1].links
        assertNotNull(links)
        assertTrue(links!![0] === links[1] && links[1] === links[2])
    }

    @Test
    fun osc8_linkSurvivesScrollingAndClearsWithTheRow() {
        val e = TerminalEmulator(rows = 3, cols = 20)
        e.feed("${esc}]8;;https://example.com$st" + "link" + "${esc}]8;;$st")
        e.feed("\r\n\r\n\r\n\r\n") // scroll the linked row into history
        val historyRow = e.buffer.history.first
        assertEquals("https://example.com", historyRow.linkAt(0))

        val row = TerminalRow(10)
        row.setChar(0, 'x', 1, 2, 0, "https://example.com")
        row.clear()
        assertNull(row.linkAt(0))
    }

    @Test
    fun osc8_deleteAndInsertCharsMoveLinksWithTheirText() {
        val row = TerminalRow(6)
        "abcdef".forEachIndexed { i, ch -> row.setChar(i, ch, 1, 2, 0, if (i >= 3) "L" else null) }

        row.deleteChars(1, 2) // "abcdef" -> "adef  ", links on d,e,f
        assertEquals("adef", row.getText())
        assertNull(row.linkAt(0))
        assertEquals("L", row.linkAt(1))
        assertEquals("L", row.linkAt(3))
        assertNull(row.linkAt(4))

        row.insertChars(1, 1) // "adef" -> "a def", the link moves right with its text
        assertNull(row.linkAt(1))
        assertEquals("L", row.linkAt(2))
        assertEquals("L", row.linkAt(4))
    }

    @Test
    fun fullResetEndsAnOpenLink() {
        val e = emulator()
        e.feed("${esc}]8;;https://example.com$st${esc}c" + "after")
        assertNull(e.buffer.screen[0].linkAt(0))
    }

    // ---- OSC 133 command timing ----

    @Test
    fun osc133_reportsDurationAndExitCode() {
        val e = emulator()
        var now = 1_000L
        e.monotonicMs = { now }
        val results = mutableListOf<Pair<Long, Int?>>()
        e.onCommandFinished = { ms, code -> results.add(ms to code) }

        e.feed("${esc}]133;C$bel")
        now += 61_500L
        e.feed("${esc}]133;D;2$bel")

        assertEquals(listOf(61_500L to 2), results)
    }

    @Test
    fun osc133_ignoresPromptsWithoutACommandAndReportsMissingExitCode() {
        val e = emulator()
        var now = 0L
        e.monotonicMs = { now }
        val results = mutableListOf<Pair<Long, Int?>>()
        e.onCommandFinished = { ms, code -> results.add(ms to code) }

        e.feed("${esc}]133;D;0$bel") // first prompt of a session, nothing ran
        e.feed("${esc}]133;A$bel${esc}]133;B$bel")
        assertTrue(results.isEmpty())

        e.feed("${esc}]133;C$bel")
        now = 5L
        e.feed("${esc}]133;D$st")
        assertEquals(listOf(5L to null), results)

        now = 100L
        e.feed("${esc}]133;D;0$bel") // a second D without a new C must not fire again
        assertEquals(1, results.size)
    }

    @Test
    fun osc133_endOfCommandClosesAStrayHyperlink() {
        val e = emulator()
        e.feed("${esc}]8;;https://example.com$st" + "unterminated" + "${esc}]133;D;0$bel" + "next")
        assertEquals("https://example.com", e.buffer.screen[0].linkAt(0))
        assertNull(e.buffer.screen[0].linkAt(12))
    }

    @Test
    fun unknownOscSequencesAreStillIgnored() {
        val e = emulator()
        e.feed("${esc}]777;notify;title;body$bel${esc}]9;4;1;50$st" + "text")
        assertEquals("text", e.buffer.screen[0].getText())
    }
}
