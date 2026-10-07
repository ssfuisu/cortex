package org.cortex.terminal.runtime

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * UrlOpenerServer listens on localhost:4715 for browser redirection requests from CLI tools
 * (e.g. `xdg-open`, `sensible-browser`, `google-chrome`, `gh auth login`, `antigravity auth login`).
 *
 * Supported formats:
 *   OPEN <token> <url>
 *   OPEN <url> (if token passed or authenticated)
 *   HTTP requests: Authorization: Bearer <token> or query param token=<token>
 *
 * Threat Model (`CORTEX_URL_TOKEN`):
 * - On Android, the loopback interface (`127.0.0.1`) is shared across all installed applications
 *   that hold the `INTERNET` permission as well as local browser tabs. `CORTEX_URL_TOKEN` protects
 *   this localhost TCP IPC endpoint against unauthorized cross-app requests and browser-based
 *   localhost CSRF attacks.
 * - Because `CORTEX_URL_TOKEN` is exported into the Cortex terminal session environment and persisted
 *   in `filesDir/cortex_url_token` (mode `0600`, owned by the app UID), it functions as a session
 *   capability token rather than an isolation boundary between processes executing inside the same
 *   Cortex terminal session.
 *
 * When a request is received and authenticated, Cortex validates that the URL uses `http` or `https`
 * and dispatches an Android `ACTION_VIEW` Intent to Chrome or the system default browser.
 */
object UrlOpenerServer {
    private const val TAG = "UrlOpenerServer"
    const val PORT = 4715
    private const val MAX_REQUEST_BYTES = 4096
    private const val TOKEN_FILE_NAME = "cortex_url_token"

    @Volatile
    internal var authToken: String? = null

    @Volatile
    internal var activePort: Int = PORT
        private set

    @Volatile
    internal var clientSoTimeoutMs: Int = 5000

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var workerPool: ThreadPoolExecutor? = null

    @Volatile
    private var isRunning = false

    private val mainHandler: Handler? = try {
        Looper.getMainLooper()?.let { Handler(it) }
    } catch (_: Throwable) {
        null
    }

    internal fun tokensMatch(candidate: String?, expected: String): Boolean {
        if (candidate.isNullOrEmpty() || expected.isEmpty()) return false
        return MessageDigest.isEqual(
            candidate.toByteArray(Charsets.UTF_8),
            expected.toByteArray(Charsets.UTF_8)
        )
    }

