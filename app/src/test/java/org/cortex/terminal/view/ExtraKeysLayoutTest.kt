package org.cortex.terminal.view

import org.cortex.terminal.view.ExtraKeysLayout.Special
import org.cortex.terminal.view.ExtraKeysLayout.SpecialKey
import org.cortex.terminal.view.ExtraKeysLayout.TextKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtraKeysLayoutTest {

    @Test
    fun theDefaultIsTheTwoRowsCortexHadBefore() {
        val result = ExtraKeysLayout.parse(ExtraKeysLayout.DEFAULT_TEXT)
        assertNull(result.error)
        assertEquals(
            listOf(listOf("ESC", "☰", "FIND", "HOME", "↑", "END", "PGUP"), listOf("⇆", "CTRL", "ALT", "←", "↓", "→", "PGDN")),
            result.rows.map { row -> row.map { it.label } }
        )
        assertEquals(result.rows, ExtraKeysLayout.DEFAULT_ROWS)
    }

    @Test
    fun namesSymbolsTextAndLabels() {
        val result = ExtraKeysLayout.parse("""
            # my keys
            esc Ctrl | ~ / -  ::  F5 bksp
            gs="git status\n" ⇥=TAB "a b" "\e:wq\n" = "say \"hi\""
        """.trimIndent())
        assertNull(result.error)
        assertEquals(
            listOf(
                SpecialKey(Special.ESC), SpecialKey(Special.CTRL), TextKey("|"), TextKey("~"), TextKey("/"),
                TextKey("-"), TextKey("::"), SpecialKey(Special.F5), SpecialKey(Special.BKSP)
            ),
            result.rows[0]
        )
        assertEquals(
            listOf(
                TextKey("git status\n", "gs"), SpecialKey(Special.TAB, "⇥"), TextKey("a b"),
                TextKey("\u001b:wq\n", "⎋:wq↵"), TextKey("="), TextKey("say \"hi\"", "say \"…")
            ),
            result.rows[1]
        )
    }

    @Test
    fun mistakesAreReportedWithTheLine() {
        fun error(text: String) = ExtraKeysLayout.parse(text).also { assertTrue(it.rows.isEmpty()) }.error ?: ""
        assertTrue(error("ESC\nUPP DOWN").startsWith("line 2: unknown key UPP"))
        assertTrue(error("\"open").contains("not closed"))
        assertTrue(error("\"\\x\"").contains("unknown escape"))
        assertTrue(error("ESC ".repeat(13)).contains("at most 12"))
        assertTrue(error("A\nB\nC\nD\nE".lowercase().replace(Regex("[a-e]")) { "ESC" }).contains("5 rows"))
        assertEquals("no keys", error("# nothing\n\n"))
        assertTrue(error("\"x\"y").contains("space is missing"))
    }

    @Test
    fun repeatingKeys() {
        assertTrue(Special.LEFT.repeats && Special.BKSP.repeats)
        assertTrue(!Special.ESC.repeats && !Special.CTRL.repeats)
    }
}
