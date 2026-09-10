package org.cortex.terminal.session

import android.content.Context
import android.util.Log
import org.cortex.terminal.emulator.TerminalEmulator
import org.cortex.terminal.pty.PtyProcess
import org.cortex.terminal.runtime.BootstrapManager
import org.cortex.terminal.runtime.Environment
import java.io.File
import java.io.IOException
import kotlin.concurrent.thread

class TerminalSession(
    private val context: Context,
    var rows: Int = 24,
    var cols: Int = 80,
    var widthPx: Int = 0,
    var heightPx: Int = 0,
    var onRedraw: (() -> Unit)? = null
) {
    private val tag = "TerminalSession"
    val emulator = TerminalEmulator(rows, cols) {
        onRedraw?.invoke()
    }
    private var ptyProcess: PtyProcess? = null
    private var readerThread: Thread? = null
    var isRunning = false
        private set

    var onSessionFinished: ((Int) -> Unit)? = null
    var title = "Cortex"

    fun start() {
        if (isRunning) return

        val shell = BootstrapManager.getInitialShellCommand(context)
        val env = Environment.buildEnvironment(context)
        val homeDir = Environment.getHomeDir(context).absolutePath

        val args = if (shell.endsWith("sh") || shell.endsWith("bash")) {
            arrayOf("-l", "-i") // Interactive login shell
        } else {
            emptyArray()
        }

        try {
            ptyProcess = PtyProcess.create(
                cmd = shell,
                args = args,
                envVars = env,
                cwd = homeDir,
                rows = rows,
                cols = cols,
                widthPx = widthPx,
                heightPx = heightPx
            )

            if (ptyProcess == null) {
                Log.e(tag, "Failed to create PTY process")
                return
            }

            isRunning = true
            val startTime = System.currentTimeMillis()

            // Read output from PTY and feed into emulator
            readerThread = thread(start = true, name = "Cortex-PtyReader") {
                val buffer = ByteArray(4096)
                try {
                    val stream = ptyProcess?.inputStream ?: return@thread
                    while (isRunning) {
                        val read = stream.read(buffer)
                        if (read <= 0) break
                        emulator.processInput(buffer, 0, read)
                    }
                } catch (e: Exception) {
                    if (isRunning) {
                        Log.e(tag, "Error reading from PTY: ${e.message}", e)
                    }
                } finally {
                    val exitCode = ptyProcess?.waitFor() ?: 0
                    val duration = System.currentTimeMillis() - startTime
                    Log.i(tag, "Process exited with code $exitCode after ${duration}ms")
                    isRunning = false
                    if (exitCode != 0) {
                        val msg = "\r\n[Process exited with code $exitCode]\r\n"
                        emulator.processInput(msg.toByteArray(), 0, msg.length)
                    }
                    onSessionFinished?.invoke(exitCode)
                }
            }

            emulator.onTitleChange = { newTitle ->
                this.title = newTitle
            }

            emulator.onSendResponse = { response ->
                write(response)
            }

        } catch (e: Exception) {
            Log.e(tag, "Error launching session", e)
        }
    }

    fun write(bytes: ByteArray) {
        if (!isRunning) return
        try {
            ptyProcess?.outputStream?.let { os ->
                os.write(bytes)
                os.flush()
            }
        } catch (e: IOException) {
            Log.e(tag, "Error writing to PTY: ${e.message}")
        }
    }

    fun write(text: String) {
        write(text.toByteArray(Charsets.UTF_8))
    }

    fun updateDimensions(newRows: Int, newCols: Int, newWidthPx: Int, newHeightPx: Int) {
        if (newRows <= 0 || newCols <= 0) return
        this.rows = newRows
        this.cols = newCols
        this.widthPx = newWidthPx
        this.heightPx = newHeightPx

        emulator.resize(newRows, newCols)
        ptyProcess?.resize(newRows, newCols, newWidthPx, newHeightPx)
    }

    fun destroy() {
        isRunning = false
        ptyProcess?.destroy()
        try {
            readerThread?.interrupt()
        } catch (_: Exception) {}
    }
}
