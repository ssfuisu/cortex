package org.cortex.terminal.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import org.cortex.terminal.BuildConfig
import org.cortex.terminal.R
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

object UpdateManager {

    private const val GITHUB_LATEST_RELEASE_URL =
        "https://api.github.com/repos/ssfuisu/cortex/releases/latest"

    data class ReleaseAsset(
        val name: String,
        val downloadUrl: String,
        val size: Long
    )

    data class ReleaseInfo(
        val tagName: String,
        val versionName: String,
        val name: String,
        val body: String,
        val asset: ReleaseAsset
    )

    var cachedReleaseInfo: ReleaseInfo? = null
        private set

    var isUpdateAvailable: Boolean = false
        private set

    private val mainHandler = Handler(Looper.getMainLooper())

    fun isNewerVersion(current: String, latest: String): Boolean {
        val cleanCurrent = current.trim().removePrefix("v").removePrefix("V")
        val cleanLatest = latest.trim().removePrefix("v").removePrefix("V")
        if (cleanCurrent == cleanLatest) return false

        val currentParts = cleanCurrent.split(".").mapNotNull { it.toIntOrNull() }
        val latestParts = cleanLatest.split(".").mapNotNull { it.toIntOrNull() }

        val maxLen = maxOf(currentParts.size, latestParts.size)
        for (i in 0 until maxLen) {
            val c = currentParts.getOrElse(i) { 0 }
            val l = latestParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }

    fun cleanUpdates(context: Context) {
        try {
            val updatesDir = File(context.cacheDir, "updates")
            if (updatesDir.exists()) {
                updatesDir.deleteRecursively()
            }
        } catch (e: Exception) {
            // Ignore cleanup errors
        }
    }

    fun checkForUpdate(
        context: Context,
        onResult: ((isAvailable: Boolean, info: ReleaseInfo?, error: String?) -> Unit)? = null
    ) {
        kotlin.concurrent.thread(name = "Cortex-UpdateCheck") {
            var connection: HttpURLConnection? = null
            try {
                val url = URL(GITHUB_LATEST_RELEASE_URL)
                connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 10000
                connection.readTimeout = 15000
                connection.setRequestProperty("User-Agent", "Cortex-Terminal-App")
                connection.setRequestProperty("Accept", "application/vnd.github.v3+json")

                val responseCode = connection.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    mainHandler.post {
                        onResult?.invoke(false, null, "Server responded with code $responseCode")
                    }
                    return@thread
                }

                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(responseText)
                val tagName = json.optString("tag_name", "")
                val releaseName = json.optString("name", tagName)
                val releaseBody = json.optString("body", "")
                val cleanLatest = tagName.removePrefix("v").removePrefix("V")

                val assetsArray = json.optJSONArray("assets")
                val assetsList = mutableListOf<ReleaseAsset>()
                if (assetsArray != null) {
                    for (i in 0 until assetsArray.length()) {
                        val assetObj = assetsArray.getJSONObject(i)
                        val name = assetObj.optString("name", "")
                        val downloadUrl = assetObj.optString("browser_download_url", "")
                        val size = assetObj.optLong("size", 0L)
                        if (name.endsWith(".apk", ignoreCase = true) && downloadUrl.isNotEmpty()) {
                            assetsList.add(ReleaseAsset(name, downloadUrl, size))
                        }
                    }
                }

                val selectedAsset = selectPreferredAsset(assetsList)
                if (selectedAsset == null) {
                    mainHandler.post {
                        onResult?.invoke(false, null, "No compatible APK asset found in release")
                    }
                    return@thread
                }

                val info = ReleaseInfo(
                    tagName = tagName,
                    versionName = cleanLatest,
                    name = releaseName,
                    body = releaseBody,
                    asset = selectedAsset
                )

                val available = isNewerVersion(BuildConfig.VERSION_NAME, cleanLatest)
                cachedReleaseInfo = info
                isUpdateAvailable = available

                mainHandler.post {
                    onResult?.invoke(available, info, null)
                }
            } catch (e: Exception) {
                mainHandler.post {
                    onResult?.invoke(false, null, e.localizedMessage ?: "Failed to connect to GitHub")
                }
            } finally {
                try {
                    connection?.disconnect()
                } catch (e: Exception) {}
            }
        }
    }

