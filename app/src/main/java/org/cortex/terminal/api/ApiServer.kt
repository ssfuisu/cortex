package org.cortex.terminal.api

import android.content.Context
import android.util.Log
import org.cortex.terminal.runtime.Environment
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
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
                socket.soTimeout = 30_000
                val out = BufferedOutputStream(socket.getOutputStream())
                val result = try {
                    val request = ApiProtocol.read(BufferedInputStream(socket.getInputStream()))
                    if (!ApiProtocol.tokenMatches(request.token, token)) {
                        ApiResult(1, "cortex-api: the request was not authorized\n")
                    } else {
                        ApiCommands.run(context, request)
                    }
                } catch (e: ApiProtocol.ProtocolException) {
                    ApiResult(2, "cortex-api: ${e.message}\n")
                }
                ApiProtocol.writeResponse(out, result.status, result.output)
            }
        } catch (e: IOException) {
            Log.w(TAG, "API connection failed: ${e.message}")
        }
    }
}
