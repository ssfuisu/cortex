package org.cortex.terminal.runtime

import android.content.Context
import android.content.ContextWrapper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files

class UrlOpenerServerTest {

    private lateinit var tempFilesDir: File
    private lateinit var fakeContext: Context

    private class TestContext(private val dir: File) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = dir
    }

    @Before
    fun setUp() {
        UrlOpenerServer.stop()
        UrlOpenerServer.authToken = null
        UrlOpenerServer.clientSoTimeoutMs = 5000
        tempFilesDir = Files.createTempDirectory("cortex_url_opener_test").toFile()
        fakeContext = TestContext(tempFilesDir)
    }

    @After
    fun tearDown() {
        UrlOpenerServer.stop()
        UrlOpenerServer.authToken = null
        UrlOpenerServer.clientSoTimeoutMs = 5000
        tempFilesDir.deleteRecursively()
    }

    @Test
    fun testTokensMatchConstantTimeEqualityAndEdgeCases() {
        val token = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        assertTrue(UrlOpenerServer.tokensMatch(token, token))

        // Single-bit / single-char mismatch of identical length
        val mismatchSameLen = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdee"
        assertFalse(UrlOpenerServer.tokensMatch(mismatchSameLen, token))

        // Different length mismatch
        assertFalse(UrlOpenerServer.tokensMatch(token.substring(0, 32), token))
        assertFalse(UrlOpenerServer.tokensMatch("$token-extra", token))

        // Null and empty inputs must always return false
        assertFalse(UrlOpenerServer.tokensMatch(null, token))
        assertFalse(UrlOpenerServer.tokensMatch("", token))
        assertFalse(UrlOpenerServer.tokensMatch(token, ""))
        assertFalse(UrlOpenerServer.tokensMatch("", ""))

        // Unicode / multi-byte UTF-8 inputs
        val unicodeToken = "cortex-🔐-token-ğüşiöç-2026"
        assertTrue(UrlOpenerServer.tokensMatch(unicodeToken, unicodeToken))
        assertFalse(UrlOpenerServer.tokensMatch("cortex-🔓-token-ğüşiöç-2026", unicodeToken))
    }

    @Test
    fun testUrlSchemeValidationAllowsHttpAndHttpsOnly() {
        // Allowed schemes
        assertEquals(
            "https://example.com/auth?code=123",
            UrlOpenerServer.normalizeAndValidateUrl("https://example.com/auth?code=123")
        )
        assertEquals(
            "http://127.0.0.1:8080/callback",
            UrlOpenerServer.normalizeAndValidateUrl("http://127.0.0.1:8080/callback")
        )
        assertEquals(
            "https://github.com/login/device",
            UrlOpenerServer.normalizeAndValidateUrl("github.com/login/device")
        )
        assertTrue(UrlOpenerServer.openUrlInBrowser(fakeContext, "https://example.com"))
        assertTrue(UrlOpenerServer.openUrlInBrowser(fakeContext, "http://example.com/test"))

        // Disallowed schemes: javascript:, file:, intent:, content:, ftp:, data:
        assertNull(UrlOpenerServer.normalizeAndValidateUrl("javascript:alert(1)"))
        assertNull(UrlOpenerServer.normalizeAndValidateUrl("javascript://example.com/%0Aalert(1)"))
        assertNull(UrlOpenerServer.normalizeAndValidateUrl("JAVASCRIPT:alert(document.cookie)"))
        assertNull(UrlOpenerServer.normalizeAndValidateUrl("file:///etc/passwd"))
        assertNull(UrlOpenerServer.normalizeAndValidateUrl("file:/data/data/org.cortex.terminal/files/cortex_url_token"))
        assertNull(UrlOpenerServer.normalizeAndValidateUrl("intent://scan/#Intent;scheme=zxing;package=com.example;end"))
        assertNull(UrlOpenerServer.normalizeAndValidateUrl("intent:#Intent;action=android.intent.action.VIEW;end"))
        assertNull(UrlOpenerServer.normalizeAndValidateUrl("content://com.android.contacts/contacts"))
        assertNull(UrlOpenerServer.normalizeAndValidateUrl("ftp://ftp.example.com/pub"))
        assertNull(UrlOpenerServer.normalizeAndValidateUrl("data:text/html,<script>alert(1)</script>"))
        assertNull(UrlOpenerServer.normalizeAndValidateUrl(""))
        assertNull(UrlOpenerServer.normalizeAndValidateUrl("   "))

        assertFalse(UrlOpenerServer.openUrlInBrowser(fakeContext, "javascript:alert(1)"))
        assertFalse(UrlOpenerServer.openUrlInBrowser(fakeContext, "file:///etc/passwd"))
        assertFalse(UrlOpenerServer.openUrlInBrowser(fakeContext, "intent://example/#Intent;end"))
    }

    @Test
    fun testBoundedWorkerPoolHandlesStalledConnectionsWithoutStarvingValidRequests() {
        UrlOpenerServer.clientSoTimeoutMs = 300
        val token = UrlOpenerServer.getOrCreateToken(fakeContext)
        assertNotNull(token)
        assertEquals(64, token.length)

        UrlOpenerServer.start(fakeContext, port = 0)
        val port = UrlOpenerServer.activePort
        assertTrue("Expected bound ephemeral port > 0, got $port", port > 0)

        val stalledSockets = mutableListOf<Socket>()
        try {
            // 1. Open 3 concurrent stalled connections that send no data (exceeding old corePoolSize=2).
            // With corePoolSize=4, maximumPoolSize=4 and allowCoreThreadTimeOut(true), the 4th worker
            // serves a valid authenticated request immediately without waiting for clientSoTimeoutMs.
            UrlOpenerServer.clientSoTimeoutMs = 2500
            repeat(3) {
                val s = Socket()
                s.connect(InetSocketAddress("127.0.0.1", port), 1000)
                stalledSockets.add(s)
            }
            // Give the accept loop a moment to dispatch the 3 stalled connections to workers
            Thread.sleep(50)

            Socket().use { client ->
                client.soTimeout = 600
                client.connect(InetSocketAddress("127.0.0.1", port), 1000)
                client.getOutputStream().write("OPEN $token https://example.com/active\n".toByteArray(Charsets.UTF_8))
                client.getOutputStream().flush()
                val resp = client.getInputStream().bufferedReader(Charsets.UTF_8).readLine()
                assertEquals("OK", resp)
            }

            UrlOpenerServer.clientSoTimeoutMs = 300
            stalledSockets.forEach {
                try { it.close() } catch (_: Exception) {}
            }
            stalledSockets.clear()

            // 2. Flood the server with 25 additional stalled connections (exceeding 4 max threads + 16 queue capacity).
            // Excess connections are rejected and closed immediately without stalling the accept thread.
            repeat(25) {
                try {
                    val s = Socket()
                    s.connect(InetSocketAddress("127.0.0.1", port), 1000)
                    stalledSockets.add(s)
                } catch (_: Exception) {
                    // Connection refused/reset under overflow is acceptable
                }
            }

            // Wait briefly for the 300ms socket read timeout to evict stalled workers
            Thread.sleep(750)

            // 3. Verify valid authenticated request succeeds after flood
            Socket().use { client ->
                client.soTimeout = 2000
                client.connect(InetSocketAddress("127.0.0.1", port), 1000)
                client.getOutputStream().write("OPEN $token https://example.com/recovered\n".toByteArray(Charsets.UTF_8))
                client.getOutputStream().flush()
                val resp = client.getInputStream().bufferedReader(Charsets.UTF_8).readLine()
                assertEquals("OK", resp)
            }

            // 4. Verify invalid token is rejected with ERR unauthorized
            Socket().use { client ->
                client.soTimeout = 2000
                client.connect(InetSocketAddress("127.0.0.1", port), 1000)
                client.getOutputStream().write("OPEN wrong_token https://example.com\n".toByteArray(Charsets.UTF_8))
                client.getOutputStream().flush()
                val resp = client.getInputStream().bufferedReader(Charsets.UTF_8).readLine()
                assertTrue("Expected ERR unauthorized, got: $resp", resp != null && resp.startsWith("ERR unauthorized"))
            }

            // 5. Verify disallowed scheme over authenticated socket returns ERR
            Socket().use { client ->
                client.soTimeout = 2000
                client.connect(InetSocketAddress("127.0.0.1", port), 1000)
                client.getOutputStream().write("OPEN $token javascript:alert(1)\n".toByteArray(Charsets.UTF_8))
                client.getOutputStream().flush()
                val resp = client.getInputStream().bufferedReader(Charsets.UTF_8).readLine()
                assertEquals("ERR failed to open URL", resp)
            }
        } finally {
            stalledSockets.forEach {
                try { it.close() } catch (_: Exception) {}
            }
        }

        // 6. Verify stop() and start() can be called repeatedly
        UrlOpenerServer.stop()
        UrlOpenerServer.start(fakeContext, port = 0)
        val secondPort = UrlOpenerServer.activePort
        assertTrue(secondPort > 0)
        Socket().use { client ->
            client.soTimeout = 2000
            client.connect(InetSocketAddress("127.0.0.1", secondPort), 1000)
            client.getOutputStream().write("OPEN $token https://example.com/restarted\n".toByteArray(Charsets.UTF_8))
            client.getOutputStream().flush()
            val resp = client.getInputStream().bufferedReader(Charsets.UTF_8).readLine()
            assertEquals("OK", resp)
        }
    }

    @Test
    fun testStartRecoversCleanlyAfterBindFailureAndLifecycleRaces() {
        val token = UrlOpenerServer.getOrCreateToken(fakeContext)
        // Occupy an ephemeral port on 127.0.0.1 so UrlOpenerServer.start() fails to bind
        java.net.ServerSocket().use { blocker ->
            blocker.reuseAddress = true
            blocker.bind(InetSocketAddress("127.0.0.1", 0))
            val occupiedPort = blocker.localPort

            UrlOpenerServer.start(fakeContext, port = occupiedPort)
            // Wait briefly for the failed accept thread's finally block to clean up
            Thread.sleep(50)
            assertEquals("activePort must be -1 after bind failure", -1, UrlOpenerServer.activePort)
        }

        // Subsequent start() must NOT be wedged by stale isRunning=true
        UrlOpenerServer.start(fakeContext, port = 0)
        val boundPort = UrlOpenerServer.activePort
        assertTrue("Expected retry start() after bind failure to bind valid port, got $boundPort", boundPort > 0)

        // Calling start() again while already running must be a safe no-op keeping the same port
        UrlOpenerServer.start(fakeContext, port = 0)
        assertEquals(boundPort, UrlOpenerServer.activePort)

        Socket().use { client ->
            client.soTimeout = 2000
            client.connect(InetSocketAddress("127.0.0.1", boundPort), 1000)
            client.getOutputStream().write("OPEN $token https://example.com/after-bind-retry\n".toByteArray(Charsets.UTF_8))
            client.getOutputStream().flush()
            val resp = client.getInputStream().bufferedReader(Charsets.UTF_8).readLine()
            assertEquals("OK", resp)
        }
    }
}