    private fun selectPreferredAsset(assets: List<ReleaseAsset>): ReleaseAsset? {
        if (assets.isEmpty()) return null
        val supportedAbis = Build.SUPPORTED_ABIS ?: emptyArray()
        val is64Bit = supportedAbis.any { it.contains("arm64") || it.contains("aarch64") }

        if (is64Bit) {
            val arm64Asset = assets.find { it.name.contains("arm64", ignoreCase = true) }
            if (arm64Asset != null) return arm64Asset
        }

        val arm32Asset = assets.find {
            it.name.contains("armeabi", ignoreCase = true) || it.name.contains("arm-v7a", ignoreCase = true)
        }
        if (arm32Asset != null) return arm32Asset

        return assets.firstOrNull()
    }

    fun showUpdateDialog(activity: Activity, info: ReleaseInfo) {
        if (activity.isFinishing || activity.isDestroyed) return

        val currentVer = "v${BuildConfig.VERSION_NAME}"
        val latestVer = if (info.tagName.startsWith("v", ignoreCase = true)) info.tagName else "v${info.tagName}"

        val messageBuilder = StringBuilder()
        messageBuilder.append("A new version of Cortex is available.\n\n")
        messageBuilder.append("Current version: $currentVer\n")
        messageBuilder.append("Latest version: $latestVer\n\n")

        val cleanNotes = cleanReleaseNotes(info.body)
        if (cleanNotes.isNotEmpty()) {
            messageBuilder.append("What's New:\n")
            messageBuilder.append(cleanNotes)
        }

        AlertDialog.Builder(activity)
            .setTitle("Update Available")
            .setMessage(messageBuilder.toString())
            .setPositiveButton("Update") { _, _ ->
                downloadAndInstall(activity, info)
            }
            .setNegativeButton("Later", null)
            .show()
    }

