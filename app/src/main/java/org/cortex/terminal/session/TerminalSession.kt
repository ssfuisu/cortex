package org.cortex.terminal.session

import android.content.Context
import android.util.Log
import org.cortex.terminal.R
import org.cortex.terminal.emulator.TerminalEmulator
import org.cortex.terminal.pty.PtyProcess
import org.cortex.terminal.runtime.BootstrapManager
import org.cortex.terminal.runtime.Environment
import org.cortex.terminal.runtime.RootMode
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
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
    @Volatile
    var isRunning = false
        private set

    var onSessionFinished: ((Int) -> Unit)? = null
    var onCloseRequested: (() -> Unit)? = null
    var exitCode: Int? = null
        private set
    var title = "Cortex"

    /** 1-based position of this session in the drawer, for notification texts. */
    var numberProvider: (() -> Int)? = null

    /** Whether the session runs as real root in a chroot (root mode was on when it started). */
    @Volatile
    var isRoot = false
        private set

    fun start() {
        if (isRunning) return

        var shell = BootstrapManager.getInitialShellCommand(context)
        val env = Environment.buildEnvironment(context)
        var ptyEnv = env
        var cwd = Environment.getHomeDir(context).absolutePath

        var args = if (shell.endsWith("sh") || shell.endsWith("bash")) {
            arrayOf("-l", "-i") // Interactive login shell
        } else {
            emptyArray()
        }

        if (RootMode.isEnabled(context)) {
            val launch = RootMode.sessionLaunch(context, env)
            if (launch != null) {
                isRoot = true
                shell = launch.command
                args = launch.args
                ptyEnv = launch.env
                cwd = launch.cwd
            } else {
                Log.e(tag, "Root mode is on, but its helper is missing: starting a normal session")
            }
        }

        try {
            ptyProcess = PtyProcess.create(
                cmd = shell,
                args = args,
                envVars = ptyEnv,
                cwd = cwd,
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
                    val code = ptyProcess?.waitFor() ?: 0
                    exitCode = code
                    val duration = System.currentTimeMillis() - startTime
                    Log.i(tag, "Process exited with code $code after ${duration}ms")
                    isRunning = false
                    if (isRoot && code != 0 && duration <= 5000) {
                        // su refused, or root is gone: say where root mode is turned off.
                        val hint = "\r\n" + context.getString(R.string.root_session_failed) + "\r\n"
                        val bytes = hint.toByteArray(Charsets.UTF_8)
                        emulator.processInput(bytes, 0, bytes.size)
                    }
                    if (code != 0) {
                        val msg = "\r\n[Process completed (code $code) - press Enter]\r\n"
                        emulator.processInput(msg.toByteArray(), 0, msg.length)
                    } else if (duration <= 1500) {
                        val msg = "\r\n[Process completed - press Enter]\r\n"
                        emulator.processInput(msg.toByteArray(), 0, msg.length)
                    }
                    onSessionFinished?.invoke(code)
                }
            }

            emulator.onTitleChange = { newTitle ->
                this.title = newTitle
            }

            emulator.onClipboardSet = { text ->
                TerminalBridge.onClipboardRequest(context, text)
            }

            emulator.onCommandFinished = { durationMs, exitCode ->
                TerminalBridge.onCommandFinished(context, numberProvider?.invoke() ?: 1, durationMs, exitCode)
            }

            emulator.onSendResponse = { response ->
                write(response)
            }

        } catch (e: Exception) {
            Log.e(tag, "Error launching session", e)
        }
    }

    private val writeExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Cortex-PtyWriter").apply { isDaemon = true }
    }

    fun write(bytes: ByteArray) {
        if (!isRunning || writeExecutor.isShutdown) return
        try {
            writeExecutor.execute {
                if (!isRunning) return@execute
                try {
                    ptyProcess?.outputStream?.let { os ->
                        os.write(bytes)
                        os.flush()
                    }
                } catch (e: IOException) {
                    if (isRunning) {
                        Log.e(tag, "Error writing to PTY: ${e.message}")
                    }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Executor shut down concurrently during destroy()
        }
    }

    fun write(text: String) {
        write(text.toByteArray(Charsets.UTF_8))
    }

    // Whether this session is on screen in the focused window. Main thread only.
    private var isFocused = false

    /** Called when the session gains or loses focus; tells the program if it asked (mode 1004). */
    fun setFocused(focused: Boolean) {
        if (focused == isFocused) return
        isFocused = focused
        emulator.focusReport(focused)?.let { write(it) }
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
        writeExecutor.shutdownNow()
        ptyProcess?.destroy()
        try {
            readerThread?.interrupt()
        } catch (_: Exception) {}
    }
}
