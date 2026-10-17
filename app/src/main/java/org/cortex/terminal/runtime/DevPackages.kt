package org.cortex.terminal.runtime

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.util.zip.GZIPInputStream

/** The in-process lock for rewriting var/lib/dpkg/status; held for the moment of the write only. */
internal object DpkgStatus {
    val lock = Any()
}

/**
 * The C development files for the glibc that Cortex runs: libc6-dev and the four packages it needs,
 * registered in dpkg's database so that apt sees them as installed.
 *
 * Why they come with the app: the libc6 in the root file system is patched for Android's seccomp filter
 * and kept away from apt, so Ubuntu's libc6-dev (which needs exactly the installed libc6, and whose
 * name matches the libc6* pin) could never be installed, nor build-essential, g++ or any -dev package
 * behind it. The overlay is built by scripts/build-dev-overlay.py from the stock packages; the only
 * change is that the exact libc6 and libcrypt1 versions in Depends became "at least", so it also fits
 * the older libc6 of an old installation.
 *
 * Runs on a background thread, once per root file system, and does nothing when the packages are
 * there already (installed by the user, or by an earlier run).
 */
object DevPackages {
    enum class Result {
        /** The packages were unpacked and registered. */
        INSTALLED,

        /** An earlier run did this. */
        ALREADY_DONE,

        /** Some of the packages are registered already; they are left alone. */
        NOT_NEEDED,

        /** No matching libc6, no overlay in the app, or no dpkg database. */
        NOT_POSSIBLE,

        /** apt or dpkg is running; the next start tries again. */
        BUSY,

        /** Unpacking or registering failed; the next start tries again. */
        FAILED
    }

    class Outcome(val result: Result, val detail: String = "") {
        override fun toString() = "$result${if (detail.isEmpty()) "" else ": $detail"}"
    }

    val PACKAGES = listOf("libc6-dev", "libc-dev-bin", "libcrypt-dev", "linux-libc-dev", "rpcsvc-proto")

    /** The glibc series the overlay was built for. */
    const val GLIBC_SERIES = "2.39"

    /** Path of the stanzas inside the overlay, relative to the root file system. */
    const val STATUS_RESOURCE = "var/lib/cortex/devpkgs/status"
    const val DONE_MARKER = "var/lib/cortex/devpkgs/installed"

    fun assetName(is64Bit: Boolean) = if (is64Bit) "devpkgs-arm64.tar.gz" else "devpkgs-arm.tar.gz"

    private val installing = java.util.concurrent.locks.ReentrantLock()

    /**
     * Installs the overlay if this root file system needs it. Never throws.
     *
     * @param openOverlay opens the gzip-compressed overlay, or returns null when the app has none
     * @param extract unpacks a tar file into the root file system; true when it worked
     */
    fun ensureInstalled(
        root: File,
        tmpDir: File,
        openOverlay: () -> InputStream?,
        extract: (tar: File, root: File) -> Boolean,
        log: (String) -> Unit = {}
    ): Outcome {
        if (!installing.tryLock()) return Outcome(Result.BUSY, "another thread is installing")
        return try {
            install(root, tmpDir, openOverlay, extract).also { log("C development packages: $it") }
        } catch (t: Throwable) {
            Outcome(Result.FAILED, t.toString()).also { log("C development packages: $it") }
        } finally {
            installing.unlock()
        }
    }

