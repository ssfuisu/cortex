package org.cortex.terminal.runtime

import android.os.Build
import android.system.Os
import android.util.Log
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

/**
 * Installs the files Cortex manages in the root file system so that the programs running there do not
 * notice: a file that already holds the content is left alone (no write, no new inode), and new content
 * goes to a temporary file that then takes the old one's place. A shell reading /etc/passwd or a
 * profile script at that moment sees the old or the new file, never an empty or half-written one.
 */
internal object FileInstaller {
    private const val TAG = "FileInstaller"

    /** Installs [text] at [file] with [mode] (420 = 0644, 448 = 0700...); true when it had to be written. */
    fun install(file: File, text: String, mode: Int): Boolean = install(file, text.toByteArray(Charsets.UTF_8), mode)

    // One install at a time: two threads installing the same file (the DNS update on resume and the
    // start-up maintenance) would share the temporary file.
    @Synchronized
    fun install(file: File, bytes: ByteArray, mode: Int): Boolean {
        if (holds(file, bytes)) {
            chmod(file, mode)
            return false
        }
        val dir = file.absoluteFile.parentFile ?: throw IOException("${file.path} has no directory")
        dir.mkdirs()
        val tmp = File(dir, ".${file.name}.cortex-new")
        ElfLinkerPatcher.deleteIfExists(tmp)
        tmp.writeBytes(bytes)
        chmod(tmp, mode)
        if (!ElfLinkerPatcher.atomicReplace(tmp, file)) {
            ElfLinkerPatcher.deleteIfExists(tmp)
            throw IOException("could not replace ${file.path}")
        }
        return true
    }

    /** True when [file] is a regular file (not a symlink to one) that holds exactly [bytes]. */
    fun holds(file: File, bytes: ByteArray): Boolean {
        if (!file.isFile || file.length() != bytes.size.toLong() || ElfLinkerPatcher.isSymlink(file)) return false
        return try {
            file.readBytes().contentEquals(bytes)
        } catch (e: IOException) {
            false
        }
    }

    /** Makes [link] a symlink to [target], replacing whatever is there; true when it had to be changed. */
    @Synchronized
    fun symlink(target: String, link: File): Boolean {
        if (ElfLinkerPatcher.readSymlink(link) == target) return false
        val dir = link.absoluteFile.parentFile ?: throw IOException("${link.path} has no directory")
        val tmp = File(dir, ".${link.name}.cortex-link")
        ElfLinkerPatcher.deleteIfExists(tmp)
        if (!ElfLinkerPatcher.createSymlink(target, tmp)) throw IOException("could not create ${tmp.path}")
        if (!ElfLinkerPatcher.atomicReplace(tmp, link)) {
            ElfLinkerPatcher.deleteIfExists(tmp)
            throw IOException("could not replace ${link.path}")
        }
        return true
    }

    /** chmod that logs instead of throwing. */
    fun chmod(file: File, mode: Int) {
        if (Build.VERSION.SDK_INT > 0) {
            try {
                Os.chmod(file.absolutePath, mode)
            } catch (e: Exception) {
                Log.w(TAG, "chmod ${Integer.toOctalString(mode)} ${file.path} failed: ${e.message}")
            }
        } else {
            // Unit tests on the JVM, where android.system.Os does nothing
            file.setReadable((mode and 256) != 0, (mode and 4) == 0)
            file.setWritable((mode and 128) != 0, true)
            file.setExecutable((mode and 64) != 0, (mode and 1) == 0)
        }
    }
}

/**
 * fcntl locks on several files at once, the kind apt and dpkg take on their lock files, so that the
 * app and a running apt or dpkg (in a session or in root mode) never work on the same files.
 */
internal class FileLocks private constructor(
    private val files: List<RandomAccessFile>,
    private val locks: List<FileLock>
) : AutoCloseable {
    override fun close() {
        locks.forEach { try { it.release() } catch (_: Exception) {} }
        files.forEach { try { it.close() } catch (_: Exception) {} }
    }

    companion object {
        /**
         * Locks every one of [paths] (creating the files like apt does), or returns null when another
         * process holds one of them or one cannot be opened.
         */
        fun tryLock(paths: List<File>): FileLocks? {
            val files = mutableListOf<RandomAccessFile>()
            val locks = mutableListOf<FileLock>()
            try {
                for (path in paths) {
                    path.parentFile?.mkdirs()
                    val raf = RandomAccessFile(path, "rw")
                    files.add(raf)
                    val lock = try { raf.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                    if (lock == null) {
                        FileLocks(files, locks).close()
                        return null
                    }
                    locks.add(lock)
                }
            } catch (e: IOException) {
                FileLocks(files, locks).close()
                return null
            }
            return FileLocks(files, locks)
        }
    }
}
