package org.cortex.terminal

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.StatFs
import androidx.core.content.FileProvider
import org.cortex.terminal.runtime.BootstrapManager
import org.cortex.terminal.runtime.Environment
import org.cortex.terminal.runtime.MirrorSelector
import org.cortex.terminal.runtime.RootMode
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A text file with what helps to find a problem: the app's and the phone's versions, the state of
 * the Ubuntu system and root mode, and the app's own log (Android lets an app read only its own).
 * Settings > Send a bug report shares it with an app the user picks; nothing is sent otherwise.
 */
object BugReport {
    private const val LOG_LINES = 4000
    private const val LOG_LIMIT = 2_000_000

    fun write(context: Context): File {
        val dir = File(context.cacheDir, "reports")
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() } // only the newest one is kept
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "cortex-report-$stamp.txt")
        file.writeText(text(context))
        return file
    }

    fun share(activity: Activity, file: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "Cortex bug report")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // The chooser passes the permission on only for the URI in the ClipData
        send.clipData = ClipData.newRawUri(file.name, uri)
        activity.startActivity(Intent.createChooser(send, activity.getString(R.string.bug_report_chooser)))
    }

    internal fun text(context: Context): String {
        val root = Environment.getCortexRoot(context)
        val sections = linkedMapOf<String, List<Pair<String, String?>>>()
        sections["Cortex"] = listOf(
            "version" to try {
                val info = context.packageManager.getPackageInfo(context.packageName, 0)
                @Suppress("DEPRECATION")
                "${info.versionName} (${info.versionCode})"
            } catch (e: Exception) {
                null
            },
            "package" to context.packageName,
            "system set up for" to readOrNull(File(root, ".cortex_version"))?.trim(),
            "current version" to BootstrapManager.CURRENT_BOOTSTRAP_VERSION.toString(),
            "root mode" to RootMode.isEnabled(context).toString()
        )
        sections["Android"] = listOf(
            "release" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "ABIs" to Build.SUPPORTED_ABIS.joinToString(),
            "free space" to try {
                "${StatFs(context.filesDir.path).availableBytes / (1024 * 1024)} MB"
            } catch (e: Exception) {
                null
            }
        )
        sections["Ubuntu"] = listOf(
            "release" to osRelease(readOrNull(File(root, "etc/os-release"))),
            "packages" to readOrNull(File(root, "var/lib/dpkg/status"))?.let { packageCount(it).toString() },
            "mirror" to MirrorSelector.status(root),
            "hook library" to File(root, "usr/lib/libcortex-hook.so").let { if (it.isFile) "${it.length()} bytes" else "missing" }
        )
        return format(sections) + "\n== Log (newest $LOG_LINES lines)\n" + appLog()
    }

    /** The sections as "== Name" headings with "key: value" lines; missing values are marked. */
    internal fun format(sections: Map<String, List<Pair<String, String?>>>): String = buildString {
        for ((name, entries) in sections) {
            append("== ").append(name).append('\n')
            for ((key, value) in entries) append(key).append(": ").append(value ?: "(unknown)").append('\n')
            append('\n')
        }
    }

    /** PRETTY_NAME of an os-release file. */
    internal fun osRelease(text: String?): String? =
        text?.lineSequence()?.firstOrNull { it.startsWith("PRETTY_NAME=") }
            ?.removePrefix("PRETTY_NAME=")?.trim('"')

    /** The installed packages of a dpkg status file. */
    internal fun packageCount(status: String): Int =
        status.lineSequence().count { it.startsWith("Status: ") && it.endsWith(" installed") }

    private fun readOrNull(file: File): String? = try {
        if (file.isFile) file.readText() else null
    } catch (e: Exception) {
        null
    }

    private fun appLog(): String = try {
        val process = ProcessBuilder("logcat", "-d", "-v", "time", "-t", LOG_LINES.toString())
            .redirectErrorStream(true)
            .start()
        val bytes = process.inputStream.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (out.size() < LOG_LIMIT) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        }
        process.destroy()
        String(bytes, Charsets.UTF_8)
    } catch (e: Exception) {
        "(the log could not be read: ${e.message})\n"
    }
}
