package org.cortex.terminal.pty

import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

import java.util.concurrent.atomic.AtomicBoolean

class PtyProcess private constructor(
    val masterFd: Int,
    val pid: Int,
    private val pfd: ParcelFileDescriptor
) {
    val inputStream: InputStream = FileInputStream(pfd.fileDescriptor)
    val outputStream: OutputStream = FileOutputStream(pfd.fileDescriptor)

    private val alive = AtomicBoolean(true)
    private val fdClosed = AtomicBoolean(false)

    val isAlive: Boolean
        get() = alive.get()

    private fun closeMasterFdOnce() {
        if (fdClosed.compareAndSet(false, true)) {
            try {
                pfd.close()
            } catch (_: Exception) {}
        }
    }

    fun resize(rows: Int, cols: Int, widthPx: Int, heightPx: Int) {
        if (isAlive && masterFd >= 0) {
            PtyNative.setPtyWindowSize(masterFd, rows, cols, widthPx, heightPx)
        }
    }

    fun waitFor(): Int {
        try {
            return PtyNative.waitForProcess(pid)
        } finally {
            alive.set(false)
            closeMasterFdOnce()
        }
    }

    fun destroy() {
        if (alive.compareAndSet(true, false)) {
            PtyNative.killProcess(pid, 1) // SIGHUP
            closeMasterFdOnce()
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
