package org.cortex.terminal.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerPortsTest {

    @Test
    fun theHooksReport() {
        assertEquals(1234 to "node", ServerPorts.parseReport("1234 node\n"))
        assertEquals("a name with spaces", 7 to "python3 -m", ServerPorts.parseReport("7 python3 -m\n"))
        assertEquals("no name", 7 to null, ServerPorts.parseReport("7 ?\n"))
        assertNull(ServerPorts.parseReport(""))
        assertNull(ServerPorts.parseReport("node 1234"))
    }

    @Test
    fun listeningSocketsOfCortexInProcNetTcp() {
        val tcp = """
            |  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
            |   0: 0100007F:0BB8 00000000:0000 0A 00000000:00000000 00:00000000 00000000 10123        0 4321 1 0000000000000000 100 0 0 10 0
            |   1: 00000000:1F90 00000000:0000 0A 00000000:00000000 00:00000000 00000000 10123        0 4322 1 0000000000000000 100 0 0 10 0
            |   2: 0100007F:13AD 00000000:0000 0A 00000000:00000000 00:00000000 00000000  1000        0 4323 1 0000000000000000 100 0 0 10 0
            |   3: 0100007F:0BB8 0100007F:C350 01 00000000:00000000 00:00000000 00000000 10123        0 4324 1 0000000000000000 20 4 30 10 -1
            |""".trimMargin()
        assertEquals("ports 3000 and 8080 of the app; another app's and a connection are not", setOf(3000, 8080),
            ServerPorts.parseProcNetListening(tcp, setOf(10123)))
        assertEquals("root mode's", setOf(5037), ServerPorts.parseProcNetListening(tcp, setOf(1000)))
        val tcp6 = """
            |  sl  local_address                         remote_address                        st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
            |   0: 00000000000000000000000001000000:1538 00000000000000000000000000000000:0000 0A 00000000:00000000 00:00000000 00000000 10123        0 5555 1 0000000000000000 100 0 0 10 0
            |""".trimMargin()
        assertEquals(setOf(5432), ServerPorts.parseProcNetListening(tcp6, setOf(10123)))
        assertTrue(ServerPorts.parseProcNetListening("", setOf(10123)).isEmpty())
    }

    @Test
    fun anHttpAnswer() {
        assertTrue(ServerPorts.isHttpResponse("HTTP/1.0"))
        assertTrue(ServerPorts.isHttpResponse("HTTP/1.1 "))
        assertFalse("ssh", ServerPorts.isHttpResponse("SSH-2.0-"))
        assertFalse(ServerPorts.isHttpResponse(""))
    }
}
