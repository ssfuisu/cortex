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
import org.cortex.terminal.R
import org.json.JSONObject
import android.content.pm.PackageManager
import android.content.pm.SigningInfo
import android.system.Os
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

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
    private val isDownloading = AtomicBoolean(false)
    @Volatile
    private var pendingInstallUri: Uri? = null

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

    fun getInstalledVersionName(context: Context): String {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            packageInfo.versionName ?: "1.24.55"
        } catch (e: Exception) {
            "1.24.55"
        }
    }

    fun cleanUpdates(context: Context) {
        if (isDownloading.get()) return
        cleanUpdatesForce(context)
    }

    private fun cleanUpdatesForce(context: Context) {
        try {
            val uri = pendingInstallUri
            if (uri != null) {
                pendingInstallUri = null
                try {
                    context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {}
            }
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

                val available = isNewerVersion(getInstalledVersionName(context), cleanLatest)
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

    fun openUnknownAppSourcesSettings(activity: Activity) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}")
                )
                activity.startActivity(intent)
            }
        } catch (e: Exception) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES))
                }
            } catch (ex: Exception) {
                Toast.makeText(activity, "Failed to open settings: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun showUpdateDialog(activity: Activity, info: ReleaseInfo) {
        if (activity.isFinishing || activity.isDestroyed) return

        val currentVer = "v${getInstalledVersionName(activity)}"
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
                    Toast.makeText(
                        activity,
                        "Lütfen Cortex için bilinmeyen uygulamaları yüklemeye izin verin",
                        Toast.LENGTH_LONG
                    ).show()
                    openUnknownAppSourcesSettings(activity)
                } else {
                    downloadAndInstall(activity, info)
                }
            }
            .setNegativeButton("Later", null)
            .show()
    }

    fun showUpToDateDialog(activity: Activity, latestVersionTag: String?) {
        if (activity.isFinishing || activity.isDestroyed) return

        val currentVer = "v${getInstalledVersionName(activity)}"
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(
                activity,
                "Lütfen Cortex için bilinmeyen uygulamaları yüklemeye izin verin",
                Toast.LENGTH_LONG
            ).show()
            openUnknownAppSourcesSettings(activity)
            return
        }

        if (!isDownloading.compareAndSet(false, true)) {
            return
        }

        cleanUpdatesForce(activity)

        val updatesDir = File(activity.cacheDir, "updates")
        if (!updatesDir.exists()) updatesDir.mkdirs()
        updatesDir.setReadable(false, false)
        updatesDir.setReadable(true, true)
        updatesDir.setWritable(false, false)
        updatesDir.setWritable(true, true)
        updatesDir.setExecutable(false, false)
        updatesDir.setExecutable(true, true)
        try { Os.chmod(updatesDir.absolutePath, 448) } catch (_: Exception) {}

        val targetApk = try {
            File.createTempFile("cortex_update_", ".apk", updatesDir).apply {
                setReadable(false, false)
                setReadable(true, true)
                setWritable(false, false)
                setWritable(true, true)
                try { Os.chmod(absolutePath, 384) } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            isDownloading.set(false)
            Toast.makeText(activity, "Failed to create temporary update file", Toast.LENGTH_SHORT).show()
            return
        }

        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_update_download, null)
        val statusText = dialogView.findViewById<TextView>(R.id.downloadStatusText)
        val progressBar = dialogView.findViewById<ProgressBar>(R.id.downloadProgressBar)
        val percentText = dialogView.findViewById<TextView>(R.id.downloadPercentText)

        statusText.text = "Connecting to download server..."
        progressBar.isIndeterminate = true

        val isCancelled = AtomicBoolean(false)
        val activeConnection = java.util.concurrent.atomic.AtomicReference<HttpURLConnection?>(null)

        val downloadDialog = AlertDialog.Builder(activity)
            .setTitle("Downloading Update")
            .setView(dialogView)
            .setCancelable(false)
            .setNegativeButton("Cancel") { _, _ ->
                isCancelled.set(true)
                try {
                    activeConnection.get()?.disconnect()
                } catch (e: Exception) {}
            }
            .create()

        downloadDialog.show()

        kotlin.concurrent.thread(name = "Cortex-ApkDownloader") {
            var inputStream: InputStream? = null
            var outputStream: FileOutputStream? = null
            var downloadSucceeded = false
            try {
                var currentUrl = info.asset.downloadUrl
                var redirects = 0
                var connection: HttpURLConnection

                while (true) {
                    val url = URL(currentUrl)
                    if (!url.protocol.equals("https", ignoreCase = true)) {
                        throw SecurityException("Insecure HTTP redirect rejected: $currentUrl")
                    }
                    connection = url.openConnection() as HttpURLConnection
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 15000
                    connection.readTimeout = 30000
                    connection.setRequestProperty("User-Agent", "Cortex-Terminal-App")
                    connection.setRequestProperty("Accept", "*/*")

                    activeConnection.set(connection)
                    val responseCode = connection.responseCode

                    if (responseCode in 300..399) {
                        val newLocation = connection.getHeaderField("Location")
                        connection.disconnect()
                        if (newLocation.isNullOrEmpty() || ++redirects > 5) {
                            throw IOException("Too many redirects or missing Location header")
                        }
                        currentUrl = newLocation
                    } else if (responseCode == HttpURLConnection.HTTP_OK) {
                        break
                    } else {
                        throw IOException("HTTP download error: $responseCode")
                    }
                }

                if (isCancelled.get()) {
                    return@thread
                }

                val contentLength = connection.contentLengthLong
                val totalBytes = if (contentLength > 0L) contentLength else info.asset.size

                mainHandler.post {
                    if (!isCancelled.get() && downloadDialog.isShowing) {
                        progressBar.isIndeterminate = false
                        progressBar.max = 100
                        progressBar.progress = 0
                        statusText.text = "Downloading ${info.asset.name}..."
                    }
                }

                // Ensure 0600 permissions on targetApk BEFORE writing downloaded bytes
                try {
                    targetApk.setReadable(false, false)
                    targetApk.setReadable(true, true)
                    targetApk.setWritable(false, false)
                    targetApk.setWritable(true, true)
                    Os.chmod(targetApk.absolutePath, 384)
                } catch (_: Exception) {}

                inputStream = connection.inputStream
                outputStream = FileOutputStream(targetApk)

                val buffer = ByteArray(32768)
                var bytesRead: Int
                var totalRead = 0L
                var lastUpdate = System.currentTimeMillis()

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    if (isCancelled.get()) {
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
                            if (!isCancelled.get() && downloadDialog.isShowing) {
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

                if (isCancelled.get()) {
                    return@thread
                }

                if (totalRead <= 0L) {
                    throw IOException("Downloaded APK is empty")
                }
                if (info.asset.size > 0L && totalRead != info.asset.size) {
                    throw IOException("Downloaded APK size ($totalRead bytes) does not match release asset size (${info.asset.size} bytes)")
                }
                if (totalBytes > 0L && totalRead != totalBytes) {
                    throw IOException("Incomplete APK download ($totalRead of $totalBytes bytes)")
                }

                // Enforce owner-only permissions (0600) on downloaded APK
                try {
                    targetApk.setReadable(false, false)
                    targetApk.setReadable(true, true)
                    targetApk.setWritable(false, false)
                    targetApk.setWritable(true, true)
                    Os.chmod(targetApk.absolutePath, 384)
                } catch (_: Exception) {}

                downloadSucceeded = true

                mainHandler.post {
                    if (!isCancelled.get() && downloadDialog.isShowing) {
                        try {
                            downloadDialog.dismiss()
                        } catch (e: Exception) {}
                        launchInstall(activity, targetApk)
                    } else {
                        try { targetApk.delete() } catch (_: Exception) {}
                        cleanUpdates(activity)
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    if (!isCancelled.get() && downloadDialog.isShowing) {
                        try { downloadDialog.dismiss() } catch (ex: Exception) {}
                        AlertDialog.Builder(activity)
                            .setTitle("Update Download Failed")
                            .setMessage("Failed to download update: ${e.localizedMessage}\n\nWould you like to retry?")
                            .setPositiveButton("Retry") { _, _ ->
                                downloadAndInstall(activity, info)
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                }
            } finally {
                try { outputStream?.close() } catch (_: Exception) {}
                try { inputStream?.close() } catch (_: Exception) {}
                try { activeConnection.get()?.disconnect() } catch (_: Exception) {}
                isDownloading.set(false)
                if (!downloadSucceeded || isCancelled.get()) {
                    try { targetApk.delete() } catch (_: Exception) {}
                    cleanUpdatesForce(activity)
                }
            }
        }
    }

    internal fun isVersionUpgrade(installedVersionCode: Long, archiveVersionCode: Long): Boolean =
        archiveVersionCode > installedVersionCode

    internal fun isAuthorizedSigner(
        currentActiveSigners: Set<String>,
        archiveActiveSigners: Set<String>,
        archiveLineageSigners: List<String>,
        hasMultipleSigners: Boolean
    ): Boolean {
        if (hasMultipleSigners) {
            return currentActiveSigners.isNotEmpty() && currentActiveSigners == archiveActiveSigners
        }
        if (currentActiveSigners.size != 1 || archiveActiveSigners.size != 1) {
            return false
        }
        if (currentActiveSigners == archiveActiveSigners) {
            return true
        }
        val currentActive = currentActiveSigners.first()
        val archiveActive = archiveActiveSigners.first()
        return archiveLineageSigners.isNotEmpty() &&
            archiveLineageSigners.last() == archiveActive &&
            currentActive in archiveLineageSigners
    }

    private fun getPackageVersionCode(info: android.content.pm.PackageInfo): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }

    private fun verifyApkSignature(context: Context, apkFile: File): Boolean {
        try {
            val pm = context.packageManager
            val currentPkg = context.packageName

            val archiveInfo: android.content.pm.PackageInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageArchiveInfo(apkFile.path, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(apkFile.path, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(apkFile.path, PackageManager.GET_SIGNATURES)
            }
            if (archiveInfo == null) return false

            if (archiveInfo.packageName != currentPkg) {
                Log.e("UpdateManager", "APK package mismatch: expected $currentPkg, found ${archiveInfo.packageName}")
                return false
            }

            val currentInfo: android.content.pm.PackageInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(currentPkg, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(currentPkg, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(currentPkg, PackageManager.GET_SIGNATURES)
            }
            if (currentInfo == null) return false

            val currentVerCode = getPackageVersionCode(currentInfo)
            val archiveVerCode = getPackageVersionCode(archiveInfo)
            if (!isVersionUpgrade(currentVerCode, archiveVerCode)) {
                Log.e("UpdateManager", "APK version downgrade/replay rejected: installed=$currentVerCode, archive=$archiveVerCode")
                return false
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val currentSigning = currentInfo.signingInfo ?: return false
                val archiveSigning = archiveInfo.signingInfo ?: return false

                if (currentSigning.hasMultipleSigners() != archiveSigning.hasMultipleSigners()) {
                    Log.e("UpdateManager", "APK multi-signer state mismatch")
                    return false
                }

                val hasMulti = currentSigning.hasMultipleSigners()
                val currentActive = currentSigning.apkContentsSigners?.map { it.toCharsString() }?.toSet()?.takeIf { it.isNotEmpty() }
                    ?: currentSigning.signingCertificateHistory?.lastOrNull()?.let { setOf(it.toCharsString()) }
                    ?: emptySet()
                val archiveActive = archiveSigning.apkContentsSigners?.map { it.toCharsString() }?.toSet()?.takeIf { it.isNotEmpty() }
                    ?: archiveSigning.signingCertificateHistory?.lastOrNull()?.let { setOf(it.toCharsString()) }
                    ?: emptySet()
                val archiveLineage = archiveSigning.signingCertificateHistory?.map { it.toCharsString() } ?: emptyList()

                if (!isAuthorizedSigner(currentActive, archiveActive, archiveLineage, hasMulti)) {
                    Log.e("UpdateManager", "APK signing certificate is not authorized for installed application")
                    return false
                }
            } else {
                @Suppress("DEPRECATION")
                val currentSigs = currentInfo.signatures?.map { it.toCharsString() }?.toSet() ?: emptySet()
                @Suppress("DEPRECATION")
                val archiveSigs = archiveInfo.signatures?.map { it.toCharsString() }?.toSet() ?: emptySet()
                if (currentSigs.isEmpty() || currentSigs != archiveSigs) {
                    Log.e("UpdateManager", "APK signature does not match installed application")
                    return false
                }
            }
            return true
        } catch (e: Exception) {
            Log.e("UpdateManager", "Failed to verify downloaded APK signature", e)
            return false
        }
    }

    private fun launchInstall(activity: Activity, apkFile: File) {
        if (activity.isFinishing || activity.isDestroyed) {
            try { apkFile.delete() } catch (_: Exception) {}
            cleanUpdatesForce(activity)
            return
        }

        if (!apkFile.exists() || apkFile.length() <= 0L) {
            Toast.makeText(activity, "Downloaded APK file is missing or corrupted", Toast.LENGTH_SHORT).show()
            try { apkFile.delete() } catch (_: Exception) {}
            cleanUpdatesForce(activity)
            return
        }

        if (!verifyApkSignature(activity, apkFile)) {
            try { apkFile.delete() } catch (_: Exception) {}
            cleanUpdatesForce(activity)
            AlertDialog.Builder(activity)
                .setTitle("Update Verification Failed")
                .setMessage("The downloaded update could not be verified or its digital signature does not match this app.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!activity.packageManager.canRequestPackageInstalls()) {
                AlertDialog.Builder(activity)
                    .setTitle("Install Permission Required")
                    .setMessage("Cortex needs permission to install downloaded APK updates. Please enable 'Allow from this source' for Cortex in system settings.")
                    .setPositiveButton("Settings") { _, _ ->
                        openUnknownAppSourcesSettings(activity)
                    }
                    .setNegativeButton("Cancel") { _, _ ->
                        try { apkFile.delete() } catch (_: Exception) {}
                        cleanUpdatesForce(activity)
                    }
                    .setOnCancelListener {
                        try { apkFile.delete() } catch (_: Exception) {}
                        cleanUpdatesForce(activity)
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
            pendingInstallUri = apkUri

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(installIntent)
        } catch (e: Exception) {
            Toast.makeText(activity, "Failed to launch installer: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            try { apkFile.delete() } catch (_: Exception) {}
            cleanUpdatesForce(activity)
        }
    }
}

