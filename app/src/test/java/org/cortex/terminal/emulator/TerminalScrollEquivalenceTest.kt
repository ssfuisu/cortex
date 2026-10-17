package org.cortex.terminal.emulator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.Random
import java.util.zip.CRC32

/**
 * Scrolling moves row objects instead of copying their contents (and reuses the oldest history row
 * once the scrollback is full). Nothing a user can see may change because of that, so this feeds
 * reproducible pseudo-random output through the emulator (text, colours, scroll regions, insert and
 * delete line, alternate screen, resizes, hyperlinks, odd chunking) and compares a checksum of the
 * complete state with the checksums the copying implementation produced.
 */
class TerminalScrollEquivalenceTest {

    private val esc = "\u001b"

    /** A reproducible stream of terminal output and resize requests; one entry per step. */
    private fun run(seed: Long): Long {
        val rnd = Random(seed)
        var rows = 5 + rnd.nextInt(8)
        var cols = 10 + rnd.nextInt(21)
        val emu = TerminalEmulator(rows = rows, cols = cols)
        val words = listOf("alpha", "beta", "gamma", "Türkçe", "şğüöçı", "日本", "x", "cortex", "  ", "0123456789", "~", "ünï")

        fun feed(text: String) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            var p = 0
            while (p < bytes.size) { // split at arbitrary points, also inside multi-byte characters
                val n = minOf(1 + rnd.nextInt(24), bytes.size - p)
                emu.processInput(bytes, p, n)
                p += n
            }
        }

        repeat(700) {
            when (rnd.nextInt(24)) {
                0, 1, 2, 3, 4 -> {
                    val sb = StringBuilder()
                    repeat(1 + rnd.nextInt(8)) { sb.append(words[rnd.nextInt(words.size)]).append(' ') }
                    feed(sb.toString())
                }
                5, 6, 7 -> feed("\r\n".repeat(1 + rnd.nextInt(4)))
                8 -> feed("$esc[${listOf("0", "1", "4", "7", "31", "32;1", "44", "38;5;${rnd.nextInt(256)}", "48;2;${rnd.nextInt(256)};9;200").random(rnd)}m")
                9 -> feed("$esc[${1 + rnd.nextInt(3)}${listOf("L", "M", "S", "T").random(rnd)}")
                10 -> {
                    val top = 1 + rnd.nextInt(rows)
                    val bottom = top + rnd.nextInt(rows - top + 1)
                    feed("$esc[$top;${bottom}r")
                }
                11 -> feed("$esc[r")
                12 -> feed("$esc[${1 + rnd.nextInt(rows)};${1 + rnd.nextInt(cols)}H")
                13 -> feed("$esc[${1 + rnd.nextInt(4)}${listOf("A", "B", "C", "D").random(rnd)}")
                14 -> feed("$esc[${rnd.nextInt(3)}${listOf("J", "K").random(rnd)}")
                15 -> feed(listOf("${esc}M", "${esc}D", "${esc}E").random(rnd))
                16 -> feed(if (rnd.nextBoolean()) "$esc[?1049h" else "$esc[?1049l")
                17 -> {
                    rows = 4 + rnd.nextInt(11)
                    cols = 8 + rnd.nextInt(23)
                    emu.resize(rows, cols)
                }
                18 -> feed("$esc]8;;https://example.com/${rnd.nextInt(100)}$esc\\link$esc]8;;$esc\\")
                19 -> feed("${esc}[2J${esc}[H")
                20 -> feed("\r${esc}[2K${words[rnd.nextInt(words.size)]}")
                // a long burst of output, so that the 5000-line scrollback fills up and its oldest rows get reused
                21 -> repeat(100 + rnd.nextInt(300)) { feed("burst ${rnd.nextInt(1000)} ${words[rnd.nextInt(words.size)]}\r\n") }
                else -> feed("\r\n" + words[rnd.nextInt(words.size)].repeat(1 + rnd.nextInt(6)))
            }
        }
        return digest(emu)
    }

    private fun <T> List<T>.random(rnd: Random): T = this[rnd.nextInt(size)]

    private fun digest(emu: TerminalEmulator): Long {
        val crc = CRC32()
        fun put(v: Int) {
            crc.update(v and 0xff); crc.update((v shr 8) and 0xff); crc.update((v shr 16) and 0xff); crc.update((v shr 24) and 0xff)
        }
        fun row(r: TerminalRow) {
            put(r.cols)
            for (c in 0 until r.cols) {
                put(r.chars[c].code); put(r.fgColors[c]); put(r.bgColors[c]); put(r.styles[c].toInt())
                put(if (r.isSearchMatch[c]) 1 else 0); put(if (r.isUrlLink[c]) 1 else 0)
                put(r.linkAt(c)?.hashCode() ?: 0)
            }
            put(if (r.isWrapped) 1 else 0)
        }
        val b = emu.buffer
        put(b.rows); put(b.cols); put(b.cursorRow); put(b.cursorCol); put(b.scrollTop); put(b.scrollBottom)
        put(if (b.isAlternate) 1 else 0); put(if (b.isWrapPending) 1 else 0)
        put(b.history.size)
        for (r in b.history) row(r)
        for (r in b.screen) row(r)
        return crc.value
    }

    @Test
    fun scrollingGivesTheSameStateAsCopyingRows() {
        // Produced by the implementation that copied rows (v1.26.29) for seeds 1..40.
        val expected = longArrayOf(
            2416632353L, 854164187L, 167735230L, 1770459094L, 2766382294L,
            3148001545L, 443829834L, 4097107297L, 3590311930L, 3314442819L,
            2797212869L, 708426361L, 2552205032L, 2685958924L, 3034641577L,
            2723360390L, 2419922855L, 56669105L, 1224857635L, 498953102L,
            3737029665L, 393568550L, 1011475602L, 947428879L, 800373509L,
            2036938186L, 565521298L, 186694172L, 1527861604L, 2735398206L,
            1623489909L, 1742272634L, 3540384490L, 2927353393L, 558435819L,
            799742564L, 288890457L, 1832347724L, 1352931504L, 3089825385L,
        )
        val actual = LongArray(expected.size) { run(it + 1L) }
        for (i in expected.indices) {
            assertEquals("seed ${i + 1}: state after the stream differs from the copying implementation", expected[i], actual[i])
        }
        assertEquals("every seed must give a different state, or the streams test nothing", expected.size, actual.toSet().size)
    }

    @Test
    fun theScrollbackFillsUpAndKeepsItsLimit() {
        // guards the test above: its streams must reach the point where the oldest history row is reused
        val rnd = Random(3)
        val emu = TerminalEmulator(rows = 8, cols = 20)
        val text = StringBuilder()
        repeat(6000) { text.append("line ").append(rnd.nextInt(1000)).append("\r\n") }
        val bytes = text.toString().toByteArray()
        emu.processInput(bytes, 0, bytes.size)
        assertEquals(5000, emu.buffer.history.size)
    }

    @Test
    fun theStreamsAreReproducible() {
        assertEquals(run(7), run(7))
        assertNotEquals(run(7), run(8))
    }

    /** Used once, against the old implementation, to produce the expected values above. */
    fun printGolden() {
        println((1..40).joinToString(", ") { "${run(it.toLong())}L" })
    }
}
