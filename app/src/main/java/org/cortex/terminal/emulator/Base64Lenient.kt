package org.cortex.terminal.emulator

import java.io.ByteArrayOutputStream

/**
 * Small tolerant base64 decoder for terminal escape sequences (OSC 52).
 *
 * `java.util.Base64` needs API 26 and `android.util.Base64` cannot run in JVM unit tests, and
 * terminal programs are sloppy about padding and line breaks, so this accepts standard and
 * URL-safe alphabets, ignores whitespace and stops at the first `=`.
 */
internal object Base64Lenient {
    private val TABLE = IntArray(128) { -1 }.also { t ->
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        for ((i, ch) in alphabet.withIndex()) t[ch.code] = i
        t['-'.code] = 62
        t['_'.code] = 63
    }

    /** Returns the decoded bytes, or null if [input] has an invalid character or exceeds [maxBytes]. */
    fun decode(input: CharSequence, maxBytes: Int = Int.MAX_VALUE): ByteArray? {
        val out = ByteArrayOutputStream((input.length * 3) / 4 + 1)
        var acc = 0
        var bits = 0
        for (ch in input) {
            if (ch == '=') break
            if (ch == ' ' || ch == '\n' || ch == '\r' || ch == '\t') continue
            val v = if (ch.code < 128) TABLE[ch.code] else -1
            if (v < 0) return null
            acc = (acc shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((acc shr bits) and 0xFF)
                acc = acc and ((1 shl bits) - 1)
                if (out.size() > maxBytes) return null
            }
        }
        return out.toByteArray()
    }
}
