package org.cortex.terminal.emulator

object TerminalSearch {
    private val URL_REGEX = Regex(
        "(https?://|ftp://|file://|www\\.)[^\\s\\u0000-\\u0020<>\"'`(){}\\[\\]]+",
        RegexOption.IGNORE_CASE
    )

    /** A link in [row] (buffer row number), found in [rowRef]. */
    data class UrlHit(val row: Int, val startCol: Int, val endCol: Int, val url: String, val rowRef: TerminalRow? = null)

    fun findAll(buffer: TerminalBuffer, query: String, ignoreCase: Boolean = true): List<TerminalBuffer.SearchHit> {
        val out = ArrayList<TerminalBuffer.SearchHit>()
        if (query.isEmpty()) return out
        val needle = if (ignoreCase) query.lowercase() else query
        for ((rowIdx, row) in buffer.allRowsWithIndex()) {
            val text = row.getText()
            if (text.isEmpty()) continue
            val hay = if (ignoreCase) text.lowercase() else text
            var from = 0
            while (true) {
                val found = hay.indexOf(needle, from)
                if (found < 0) break
                out.add(TerminalBuffer.SearchHit(rowIdx, found, found + needle.length - 1))
                from = found + maxOf(1, needle.length)
                if (out.size >= 2000) return out
            }
        }
        return out
    }

    /**
     * Scans only the tail of the buffer (newest [maxRows] rows) so URL detection
     * stays cheap even when invoked from the PTY reader thread.
     */
    fun findUrls(buffer: TerminalBuffer, maxRows: Int = 300): List<UrlHit> {
        val out = ArrayList<UrlHit>()
        // Only the newest rows: listing the whole scrollback (thousands of rows) for them on every
        // refresh during output was most of the cost.
        for ((rowIdx, row) in buffer.lastRowsWithIndex(maxRows)) {
            val text = row.getText()
            if (text.isEmpty()) continue
            for (m in URL_REGEX.findAll(text)) {
                val url = m.value.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '\'', '"')
                if (url.isEmpty()) continue
                val endCol = m.range.first + url.length - 1
                out.add(UrlHit(rowIdx, m.range.first, endCol, url, row))
                if (out.size >= 400) return out
            }
        }
        return out
    }

    fun normalizeUrl(raw: String): String {
        var u = raw.trim()
        if (u.startsWith("www.", ignoreCase = true)) u = "https://$u"
        return u
    }
}