    @Synchronized
    fun getOrCreateToken(context: Context): String {
        authToken?.let { return it }

        val tokenFile = File(context.filesDir, TOKEN_FILE_NAME)
        if (tokenFile.exists()) {
            try {
                val existing = tokenFile.readText(Charsets.UTF_8).trim()
                if (existing.length == 64 && existing.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
                    authToken = existing
                    return existing
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read existing token file", e)
            }
        }

        // Generate cryptographically secure 256-bit token (32 bytes hex = 64 characters)
        val randomBytes = ByteArray(32)
        SecureRandom().nextBytes(randomBytes)
        val newToken = randomBytes.joinToString("") { "%02x".format(it) }

        try {
            tokenFile.writeText(newToken, Charsets.UTF_8)
            // Enforce 0600 permissions: readable/writable only by owner
            tokenFile.setReadable(false, false)
            tokenFile.setReadable(true, true)
            tokenFile.setWritable(false, false)
            tokenFile.setWritable(true, true)
            tokenFile.setExecutable(false, false)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write token file with 0600 permissions", e)
        }

        authToken = newToken
        return newToken
    }

    fun start(context: Context) {
        start(context, PORT)
    }

    internal fun start(context: Context, port: Int) {
        val bindLatch = CountDownLatch(1)
        synchronized(this) {
            if (isRunning) return
            val appContext = context.applicationContext ?: context
            getOrCreateToken(appContext)

            val pool = ThreadPoolExecutor(
                4,
                4,
                30L,
                TimeUnit.SECONDS,
                ArrayBlockingQueue(16),
                ThreadFactory { r ->
                    Thread(r, "Cortex-UrlOpenerWorker").apply { isDaemon = true }
                }
            ).apply {
                allowCoreThreadTimeOut(true)
            }
            workerPool = pool
            isRunning = true

            val thread = Thread({
                var server: ServerSocket? = null
                try {
                    server = ServerSocket()
                    server.reuseAddress = true
                    server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 50)
                    synchronized(this@UrlOpenerServer) {
                        if (!isRunning || acceptThread !== Thread.currentThread()) {
                            try { server.close() } catch (_: Exception) {}
                            return@Thread
                        }
                        serverSocket = server
                        activePort = server.localPort
                    }
                    bindLatch.countDown()
                    Log.i(TAG, "UrlOpenerServer started on 127.0.0.1:${server.localPort}")

                    while (isRunning && !server.isClosed) {
                        val client = try {
                            server.accept()
                        } catch (e: Exception) {
                            break
                        }
                        try {
                            pool.execute {
                                handleClient(appContext, client)
                            }
                        } catch (e: RejectedExecutionException) {
                            Log.w(TAG, "UrlOpenerServer worker queue full; closing client socket")
                            try {
                                client.close()
                            } catch (_: Exception) {}
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error running UrlOpenerServer", e)
                } finally {
                    bindLatch.countDown()
                    try {
                        server?.close()
                    } catch (_: Exception) {}
                    pool.shutdownNow()
                    synchronized(this@UrlOpenerServer) {
                        if (acceptThread === Thread.currentThread()) {
                            serverSocket = null
                            acceptThread = null
                            isRunning = false
                            activePort = -1
                            workerPool?.shutdownNow()
                            workerPool = null
                        }
                    }
                }
            }, "Cortex-UrlOpenerAccept").apply {
                isDaemon = true
            }
            acceptThread = thread
            thread.start()
        }
        try {
            bindLatch.await(2, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    @Synchronized
    fun stop() {
        isRunning = false
        activePort = -1
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        acceptThread?.interrupt()
        acceptThread = null
        workerPool?.shutdownNow()
        workerPool = null
        Log.i(TAG, "UrlOpenerServer stopped")
    }

    private fun handleClient(context: Context, socket: Socket) {
        try {
            socket.soTimeout = clientSoTimeoutMs
            val input = socket.getInputStream()
            val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)

            // Enforce 4 KB request payload cap; reject any request exceeding 4096 bytes
            val buffer = ByteArray(MAX_REQUEST_BYTES + 1)
            var totalRead = 0
            while (totalRead < buffer.size) {
                val read = input.read(buffer, totalRead, buffer.size - totalRead)
                if (read == -1) break
                totalRead += read
                // If we hit newline in plain-text socket mode, we can stop reading
                val textSoFar = String(buffer, 0, totalRead, Charsets.UTF_8)
                if (!textSoFar.startsWith("GET ") && !textSoFar.startsWith("POST ") &&
                    !textSoFar.startsWith("HEAD ") && textSoFar.contains("\n")) {
                    break
                }
                // If HTTP header ends (\r\n\r\n or \n\n)
                if (textSoFar.contains("\r\n\r\n") || textSoFar.contains("\n\n")) {
                    break
                }
            }

            if (totalRead > MAX_REQUEST_BYTES) {
                val isHttp = String(buffer, 0, minOf(totalRead, 10), Charsets.UTF_8).let {
                    it.startsWith("GET") || it.startsWith("POST") || it.startsWith("HEAD")
                }
                if (isHttp) {
                    val body = "ERR request payload exceeds 4KB cap\n"
                    val resp = "HTTP/1.1 413 Payload Too Large\r\nContent-Type: text/plain\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
                    writer.write(resp)
                } else {
                    writer.write("ERR request payload exceeds 4KB cap\n")
                }
                writer.flush()
                return
            }

            val requestText = String(buffer, 0, totalRead, Charsets.UTF_8)
            val lines = requestText.lines()
            val rawLine = lines.firstOrNull()?.trim()

            if (rawLine.isNullOrEmpty()) {
                writer.write("ERR empty request\n")
                writer.flush()
                return
            }

            val expectedToken = authToken ?: getOrCreateToken(context)
            var isHttp = false
            var targetUrl: String = rawLine
            var authenticated = false

            if (rawLine.startsWith("GET ", ignoreCase = true) || rawLine.startsWith("POST ", ignoreCase = true)) {
                isHttp = true
                val path = rawLine.substringAfter(" ").substringBefore(" ").trim()

                // Check Bearer token in headers
                for (header in lines.drop(1)) {
                    val colonIdx = header.indexOf(':')
                    if (colonIdx > 0) {
                        val headerName = header.substring(0, colonIdx).trim()
                        val headerValue = header.substring(colonIdx + 1).trim()
                        if (headerName.equals("Authorization", ignoreCase = true)) {
                            if (headerValue.startsWith("Bearer ", ignoreCase = true)) {
                                val token = headerValue.substring(7).trim()
                                if (tokensMatch(token, expectedToken)) {
                                    authenticated = true
                                }
                            }
                        }
                    }
                }

                // Check query param token=<token>
                if (!authenticated && path.contains("token=")) {
                    val tokenInQuery = path.substringAfter("token=").substringBefore("&")
                    if (tokensMatch(tokenInQuery, expectedToken)) {
                        authenticated = true
                    }
                }

                targetUrl = when {
                    path.contains("url=") -> {
                        val encoded = path.substringAfter("url=").substringBefore("&")
                        try { URLDecoder.decode(encoded, "UTF-8") } catch (e: Exception) { encoded }
                    }
                    path.startsWith("/http://", ignoreCase = true) -> path.substring(1)
                    path.startsWith("/https://", ignoreCase = true) -> path.substring(1)
                    path.startsWith("/open/", ignoreCase = true) -> path.substring(6)
                    else -> path.trimStart('/')
                }
            } else if (rawLine.startsWith("OPEN ", ignoreCase = true)) {
                val rest = rawLine.substring(5).trim()
                val parts = rest.split("\\s+".toRegex(), limit = 2)
                if (parts.size == 2 && tokensMatch(parts[0], expectedToken)) {
                    authenticated = true
                    targetUrl = parts[1]
                } else if (parts.size == 1 && tokensMatch(parts[0], expectedToken)) {
                    authenticated = true
                    targetUrl = ""
                } else {
                    targetUrl = rest
                }
            } else {
                val parts = rawLine.split("\\s+".toRegex(), limit = 2)
                if (parts.size == 2 && tokensMatch(parts[0], expectedToken)) {
                    authenticated = true
                    targetUrl = parts[1]
                }
            }

            if (!authenticated) {
                if (isHttp) {
                    val body = "ERR unauthorized: invalid or missing bearer token\n"
                    val resp = "HTTP/1.1 401 Unauthorized\r\nContent-Type: text/plain\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
                    writer.write(resp)
                } else {
                    writer.write("ERR unauthorized: invalid or missing bearer token\n")
                }
                writer.flush()
                return
            }

            targetUrl = targetUrl.trim('\"', '\'', ' ', '\t')

            val success = openUrlInBrowser(context, targetUrl)
            if (isHttp) {
                if (success) {
                    val body = "OK\n"
                    val resp = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
                    writer.write(resp)
                } else {
                    val body = "ERR failed to open URL\n"
                    val resp = "HTTP/1.1 400 Bad Request\r\nContent-Type: text/plain\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
                    writer.write(resp)
                }
            } else {
                if (success) {
                    writer.write("OK\n")
                } else {
                    writer.write("ERR failed to open URL\n")
                }
            }
            writer.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Error handling client request", e)
        } finally {
            try { socket.close() } catch (e: Exception) {}
        }
    }

    internal fun normalizeAndValidateUrl(rawUrl: String): String? {
        var cleanUrl = rawUrl.trim().trim('\"', '\'')
        if (cleanUrl.isEmpty()) return null
        if (cleanUrl.any { it.isWhitespace() || it.code < 0x20 }) return null

        if (!cleanUrl.contains("://")) {
            val colonIdx = cleanUrl.indexOf(':')
            val slashIdx = cleanUrl.indexOfAny(charArrayOf('/', '?', '#'))
            val hasColonBeforePath = colonIdx != -1 && (slashIdx == -1 || colonIdx < slashIdx)
            if (hasColonBeforePath) {
                val afterColon = cleanUrl.substring(colonIdx + 1)
                val isHostPort = colonIdx > 0 && afterColon.matches(Regex("^\\d+([/?#].*)?$"))
                if (!isHostPort) {
                    return null
                }
            }
            cleanUrl = "https://$cleanUrl"
        }

        val jUri = try {
            URI(cleanUrl)
        } catch (_: Exception) {
            null
        }
        val aUri = try {
            Uri.parse(cleanUrl)
        } catch (_: Throwable) {
            null
        }

        val scheme = (aUri?.scheme ?: jUri?.scheme)?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return null
        }

        val host = aUri?.host ?: jUri?.host
        if (host.isNullOrBlank()) {
            return null
        }

        return cleanUrl
    }

    fun openUrlInBrowser(context: Context, rawUrl: String): Boolean {
        val cleanUrl = normalizeAndValidateUrl(rawUrl)
        if (cleanUrl == null) {
            Log.w(TAG, "Rejected disallowed or malformed URL: $rawUrl")
            return false
        }

        val uri = try {
            Uri.parse(cleanUrl)
        } catch (e: Exception) {
            Log.e(TAG, "Malformed URL: $cleanUrl", e)
            return false
        }

        // Strictly validate URLs: only allow http:// and https:// schemes.
        // Reject file://, content://, javascript:, intent:, ftp://, and null/empty schemes.
        if (uri != null) {
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") {
                Log.w(TAG, "Rejected disallowed URL scheme '$scheme': $cleanUrl")
                return false
            }
        }

        if (uri != null) {
            mainHandler?.post {
                val pm = context.packageManager
                val browserPackages = listOf(
                    "com.android.chrome",
                    "com.chrome.beta",
                    "com.chrome.dev",
                    "com.chrome.canary",
                    "org.mozilla.firefox",
                    "com.brave.browser",
                    "com.opera.browser",
                    "com.microsoft.emmx",
                    "com.sec.android.app.sbrowser"
                )

                for (pkg in browserPackages) {
                    try {
                        pm.getPackageInfo(pkg, 0)
                        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                            setPackage(pkg)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        }
                        context.startActivity(intent)
                        return@post
                    } catch (e: Exception) {
                        // Try next browser
                    }
                }

                // Fallback to default browser / system handler
                try {
                    val fallbackIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    context.startActivity(fallbackIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to launch default browser for $cleanUrl", e)
                }
            }
        }
        return true
    }
}
