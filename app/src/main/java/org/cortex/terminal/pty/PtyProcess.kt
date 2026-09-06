package org.cortex.terminal.pty

import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

class PtyProcess private constructor(
    val masterFd: Int,
    val pid: Int,
    private val pfd: ParcelFileDescriptor
) {
    val inputStream: InputStream = FileInputStream(pfd.fileDescriptor)
    val outputStream: OutputStream = FileOutputStream(pfd.fileDescriptor)

    private var isAlive = true

    fun resize(rows: Int, cols: Int, widthPx: Int, heightPx: Int) {
        if (isAlive && masterFd >= 0) {
            PtyNative.setPtyWindowSize(masterFd, rows, cols, widthPx, heightPx)
        }
    }

    fun waitFor(): Int {
        val exitCode = PtyNative.waitForProcess(pid)
        isAlive = false
        return exitCode
    }

    fun destroy() {
        if (isAlive) {
            PtyNative.killProcess(pid, 15) // SIGTERM
            try {
                pfd.close()
            } catch (_: Exception) {}
            isAlive = false
        }
    }

    companion object {
        fun create(
            cmd: String,
            args: Array<String>,
            envVars: Array<String>,
            cwd: String?,
            rows: Int,
            cols: Int,
            widthPx: Int,
            heightPx: Int
        ): PtyProcess? {
            val result = PtyNative.createPty(cmd, args, envVars, cwd, rows, cols, widthPx, heightPx) ?: return null
            val masterFd = result[0]
            val pid = result[1]
            val pfd = ParcelFileDescriptor.adoptFd(masterFd)
            return PtyProcess(masterFd, pid, pfd)
        }
    }
}
