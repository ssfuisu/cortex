package org.cortex.terminal.runtime

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * UrlOpenerServer listens on localhost:4715 for browser redirection requests from CLI tools
 * (e.g. `xdg-open`, `sensible-browser`, `google-chrome`, `gh auth login`, `antigravity auth login`).
 *
 * Supported command format:
 *   OPEN <url>
 * or raw:
 *   <url>
 *
 * When received, Cortex dispatches an Android Intent to Chrome or the system default browser.
 */
object UrlOpenerServer {
    private const val TAG = "UrlOpenerServer"
    const val PORT = 4715

    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val threadPool = Executors.newCachedThreadPool()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Synchronized
    fun start(context: Context) {
        if (isRunning) return
        val appContext = context.applicationContext

        threadPool.execute {
            try {
                val server = ServerSocket(PORT, 10, InetAddress.getByName("127.0.0.1"))
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
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)

            val line = reader.readLine()?.trim()
            if (line.isNullOrEmpty()) {
                writer.write("ERR empty request\n")
                writer.flush()
                return
            }

            val targetUrl = if (line.startsWith("OPEN ", ignoreCase = true)) {
                line.substring(5).trim()
            } else {
                line
            }.trim('\"', '\'')

            val success = openUrlInBrowser(context, targetUrl)
            if (success) {
                writer.write("OK\n")
            } else {
                writer.write("ERR failed to open URL\n")
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

        // Normalize URL scheme
        if (!cleanUrl.startsWith("http://", ignoreCase = true) &&
            !cleanUrl.startsWith("https://", ignoreCase = true) &&
            !cleanUrl.startsWith("ftp://", ignoreCase = true) &&
            !cleanUrl.startsWith("file://", ignoreCase = true)
        ) {
            cleanUrl = "https://$cleanUrl"
        }

        val uri = try {
            Uri.parse(cleanUrl)
        } catch (e: Exception) {
            Log.e(TAG, "Malformed URL: $cleanUrl", e)
            return false
        }

        mainHandler.post {
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            // Prefer Google Chrome if installed on the device
            val pm = context.packageManager
            val chromePkg = "com.android.chrome"
            val hasChrome = try {
                pm.getPackageInfo(chromePkg, 0)
                true
            } catch (e: Exception) {
                false
            }

            if (hasChrome) {
                try {
                    intent.setPackage(chromePkg)
                    context.startActivity(intent)
                    return@post
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to launch Chrome specifically, falling back to default handler", e)
                }
            }

            // Fallback to default browser / system handler
            try {
                val fallbackIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallbackIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch default browser for $cleanUrl", e)
            }
        }
        return true
    }
}
