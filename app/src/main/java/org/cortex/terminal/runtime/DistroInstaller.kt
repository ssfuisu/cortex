package org.cortex.terminal.runtime

import android.content.Context
import android.os.Process
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** One image of the catalog: a distribution for this phone's architecture */
data class DistroImage(
    val id: String,
    val name: String,
    val version: String,
    val pm: PackageManager,
    val file: String,
    val sha256: String,
    val size: Long,
    val unpacked: Long
)

/**
 * The distributions besides the app's own Ubuntu: their catalog (distros.json of the "distros"
 * release, which build-distros.yml publishes), their download, checked against the catalog's
 * SHA-256 before a byte of it is unpacked, and the switch between installed ones.
 *
 * The active system is always files/cortex (every path the hook, the sessions and the tools know);
 * the others wait in files/distros/ID, each with its own /home, and trade places with it by rename.
 */
object DistroInstaller {
    private const val TAG = "DistroInstaller"
    const val RELEASE_URL = "https://github.com/ssfuisu/cortex/releases/download/distros/"

    fun arch(): String = if (Process.is64Bit()) "arm64" else "armhf"

    fun storeDir(context: Context): File = File(context.filesDir, "distros")

    /** The catalog's images for this phone (newest download each time: it changes when the images are rebuilt) */
    @Throws(IOException::class)
    fun fetchCatalog(): List<DistroImage> {
        val text = httpGet(RELEASE_URL + "distros.json").toString(Charsets.UTF_8)
        return parseCatalog(text, arch())
    }

    fun parseCatalog(text: String, arch: String): List<DistroImage> {
        val root = JSONObject(text)
        if (root.optInt("format") != 1) throw IOException("unknown catalog format ${root.optInt("format")}")
        val images = root.getJSONArray("images")
        val out = mutableListOf<DistroImage>()
        for (i in 0 until images.length()) {
            val o = images.getJSONObject(i)
            if (o.optString("arch") != arch) continue
            val pm = PackageManager.of(o.optString("pm")) ?: continue
            val sha = o.optString("sha256")
            val file = o.optString("file")
            // only a plain file name of this release, and a real hash: nothing else is downloaded
            if (!Regex("^[0-9a-f]{64}$").matches(sha) || !Regex("^cortex-[A-Za-z0-9._-]+\\.tar\\.zst$").matches(file)) continue
            out += DistroImage(o.getString("id"), o.optString("name"), o.optString("version"), pm, file, sha,
                o.optLong("size"), o.optLong("unpacked"))
        }
        return out
    }

