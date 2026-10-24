package org.cortex.terminal.view

/**
 * The keys of the toolbar above the keyboard, as written in ~/.cortex/keys: one row per line (at
 * most [MAX_ROWS]), keys separated by spaces (at most [MAX_KEYS] a row), lines starting with # are
 * comments. A key is
 *  - a name: ESC TAB CTRL ALT UP DOWN LEFT RIGHT HOME END PGUP PGDN INS DEL BKSP ENTER SPACE F1-F12,
 *    or MENU (the session list), FIND (search), PASTE, KEYBOARD (shows or hides it);
 *  - text in double quotes, typed as it is ("git status\n"; \n is Enter, also \t \e \\ \");
 *  - one or more symbols, typed as they are (| ~ / - ::);
 *  - LABEL=KEY for another label on the button (gs="git status\n", ⇥=TAB).
 */
object ExtraKeysLayout {
    const val MAX_ROWS = 4
    const val MAX_KEYS = 12

    enum class Special(val label: String) {
        ESC("ESC"), TAB("⇆"), CTRL("CTRL"), ALT("ALT"),
        UP("↑"), DOWN("↓"), LEFT("←"), RIGHT("→"),
        HOME("HOME"), END("END"), PGUP("PGUP"), PGDN("PGDN"),
        INS("INS"), DEL("DEL"), BKSP("⌫"), ENTER("↵"), SPACE("␣"),
        MENU("☰"), FIND("FIND"), PASTE("PASTE"), KEYBOARD("⌨"),
        F1("F1"), F2("F2"), F3("F3"), F4("F4"), F5("F5"), F6("F6"),
        F7("F7"), F8("F8"), F9("F9"), F10("F10"), F11("F11"), F12("F12");

        /** Keys that repeat while held. */
        val repeats: Boolean get() = this == UP || this == DOWN || this == LEFT || this == RIGHT || this == BKSP || this == DEL
    }

    sealed class Key {
        abstract val label: String
    }

    data class SpecialKey(val special: Special, override val label: String = special.label) : Key()

    /** Typed as it is; "\n" is Enter. */
    data class TextKey(val text: String, override val label: String = text) : Key()

    /** The rows; none, and what is wrong (with the line number), when [error] is set. */
    data class Result(val rows: List<List<Key>>, val error: String? = null)

    /** The layout without ~/.cortex/keys; also written to ~/.cortex/keys.example. */
    val DEFAULT_TEXT = """
        |# The keys above the keyboard. Copy this file to ~/.cortex/keys and change it; Cortex uses it
        |# as soon as it is saved.
        |#
        |# One row per line (up to 4), keys separated by spaces (up to 12 a row). A key is
        |#   a name:     ESC TAB CTRL ALT UP DOWN LEFT RIGHT HOME END PGUP PGDN INS DEL BKSP ENTER
        |#               SPACE F1 ... F12, MENU (sessions), FIND (search), PASTE, KEYBOARD
        |#   text:       "git status\n" is typed as it is (\n is Enter; also \t \e \\ \")
        |#   symbols:    | ~ / - and the like are typed as they are
        |#   LABEL=KEY:  another label on the button, such as gs="git status\n" or ⇥=TAB
        |#
        |# Swiping from the left edge opens the sessions and swiping left on these keys opens the
        |# search, also without MENU and FIND.
        |
        |ESC MENU FIND HOME UP END PGUP
        |TAB CTRL ALT LEFT DOWN RIGHT PGDN
        |""".trimMargin()

    val DEFAULT_ROWS: List<List<Key>> by lazy { parse(DEFAULT_TEXT).rows }

    fun parse(text: String): Result {
        val rows = mutableListOf<List<Key>>()
        text.lines().forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val where = "line ${index + 1}"
            val tokens = try {
                tokenize(line)
            } catch (e: IllegalArgumentException) {
                return failed("$where: ${e.message}")
            }
            val keys = mutableListOf<Key>()
            for (token in tokens) {
                keys += try {
                    keyOf(token)
                } catch (e: IllegalArgumentException) {
                    return failed("$where: ${e.message}")
                }
            }
            if (keys.size > MAX_KEYS) return failed("$where: ${keys.size} keys, at most $MAX_KEYS fit in a row")
            rows += keys
        }
        if (rows.isEmpty()) return failed("no keys")
        if (rows.size > MAX_ROWS) return failed("${rows.size} rows, at most $MAX_ROWS")
        return Result(rows)
    }

    private fun failed(error: String) = Result(emptyList(), error)

    private class Token(val text: String, val quoted: Boolean, val label: String?)

    /** Splits a row at spaces; quoted text keeps its spaces and has its escapes resolved. */
    private fun tokenize(line: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < line.length) {
            if (line[i].isWhitespace()) {
                i++
                continue
            }
            // LABEL= before a key (an '=' that starts the token is a symbol key)
            var label: String? = null
            val start = i
            while (i < line.length && !line[i].isWhitespace() && line[i] != '"' && line[i] != '=') i++
            if (i < line.length && line[i] == '=' && i > start && i + 1 < line.length && !line[i + 1].isWhitespace()) {
                label = line.substring(start, i)
                i++
            } else {
                i = start
            }
            if (line[i] == '"') {
                val sb = StringBuilder()
                i++
                var closed = false
                while (i < line.length) {
                    val c = line[i]
                    if (c == '"') {
                        closed = true
                        i++
                        break
                    }
                    if (c == '\\' && i + 1 < line.length) {
                        sb.append(
                            when (val e = line[i + 1]) {
                                'n' -> '\n'
                                't' -> '\t'
                                'e' -> '\u001b'
                                'r' -> '\r'
                                '\\', '"' -> e
                                else -> throw IllegalArgumentException("unknown escape \\$e")
                            }
                        )
                        i += 2
                        continue
                    }
                    sb.append(c)
                    i++
                }
                if (!closed) throw IllegalArgumentException("a quote is not closed")
                if (sb.isEmpty()) throw IllegalArgumentException("empty text \"\"")
                if (i < line.length && !line[i].isWhitespace()) throw IllegalArgumentException("a space is missing after the quote")
                tokens += Token(sb.toString(), quoted = true, label = label)
            } else {
                val s = i
                while (i < line.length && !line[i].isWhitespace()) i++
                tokens += Token(line.substring(s, i), quoted = false, label = label)
            }
        }
        return tokens
    }

    private fun keyOf(token: Token): Key {
        val key = if (token.quoted) {
            TextKey(token.text, labelFor(token.text))
        } else {
            val special = Special.values().firstOrNull { it.name.equals(token.text, ignoreCase = true) }
            when {
                special != null -> SpecialKey(special)
                token.text.any { it.isLetterOrDigit() } && token.text.length > 1 ->
                    throw IllegalArgumentException("unknown key ${token.text} (text goes in quotes: \"${token.text}\")")
                else -> TextKey(token.text)
            }
        }
        val label = token.label ?: return key
        return when (key) {
            is SpecialKey -> key.copy(label = label)
            is TextKey -> key.copy(label = label)
        }
    }

    /** The button label of quoted text: control characters shown, long text shortened. */
    private fun labelFor(text: String): String {
        val shown = text.replace("\n", "↵").replace("\t", "⇆").replace("\u001b", "⎋").replace("\r", "↵")
        return if (shown.length > 6) shown.take(5) + "…" else shown
    }
}
