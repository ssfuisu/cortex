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

    /**
     * The first port of the kernel's range for the ports it picks itself, from the text of
     * /proc/sys/net/ipv4/ip_local_port_range ("32768\t60999"); 32768, Linux's default, without one.
     */
    fun ephemeralLow(range: String?): Int =
        range?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.toIntOrNull()?.takeIf { it in 1..65535 } ?: 32768

    /**
     * Whether a listening port is worth asking for web pages: one below the kernel's own range.
     * Programs that talk to each other over TCP (Gradle's daemon, a JVM's debugger) let the kernel
     * pick the port, and often accept just one connection, which the question would take (#51).
     */
    fun mayServeWebPages(port: Int, ephemeralLow: Int) = port in 1 until ephemeralLow

    /** Whether a server's answer to a request starts as HTTP does. */
    fun isHttpResponse(start: String) = start.startsWith("HTTP/")
}
