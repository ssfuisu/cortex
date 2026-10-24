package org.cortex.terminal.session

import org.cortex.terminal.runtime.FileInstaller
import java.io.File

/**
 * The sessions as they were when Cortex last kept them (each change, and when it went to the
 * background), so that they come back after Android ended the app: their directories and the end
 * of their output. One file, replaced in one step; Cortex's own exit leaves none.
 */
class SessionStore(private val file: File) {

    /** [directory]: where the shell was, null when unknown. [output]: the end of what it showed. */
    data class Saved(val directory: String?, val output: String)

    data class State(val sessions: List<Saved>, val current: Int)

    fun save(state: State) {
        val sb = StringBuilder(HEADER).append('\n')
        sb.append("current ").append(state.current).append('\n')
        for (saved in state.sessions) {
            sb.append("session\n")
            saved.directory?.let { sb.append("directory ").append(escape(it)).append('\n') }
            sb.append("output ").append(escape(saved.output)).append('\n')
        }
        FileInstaller.install(file, sb.toString(), 384) // 0600
    }

    /** The kept sessions; null without any (or when the file is from another version or damaged). */
    fun load(): State? {
        val lines = try {
            if (!file.isFile) return null
            file.readLines()
        } catch (e: Exception) {
            return null
        }
        if (lines.firstOrNull() != HEADER) return null
        var current = 0
        val sessions = mutableListOf<Saved>()
        var directory: String? = null
        var output: String? = null
        var inSession = false
        fun finish() {
            if (inSession) sessions += Saved(directory, output ?: "")
            directory = null
            output = null
        }
        for (line in lines.drop(1)) {
            when {
                line.startsWith("current ") -> current = line.removePrefix("current ").toIntOrNull() ?: 0
                line == "session" -> {
                    finish()
                    inSession = true
                }
                line.startsWith("directory ") -> directory = unescape(line.removePrefix("directory "))
                line.startsWith("output ") -> output = unescape(line.removePrefix("output "))
                line.isEmpty() -> {}
                else -> return null
            }
        }
        finish()
        if (sessions.isEmpty()) return null
        return State(sessions, current.coerceIn(0, sessions.size - 1))
    }

    fun clear() {
        file.delete()
    }

    companion object {
        private const val HEADER = "cortex-sessions 1"

        /** At most this much output is kept of each session. */
        const val MAX_LINES = 300
        const val MAX_CHARS = 64 * 1024

        /** The end of [output] that is kept: [MAX_CHARS] at most, starting at a line. */
        fun trimOutput(output: String): String {
            if (output.length <= MAX_CHARS) return output
            val tail = output.substring(output.length - MAX_CHARS)
            val nl = tail.indexOf('\n')
            return if (nl >= 0) tail.substring(nl + 1) else tail
        }

        private fun escape(s: String): String {
            val sb = StringBuilder(s.length + 16)
            for (c in s) {
                when (c) {
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    else -> sb.append(c)
                }
            }
            return sb.toString()
        }

        private fun unescape(s: String): String {
            val sb = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    when (s[i + 1]) {
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        else -> sb.append(s[i + 1])
                    }
                    i += 2
                } else {
                    sb.append(c)
                    i++
                }
            }
            return sb.toString()
        }
    }
}
