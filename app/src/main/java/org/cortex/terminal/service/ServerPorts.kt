package org.cortex.terminal.service

/** What [LocalServers] reads: the hook's reports, /proc/net/tcp and the start of an answer. */
object ServerPorts {

    /** "PID NAME" as the hook writes it in run/cortex/listening/PORT. */
    fun parseReport(text: String): Pair<Int, String?>? {
        val line = text.lineSequence().firstOrNull()?.trim() ?: return null
        val pid = line.substringBefore(' ').toIntOrNull() ?: return null
        val name = line.substringAfter(' ', "").trim().takeIf { it.isNotEmpty() && it != "?" }
        return pid to name
    }

    /** The ports of the listening TCP sockets in /proc/net/tcp or tcp6 that belong to [uids]. */
    fun parseProcNetListening(text: String, uids: Set<Int>): Set<Int> {
        val ports = mutableSetOf<Int>()
        for (line in text.lineSequence().drop(1)) {
            val fields = line.trim().split(Regex("\\s+"))
            if (fields.size < 8 || fields[3] != "0A") continue // LISTEN
            val uid = fields[7].toIntOrNull() ?: continue
            if (uid !in uids) continue
            val port = fields[1].substringAfterLast(':').toIntOrNull(16) ?: continue
            if (port > 0) ports += port
        }
        return ports
    }

    /** Whether a server's answer to a request starts as HTTP does. */
    fun isHttpResponse(start: String) = start.startsWith("HTTP/")
}