    fun showUpToDateDialog(activity: Activity, latestVersionTag: String?) {
        if (activity.isFinishing || activity.isDestroyed) return

        val currentVer = "v${BuildConfig.VERSION_NAME}"
        val latestVer = if (!latestVersionTag.isNullOrEmpty()) {
            if (latestVersionTag.startsWith("v", ignoreCase = true)) latestVersionTag else "v$latestVersionTag"
        } else {
            currentVer
        }

        AlertDialog.Builder(activity)
            .setTitle("Check for Updates")
            .setMessage("You are using the latest version of Cortex.\n\nCurrent version: $currentVer\nLatest version: $latestVer")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun cleanReleaseNotes(body: String): String {
        if (body.isBlank()) return ""
        val lines = body.lines()
        val filtered = lines
            .filter { line ->
                val trimmed = line.trim()
                !trimmed.startsWith("### Cortex Release") &&
                !trimmed.startsWith("Cortex turns your Android device") &&
                !trimmed.startsWith("#### Included Packages") &&
                !trimmed.startsWith("- `Cortex-")
            }
            .joinToString("\n")
            .trim()

        return if (filtered.length > 800) {
            filtered.substring(0, 800) + "..."
        } else {
            filtered
        }
    }

    fun downloadAndInstall(activity: Activity, info: ReleaseInfo) {
        if (activity.isFinishing || activity.isDestroyed) return

        cleanUpdates(activity)

        val updatesDir = File(activity.cacheDir, "updates")
        if (!updatesDir.exists()) updatesDir.mkdirs()

        val targetApk = File(updatesDir, info.asset.name)

        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_update_download, null)
        val statusText = dialogView.findViewById<TextView>(R.id.downloadStatusText)
        val progressBar = dialogView.findViewById<ProgressBar>(R.id.downloadProgressBar)
        val percentText = dialogView.findViewById<TextView>(R.id.downloadPercentText)

        statusText.text = "Connecting to download server..."
        progressBar.isIndeterminate = true

        var isCancelled = false
        var activeConnection: HttpURLConnection? = null

        val downloadDialog = AlertDialog.Builder(activity)
            .setTitle("Downloading Update")
            .setView(dialogView)
            .setCancelable(false)
            .setNegativeButton("Cancel") { _, _ ->
                isCancelled = true
                try {
                    activeConnection?.disconnect()
                } catch (e: Exception) {}
                cleanUpdates(activity)
            }
            .create()

        downloadDialog.show()

        kotlin.concurrent.thread(name = "Cortex-ApkDownloader") {
            var inputStream: InputStream? = null
            var outputStream: FileOutputStream? = null
            try {
                var currentUrl = info.asset.downloadUrl
                var redirects = 0
                var connection: HttpURLConnection

                while (true) {
                    val url = URL(currentUrl)
                    connection = url.openConnection() as HttpURLConnection
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 15000
                    connection.readTimeout = 30000
                    connection.setRequestProperty("User-Agent", "Cortex-Terminal-App")
                    connection.setRequestProperty("Accept", "*/*")

                    activeConnection = connection
                    val responseCode = connection.responseCode

                    if (responseCode in 300..399) {
                        val newLocation = connection.getHeaderField("Location")
                        connection.disconnect()
                        if (newLocation.isNullOrEmpty() || ++redirects > 5) {
                            throw Exception("Too many redirects or missing Location header")
                        }
                        currentUrl = newLocation
                    } else if (responseCode == HttpURLConnection.HTTP_OK) {
                        break
                    } else {
                        throw Exception("HTTP download error: $responseCode")
                    }
                }

                if (isCancelled) {
                    cleanUpdates(activity)
                    return@thread
                }

                val totalBytes = connection.contentLength.toLong().let {
                    if (it > 0) it else info.asset.size
                }

                mainHandler.post {
                    if (!isCancelled && downloadDialog.isShowing) {
                        progressBar.isIndeterminate = false
                        progressBar.max = 100
                        progressBar.progress = 0
                        statusText.text = "Downloading ${info.asset.name}..."
                    }
                }

                inputStream = connection.inputStream
                outputStream = FileOutputStream(targetApk)

                val buffer = ByteArray(32768)
                var bytesRead: Int
                var totalRead = 0L
                var lastUpdate = System.currentTimeMillis()

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    if (isCancelled) {
                        outputStream.close()
                        inputStream.close()
                        cleanUpdates(activity)
                        return@thread
                    }

                    outputStream.write(buffer, 0, bytesRead)
                    totalRead += bytesRead

                    val now = System.currentTimeMillis()
                    if (now - lastUpdate > 150 || totalRead == totalBytes) {
                        lastUpdate = now
                        val percent = if (totalBytes > 0) {
                            ((totalRead * 100) / totalBytes).toInt().coerceIn(0, 100)
                        } else {
                            0
                        }
                        val downloadedMB = String.format(Locale.US, "%.1f", totalRead / (1024.0 * 1024.0))
                        val totalMB = String.format(Locale.US, "%.1f", totalBytes / (1024.0 * 1024.0))

                        mainHandler.post {
                            if (!isCancelled && downloadDialog.isShowing) {
                                progressBar.progress = percent
                                percentText.text = "$percent% ($downloadedMB MB / $totalMB MB)"
                            }
                        }
                    }
                }

                outputStream.flush()
                outputStream.close()
                outputStream = null
                inputStream.close()
                inputStream = null

                mainHandler.post {
                    if (!isCancelled && downloadDialog.isShowing) {
                        try {
                            downloadDialog.dismiss()
                        } catch (e: Exception) {}
                        launchInstall(activity, targetApk)
                    }
                }
            } catch (e: Exception) {
                try { outputStream?.close() } catch (ex: Exception) {}
                try { inputStream?.close() } catch (ex: Exception) {}
                cleanUpdates(activity)

                mainHandler.post {
                    if (!isCancelled && downloadDialog.isShowing) {
                        try { downloadDialog.dismiss() } catch (ex: Exception) {}
                        Toast.makeText(
                            activity,
                            "Download failed: ${e.localizedMessage}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    private fun launchInstall(activity: Activity, apkFile: File) {
        if (activity.isFinishing || activity.isDestroyed) return

        if (!apkFile.exists() || apkFile.length() == 0L) {
            Toast.makeText(activity, "Downloaded APK file is missing or corrupted", Toast.LENGTH_SHORT).show()
            cleanUpdates(activity)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!activity.packageManager.canRequestPackageInstalls()) {
                AlertDialog.Builder(activity)
                    .setTitle("Install Permission Required")
                    .setMessage("Cortex needs permission to install downloaded APK updates. Please enable 'Allow from this source' for Cortex in system settings.")
                    .setPositiveButton("Settings") { _, _ ->
                        try {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:${activity.packageName}")
                            )
                            activity.startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(activity, "Failed to open settings: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("Cancel") { _, _ ->
                        cleanUpdates(activity)
                    }
                    .setOnCancelListener {
                        cleanUpdates(activity)
                    }
                    .show()
                return
            }
        }

        try {
            val apkUri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(installIntent)
        } catch (e: Exception) {
            Toast.makeText(activity, "Failed to launch installer: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            cleanUpdates(activity)
        }
    }
}
