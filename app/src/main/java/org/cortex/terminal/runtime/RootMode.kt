package org.cortex.terminal.runtime

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.preference.PreferenceManager
import java.io.File
import java.io.IOException

/**
 * Root mode: on a rooted phone, sessions run as real root in a chroot of the same rootfs that normal
 * mode uses through the preloaded hook (see app/src/main/cpp/root/cortex-chroot.c). The setting
 * stays on until it is turned off in Settings.
 */
object RootMode {
    const val PREF_KEY = "root_mode"
    private const val TAG = "RootMode"

    // The root manager may ask the user first, so give them time to answer.
    private const val CHECK_TIMEOUT_SECONDS = 90L
    private const val RESTORE_TIMEOUT_SECONDS = 900L

    sealed class Result {
        object Ok : Result()

        /** su is missing, or it did not give root (the request was denied or timed out). */
        object NoRoot : Result()

        /** Root works, but the helper failed; [detail] is its last message. */
        data class Failed(val detail: String) : Result()
    }

    class Launch(val command: String, val args: Array<String>, val env: Array<String>, val cwd: String)

    fun isEnabled(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREF_KEY, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(PREF_KEY, enabled).apply()
    }

    /** su of Magisk, KernelSU, APatch or an older root solution; "su" (from PATH) if none is visible. */
    fun findSu(): String {
        val candidates = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/debug_ramdisk/su",
            "/data/adb/ksu/bin/su",
            "/data/adb/ap/bin/su",
            "/su/bin/su",
            "/vendor/bin/su",
            "/system_ext/bin/su",
            "/product/bin/su"
        )
        return candidates.firstOrNull { File(it).exists() } ?: "su"
    }

    private fun helperFile(context: Context): File = File(context.filesDir, "bin/cortex-chroot")

    // The APK cannot change while this process runs: compare with it once.
    @Volatile
    private var helperChecked = false

    /** Installs the helper for this ABI from the APK (when missing or changed). */
    fun ensureHelper(context: Context): File? {
        val asset = if (Process.is64Bit()) "cortex-chroot-arm64.elf" else "cortex-chroot-arm.elf"
        val target = helperFile(context)
        if (helperChecked && target.isFile) return target
        return try {
            val bytes = context.assets.open(asset).use { it.readBytes() }
            val current = if (target.isFile && target.length() == bytes.size.toLong()) target.readBytes() else null
            if (current == null || !current.contentEquals(bytes)) {
                target.parentFile?.mkdirs()
                val tmp = File(target.parentFile, "${target.name}.tmp")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(target)) throw IOException("cannot replace ${target.path}")
            }
            try {
                android.system.Os.chmod(target.absolutePath, 493) // 0755: run by root
            } catch (e: Exception) {
                Log.w(TAG, "chmod ${target.path} failed: ${e.message}")
            }
            helperChecked = true
            target
        } catch (e: Exception) {
            Log.e(TAG, "Root mode helper $asset is not available", e)
            null
        }
    }

    private fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    private fun valueOf(env: Array<String>, name: String): String? =
        env.firstOrNull { it.startsWith("$name=") }?.substring(name.length + 1)

    /**
     * How to start a root session: su runs the helper, which sets the chroot up and starts a login
     * shell in it. Terminal, language and time zone are the ones normal mode would use. null when
     * the helper is missing from this build.
     */
    fun sessionLaunch(context: Context, normalEnv: Array<String>): Launch? {
        val helper = ensureHelper(context) ?: return null
        val root = Environment.getCortexRoot(context).absolutePath
        val words = mutableListOf(helper.absolutePath, "enter", root)
        valueOf(normalEnv, "TERM")?.let { words += listOf("--term", it) }
        valueOf(normalEnv, "COLORTERM")?.let { words += listOf("--colorterm", it) }
        valueOf(normalEnv, "LANG")?.let { words += listOf("--lang", it) }
        valueOf(normalEnv, "TZ")?.let { words += listOf("--tz", it) }
        val env = arrayOf(
            "PATH=/system/bin:/system/xbin:/sbin:/vendor/bin",
            "ANDROID_ROOT=/system",
            "ANDROID_DATA=/data",
            "HOME=/",
            "TERM=" + (valueOf(normalEnv, "TERM") ?: "xterm-256color")
        )
        return Launch(findSu(), arrayOf("-c", words.joinToString(" ") { quote(it) }), env, context.filesDir.absolutePath)
    }

    private class SuResult(val exitCode: Int, val output: String)

    /** Runs [command] through su and returns its status and output; exit code -1 if su cannot run. */
    private fun runSu(command: String, timeoutSeconds: Long): SuResult {
        val process = try {
            ProcessBuilder(findSu(), "-c", command).redirectErrorStream(true).start()
        } catch (e: IOException) {
            Log.w(TAG, "Cannot start su: ${e.message}")
            return SuResult(-1, e.message ?: "")
        }
        val output = StringBuilder()
        val reader = Thread {
            try {
                process.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(output) { output.append(line).append('\n') }
                }
            } catch (_: IOException) {
            }
        }.apply {
            isDaemon = true
            start()
        }
        // Process.waitFor(timeout) needs API 26: poll instead.
        val deadline = SystemClock.uptimeMillis() + timeoutSeconds * 1000
        while (true) {
            try {
                val code = process.exitValue()
                reader.join(2000)
                return SuResult(code, synchronized(output) { output.toString() })
            } catch (_: IllegalThreadStateException) {
                if (SystemClock.uptimeMillis() > deadline) {
                    process.destroy()
                    Log.w(TAG, "su did not finish within $timeoutSeconds s")
                    return SuResult(-1, "timeout")
                }
                SystemClock.sleep(100)
            }
        }
    }

    private fun lastHelperMessage(output: String): String {
        val lines = output.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val helperLine = lines.lastOrNull { it.startsWith("cortex-chroot:") }
        return (helperLine ?: lines.lastOrNull() ?: "").removePrefix("cortex-chroot:").trim()
    }

    /** Whether root mode works on this phone: su gives root and the chroot can be set up. Blocking. */
    fun check(context: Context): Result {
        val id = runSu("id -u", CHECK_TIMEOUT_SECONDS)
        if (id.exitCode != 0 || id.output.lines().none { it.trim() == "0" }) {
            Log.i(TAG, "No root: status ${id.exitCode}, output '${id.output.trim()}'")
            return Result.NoRoot
        }
        val helper = ensureHelper(context) ?: return Result.Failed("cortex-chroot is missing from this build")
        val root = Environment.getCortexRoot(context).absolutePath
        val check = runSu("${quote(helper.absolutePath)} check ${quote(root)}", CHECK_TIMEOUT_SECONDS)
        if (check.exitCode != 0) {
            Log.w(TAG, "Root mode check failed (${check.exitCode}): ${check.output.trim()}")
            return Result.Failed(lastHelperMessage(check.output).ifEmpty { "status ${check.exitCode}" })
        }
        return Result.Ok
    }

    /**
     * Gives the files root mode created back to the app, so normal mode (which runs as the app) can
     * change them again. Blocking; can take a few seconds on a large rootfs.
     */
    fun restore(context: Context): Result {
        val helper = ensureHelper(context) ?: return Result.Failed("cortex-chroot is missing from this build")
        val root = Environment.getCortexRoot(context).absolutePath
        val uid = Process.myUid()
        val gid = try { android.system.Os.getgid() } catch (e: Exception) { uid }
        val result = runSu("${quote(helper.absolutePath)} restore ${quote(root)} $uid $gid", RESTORE_TIMEOUT_SECONDS)
        Log.i(TAG, "restore: status ${result.exitCode}, ${result.output.trim()}")
        // Without root the helper never runs, so none of its output is there.
        val helperRan = result.output.contains("cortex-chroot") || result.output.contains("owners ")
        return when {
            result.exitCode == -1 || (result.exitCode != 0 && !helperRan) -> Result.NoRoot
            result.exitCode != 0 -> Result.Failed(lastHelperMessage(result.output).ifEmpty { "status ${result.exitCode}" })
            else -> Result.Ok
        }
    }
}
