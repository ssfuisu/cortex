package org.cortex.terminal.desktop

import android.content.Context
import android.util.Log
import org.cortex.terminal.pty.PtyProcess
import org.cortex.terminal.runtime.BootstrapManager
import org.cortex.terminal.runtime.Environment
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.concurrent.thread

/**
 * The desktop add-on (X11): what the app knows of it from the rootfs. The packages and the X server
 * are cortex-desktop's (a script in /usr/local/bin): it installs them with apt, starts Ubuntu's Xvnc
 * on a Unix socket of its own, and the app's Desktop screen shows what Xvnc draws.
 */
object DesktopAddon {
    private const val TAG = "DesktopAddon"

    const val PREF_MOUSE_MODE = "desktop_mouse_mode" // "touch" (the finger is the pointer) or "touchpad"
    const val PREF_SCALE = "desktop_scale" // size of the desktop's pixels in percent of Android's dp

    const val KIND_LIGHT = "light"
    const val KIND_XFCE = "xfce"

    fun stateDir(context: Context) = File(Environment.getCortexRoot(context), "tmp/.cortex-desktop")

    fun socket(context: Context) = File(stateDir(context), "vnc.sock")

    fun audioPipe(context: Context) = File(stateDir(context), "audio.fifo")

    /** "light" or "xfce" once cortex-desktop installed it, else null. */
    fun installedKind(context: Context): String? {
        val root = Environment.getCortexRoot(context)
        val hasServer = File(root, "usr/bin/Xtigervnc").exists() || File(root, "usr/bin/Xvnc").exists()
        if (!hasServer) return null
        val kind = try {
            File(root, "etc/cortex/desktop.conf").readLines()
                .lastOrNull { it.startsWith("DESKTOP=") }?.substringAfter('=')?.trim()
        } catch (e: Exception) {
            null
        }
        return kind?.takeIf { it.isNotEmpty() }
    }

    /** Whether the X server runs: its pid (a process of this app) is alive and its socket is there. */
    fun isRunning(context: Context): Boolean {
        val pid = try {
            File(stateDir(context), "xvnc.pid").readText().trim().toInt()
        } catch (e: Exception) {
            return false
        }
        return File("/proc/$pid").exists() && socket(context).exists()
    }

    fun installCommand(kind: String, firefox: Boolean): String =
        "cortex-desktop install $kind" + if (firefox) " --firefox" else ""

    /** The size of the X screen for a view of [widthPx] x [heightPx] pixels at [scalePercent] % of dp. */
    fun desktopSize(widthPx: Int, heightPx: Int, density: Float, scalePercent: Int): Pair<Int, Int> {
        // One desktop pixel is scalePercent % of a dp: 100 % makes text as large as Android's, smaller
        // values fit more on the screen
        val factor = (density * scalePercent / 100f).coerceAtLeast(1f)
        val w = (widthPx / factor).toInt().coerceIn(320, 4096)
        val h = (heightPx / factor).toInt().coerceIn(240, 4096)
        return Pair(w - w % 2, h - h % 2)
    }

    class Result(val exitCode: Int, val output: String)

    /**
     * Runs a command line in a login shell of the rootfs, the way a session's shell runs it, but with
     * no terminal on the screen; blocks until it ends (call it off the main thread).
     */
    fun run(context: Context, command: String, timeoutMs: Long = 120_000): Result {
        val shell = BootstrapManager.getInitialShellCommand(context)
        val env = Environment.buildEnvironment(context)
        val home = Environment.getHomeDir(context).absolutePath
        val process = PtyProcess.create(shell, arrayOf("-l", "-c", command), env, home, 24, 160, 0, 0)
            ?: return Result(-1, "could not start $shell")
        val output = ByteArrayOutputStream()
        val reader = thread(name = "Cortex-DesktopCommand") {
            val buffer = ByteArray(4096)
            try {
                while (true) {
                    val n = process.inputStream.read(buffer)
                    if (n <= 0) break
                    synchronized(output) { if (output.size() < 65536) output.write(buffer, 0, n) }
                }
            } catch (e: Exception) {
                // the pseudo-terminal ends with an error when the shell is gone
            }
        }
        val code = java.util.concurrent.atomic.AtomicInteger(-1)
        val waiter = thread(name = "Cortex-DesktopWait") {
            code.set(try { process.waitFor() } catch (e: Exception) { -1 })
        }
        waiter.join(timeoutMs)
        if (waiter.isAlive) {
            Log.w(TAG, "'$command' still runs after $timeoutMs ms; stopped")
            process.destroy()
            waiter.join(2000)
        }
        reader.join(2000)
        val text = synchronized(output) { output.toString("UTF-8") }
        return Result(code.get(), text.replace("\r\n", "\n").trim())
    }
}
