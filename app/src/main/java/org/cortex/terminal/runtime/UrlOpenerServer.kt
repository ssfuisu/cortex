package org.cortex.terminal.runtime

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * UrlOpenerServer listens on localhost:4715 for browser redirection requests from CLI tools
 * (e.g. `xdg-open`, `sensible-browser`, `google-chrome`, `gh auth login`, `antigravity auth login`).
 *
 * Supported formats:
 *   OPEN <token> <url>
 *   OPEN <url> (if token passed or authenticated)
 *   HTTP requests: Authorization: Bearer <token> or query param token=<token>
 *
 * When received and authenticated, Cortex dispatches an Android Intent to Chrome or the system default browser.
 */
object UrlOpenerServer {
    private const val TAG = "UrlOpenerServer"
    const val PORT = 4715
    private const val MAX_REQUEST_BYTES = 4096
    private const val TOKEN_FILE_NAME = "cortex_url_token"

    @Volatile
    private var authToken: String? = null

    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val threadPool = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())

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

    @Synchronized
    fun start(context: Context) {
        if (isRunning) return
        val appContext = context.applicationContext
        getOrCreateToken(appContext)

        threadPool.execute {
            try {
                val server = ServerSocket()
                server.reuseAddress = true
                server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 50)
                serverSocket = server
                isRunning = true
                Log.i(TAG, "UrlOpenerServer started on 127.0.0.1:$PORT")

                while (isRunning && !server.isClosed) {
                    val client = try {
                        server.accept()
                    } catch (e: Exception) {
                        break
                    }
                    threadPool.execute {
                        handleClient(appContext, client)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error running UrlOpenerServer", e)
            } finally {
                isRunning = false
            }
        }
    }

    @Synchronized
    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {}
        serverSocket = null
        Log.i(TAG, "UrlOpenerServer stopped")
    }

    private fun handleClient(context: Context, socket: Socket) {
        try {
            socket.soTimeout = 5000
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
                                if (token == expectedToken) {
                                    authenticated = true
                                }
                            }
                        }
                    }
                }

                // Check query param token=<token>
                if (!authenticated && path.contains("token=")) {
                    val tokenInQuery = path.substringAfter("token=").substringBefore("&")
                    if (tokenInQuery == expectedToken) {
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
                if (parts.size == 2 && parts[0] == expectedToken) {
                    authenticated = true
                    targetUrl = parts[1]
                } else if (parts.size == 1 && parts[0] == expectedToken) {
                    authenticated = true
                    targetUrl = ""
                } else {
                    targetUrl = rest
                }
            } else {
                val parts = rawLine.split("\\s+".toRegex(), limit = 2)
                if (parts.size == 2 && parts[0] == expectedToken) {
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

    fun openUrlInBrowser(context: Context, rawUrl: String): Boolean {
        var cleanUrl = rawUrl.trim().trim('\"', '\'')
        if (cleanUrl.isEmpty()) return false

        // Normalize URL scheme if scheme is missing
        if (!cleanUrl.contains("://")) {
            cleanUrl = "https://$cleanUrl"
        }

        val uri = try {
            Uri.parse(cleanUrl)
        } catch (e: Exception) {
            Log.e(TAG, "Malformed URL: $cleanUrl", e)
            return false
        }

        // Strictly validate URLs: only allow http:// and https:// schemes.
        // Reject file://, content://, javascript:, ftp://, and null/empty schemes.
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            Log.w(TAG, "Rejected disallowed URL scheme '$scheme': $cleanUrl")
            return false
        }

        mainHandler.post {
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
        return true
    }
}