    private fun httpGet(url: String): ByteArray {
        val c = open(url)
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode} for $url")
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000
        c.readTimeout = 60_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "Cortex-Terminal")
        return c
    }

    /**
     * Downloads [image] into the cache (resumed where an interrupted download stopped) and checks its
     * size and SHA-256. [onProgress] gets the share done (0-100). Returns the checked file.
     */
    @Throws(IOException::class)
    fun download(context: Context, image: DistroImage, onProgress: (Int) -> Unit, cancelled: () -> Boolean = { false }): File {
        val done = File(context.cacheDir, image.file)
        if (done.isFile && done.length() == image.size && sha256(done) == image.sha256) return done
        done.delete()
        val part = File(context.cacheDir, image.file + ".part")
        var attempt = 0
        while (true) {
            try {
                fetchInto(image, part, onProgress, cancelled)
                break
            } catch (e: IOException) {
                if (cancelled() || ++attempt >= 4) throw e
                Log.w(TAG, "Download of ${image.file} interrupted, again (${e.message})")
                Thread.sleep(2000L * attempt)
            }
        }
        if (part.length() != image.size) {
            part.delete()
            throw IOException("${image.file}: ${part.length()} bytes, the catalog says ${image.size}")
        }
        val got = sha256(part)
        if (got != image.sha256) {
            part.delete()
            throw IOException("${image.file}: SHA-256 $got, the catalog says ${image.sha256}")
        }
        if (!part.renameTo(done)) throw IOException("could not keep ${done.path}")
        return done
    }

    private fun fetchInto(image: DistroImage, part: File, onProgress: (Int) -> Unit, cancelled: () -> Boolean) {
        val have = if (part.isFile && part.length() < image.size) part.length() else 0L
        if (have == 0L) part.delete()
        val c = open(RELEASE_URL + image.file)
        if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
        try {
            val resumed = c.responseCode == 206
            if (c.responseCode != 200 && !resumed) throw IOException("HTTP ${c.responseCode} for ${image.file}")
            var written = if (resumed) have else 0L
            java.io.FileOutputStream(part, resumed).use { out ->
                c.inputStream.use { input ->
                    val buf = ByteArray(256 * 1024)
                    var lastShown = -1
                    while (true) {
                        if (cancelled()) throw IOException("cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        written += n
                        val pct = if (image.size > 0) (written * 100 / image.size).toInt().coerceIn(0, 100) else 0
                        if (pct != lastShown) {
                            lastShown = pct
                            onProgress(pct)
                        }
                    }
                }
            }
        } finally {
            c.disconnect()
        }
    }

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** The checked archive unpacked as the active system (files/cortex must be empty or absent) */
    fun installArchive(context: Context, archive: File, onProgress: (Int) -> Unit): Boolean =
        BootstrapManager.installBootstrap(context, { FileInputStream(archive) }, onProgress = onProgress, bundled = false)

    /** The ids of the systems that wait in files/distros */
    fun stored(context: Context): List<String> =
        storeDir(context).listFiles()?.filter { it.isDirectory && File(it, "usr/bin/bash").exists() }
            ?.map { Distro.of(it).id }?.sorted() ?: emptyList()

    /** The active system, when one is installed */
    fun active(context: Context): InstalledDistro? {
        val root = Environment.getCortexRoot(context)
        return if (File(root, "usr/bin/bash").exists()) Distro.of(root) else null
    }

    /**
     * Puts the active system aside (files/distros/ITS-ID) and brings [id] back if it waits there.
     * Returns true when [id] is now active, false when files/cortex is empty for an install of it.
     * No session may run: the caller ends them first.
     */
    @Throws(IOException::class)
    @Synchronized
    fun makeActive(context: Context, id: String): Boolean {
        val root = Environment.getCortexRoot(context)
        val store = storeDir(context).apply { mkdirs() }
        active(context)?.let { current ->
            if (current.id == id) return true
            val aside = File(store, current.id)
            if (aside.exists()) throw IOException("${aside.path} is in the way")
            if (!root.renameTo(aside)) throw IOException("could not move ${current.id} aside")
        }
        if (root.exists() && root.list()?.isNotEmpty() == true) {
            // what a failed install left: not a system, kept out of the way
            val left = File(store, ".incomplete-${System.currentTimeMillis()}")
            if (!root.renameTo(left)) throw IOException("could not clear ${root.path}")
            Thread { org.cortex.terminal.documents.DocumentPaths.deleteTree(left) }.start()
        }
        val waiting = File(store, id)
        if (waiting.isDirectory) {
            if (!waiting.renameTo(root)) throw IOException("could not bring $id back")
            return true
        }
        root.mkdirs()
        return false
    }

    /** Takes away what an installation that stopped half way left in files/cortex */
    fun discardActive(context: Context) {
        val root = Environment.getCortexRoot(context)
        if (!root.exists()) return
        val left = File(storeDir(context).apply { mkdirs() }, ".incomplete-${System.currentTimeMillis()}")
        if (root.renameTo(left)) {
            Thread { org.cortex.terminal.documents.DocumentPaths.deleteTree(left) }.start()
        } else {
            org.cortex.terminal.documents.DocumentPaths.deleteTree(root)
        }
    }

    /** Deletes a system that waits in files/distros (never the active one) */
    fun delete(context: Context, id: String) {
        val dir = File(storeDir(context), id)
        if (dir.isDirectory) org.cortex.terminal.documents.DocumentPaths.deleteTree(dir)
    }
}
