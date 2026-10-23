package org.cortex.terminal.api

import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.cortex.terminal.runtime.Environment
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.concurrent.thread

/**
 * Serves cortex-api while Cortex's service runs. It listens on 127.0.0.1 only, on a port chosen by
 * the system, and writes "PORT TOKEN" to /run/cortex-api in the rootfs (mode 0600, so only the app
 * and root can read it). Other apps can reach the port, but not without the token.
 */
object ApiServer {
    private const val TAG = "CortexApi"

    private var server: ServerSocket? = null
    private var workers: ExecutorService? = null

    private fun confFile(context: Context) = File(Environment.getCortexRoot(context), "run/cortex-api")

    @Synchronized
    fun start(context: Context) {
        if (server != null) return
        val app = context.applicationContext
        try {
            val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
            val token = newToken()
            writeConf(confFile(app), socket.localPort, token)
            val pool = Executors.newFixedThreadPool(4) { r -> Thread(r, "Cortex-Api").apply { isDaemon = true } }
            server = socket
            workers = pool
            thread(name = "Cortex-ApiAccept", isDaemon = true) {
                while (!socket.isClosed) {
                    val client = try {
                        socket.accept()
                    } catch (e: IOException) {
                        break
                    }
                    try {
                        pool.execute { handle(app, client, token) }
                    } catch (e: RejectedExecutionException) {
                        client.close()
                    }
                }
            }
            Log.i(TAG, "Listening on 127.0.0.1:${socket.localPort}")
        } catch (e: Exception) {
            Log.e(TAG, "cortex-api cannot start", e)
        }
    }

    @Synchronized
    fun stop(context: Context) {
        try {
            server?.close()
        } catch (e: IOException) {
            Log.w(TAG, "Closing the API socket failed", e)
        }
        server = null
        workers?.shutdownNow()
        workers = null
        confFile(context.applicationContext).delete()
    }

    private fun newToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun writeConf(file: File, port: Int, token: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.delete()
        tmp.writeText("")
        android.system.Os.chmod(tmp.absolutePath, 384) // 0600 before the token is in it
        tmp.writeText("$port $token\n")
        if (!tmp.renameTo(file)) throw IOException("cannot write ${file.path}")
    }

    private fun handle(context: Context, client: Socket, token: String) {
        try {
            client.use { socket ->
                socket.soTimeout = READ_TIMEOUT_MS
                val out = BufferedOutputStream(socket.getOutputStream())
                val result = try {
                    val input = DeadlineInputStream(BufferedInputStream(socket.getInputStream()), REQUEST_DEADLINE_MS)
                    val request = ApiProtocol.read(input) { ApiProtocol.tokenMatches(it, token) }
                    ApiCommands.run(context, request)
                } catch (e: ApiProtocol.Unauthorized) {
                    ApiResult(1, "cortex-api: the request was not authorized\n")
                } catch (e: ApiProtocol.ProtocolException) {
                    ApiResult(2, "cortex-api: ${e.message}\n")
                }
                ApiProtocol.writeResponse(out, result.status, result.output)
            }
        } catch (e: IOException) {
            Log.w(TAG, "API connection failed: ${e.message}")
        }
    }

    // The client sends its whole request at once (it reads stdin before it connects), so a request
    // that takes longer is ended: the read timeout alone let a client that sends a byte now and then
    // hold one of the four workers for as long as it liked.
    private const val READ_TIMEOUT_MS = 5_000
    private const val REQUEST_DEADLINE_MS = 10_000L

    private class DeadlineInputStream(private val input: InputStream, timeoutMs: Long) : InputStream() {
        private val deadline = SystemClock.uptimeMillis() + timeoutMs

        private fun checkDeadline() {
            if (SystemClock.uptimeMillis() > deadline) throw SocketTimeoutException("the request took too long")
        }

        override fun read(): Int {
            checkDeadline()
            return input.read()
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            checkDeadline()
            return input.read(b, off, len)
        }
    }
}