    private fun install(
        root: File,
        tmpDir: File,
        openOverlay: () -> InputStream?,
        extract: (File, File) -> Boolean
    ): Outcome {
        val marker = File(root, DONE_MARKER)
        if (marker.exists()) return Outcome(Result.ALREADY_DONE)

        val statusFile = File(root, "var/lib/dpkg/status")
        if (!statusFile.isFile) return Outcome(Result.NOT_POSSIBLE, "no dpkg database")
        val status = statusFile.readText()
        val libc = installedVersion(status, "libc6")
            ?: return Outcome(Result.NOT_POSSIBLE, "libc6 is not installed")
        if (!libc.startsWith("$GLIBC_SERIES-")) {
            return Outcome(Result.NOT_POSSIBLE, "libc6 $libc is not from glibc $GLIBC_SERIES")
        }
        val present = PACKAGES.filter { isRegistered(status, it) }
        if (present.isNotEmpty()) {
            writeMarker(marker, "already registered: ${present.joinToString()}")
            return Outcome(Result.NOT_NEEDED, present.joinToString())
        }

        val overlay = openOverlay() ?: return Outcome(Result.NOT_POSSIBLE, "this build has no overlay")
        val locks = tryLockDpkg(root) ?: run {
            overlay.close()
            return Outcome(Result.BUSY, "dpkg is in use")
        }
        val tar = File(tmpDir, "devpkgs.tar")
        try {
            tmpDir.mkdirs()
            overlay.use { raw -> GZIPInputStream(raw).use { gz -> tar.outputStream().use { gz.copyTo(it) } } }
            if (!extract(tar, root)) return Outcome(Result.FAILED, "unpacking failed")

            val additions = File(root, STATUS_RESOURCE)
            if (!additions.isFile) return Outcome(Result.FAILED, "overlay has no status stanzas")
            val merged = synchronized(DpkgStatus.lock) {
                val current = statusFile.readText()
                val updated = mergeStatus(current, additions.readText())
                if (updated != null) writeAtomically(statusFile, updated)
                updated != null
            }
            val after = statusFile.readText()
            val missing = PACKAGES.filter { !isRegistered(after, it) }
            if (missing.isNotEmpty()) return Outcome(Result.FAILED, "not registered: ${missing.joinToString()}")
            writeMarker(marker, "libc6 $libc: ${PACKAGES.joinToString()}")
            return Outcome(Result.INSTALLED, if (merged) "libc6 $libc" else "libc6 $libc, already merged")
        } finally {
            tar.delete()
            locks.close()
        }
    }

    /** The version of an installed package, or null. A package in the status file may also be removed. */
    internal fun installedVersion(status: String, name: String): String? {
        for (stanza in stanzas(status)) {
            if (field(stanza, "Package") != name) continue
            val state = field(stanza, "Status") ?: continue
            if (!state.endsWith(" installed")) continue
            return field(stanza, "Version")
        }
        return null
    }

    internal fun isRegistered(status: String, name: String) = stanzas(status).any { field(it, "Package") == name }

    /**
     * [current] plus the stanzas of [additions] for packages that [current] does not know, or null
     * when there is nothing to add. The result ends like dpkg's own file: stanzas separated by a blank
     * line, a blank line after the last one.
     */
    internal fun mergeStatus(current: String, additions: String): String? {
        val known = stanzas(current).mapNotNull { field(it, "Package") }.toSet()
        val fresh = stanzas(additions).filter { field(it, "Package") !in known }
        if (fresh.isEmpty()) return null
        val sb = StringBuilder(current.trimEnd('\n'))
        sb.append("\n\n")
        for (s in fresh) sb.append(s.trimEnd('\n')).append("\n\n")
        return sb.toString()
    }

    private fun stanzas(text: String): List<String> = text.split(Regex("\n{2,}")).filter { it.isNotBlank() }

    private fun field(stanza: String, name: String): String? {
        val prefix = "$name:"
        for (line in stanza.lineSequence()) {
            if (line.startsWith(prefix)) return line.substring(prefix.length).trim()
        }
        return null
    }

    private fun writeAtomically(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".cortex-new")
        java.io.FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        tmp.setReadable(true, true)
        tmp.setWritable(true, true)
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw java.io.IOException("could not replace ${target.path}")
        }
    }

    private fun writeMarker(marker: File, text: String) {
        marker.parentFile?.mkdirs()
        marker.writeText(text + "\n")
    }

    /** dpkg's two locks, the way dpkg takes them, so that a running apt or dpkg and this never overlap. */
    private class DpkgLocks(private val files: List<RandomAccessFile>, private val locks: List<FileLock>) : AutoCloseable {
        override fun close() {
            locks.forEach { try { it.release() } catch (_: Exception) {} }
            files.forEach { try { it.close() } catch (_: Exception) {} }
        }
    }

    private fun tryLockDpkg(root: File): DpkgLocks? {
        val dir = File(root, "var/lib/dpkg")
        val files = mutableListOf<RandomAccessFile>()
        val locks = mutableListOf<FileLock>()
        try {
            for (name in listOf("lock-frontend", "lock")) {
                val raf = RandomAccessFile(File(dir, name), "rw")
                files.add(raf)
                val lock = try { raf.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                if (lock == null) {
                    DpkgLocks(files, locks).close()
                    return null
                }
                locks.add(lock)
            }
        } catch (e: java.io.IOException) {
            DpkgLocks(files, locks).close()
            return null
        }
        return DpkgLocks(files, locks)
    }
}
