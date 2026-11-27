package org.cortex.terminal.runtime

import android.content.Context
import android.os.StatFs
import android.util.Log
import org.cortex.terminal.documents.DocumentPaths
import java.io.File

/**
 * Replaces the installed base system with the one this app brings (Rebase to a new Ubuntu release),
 * keeping the user's home directory as it is. The previous system stays beside the root (cortex-old)
 * until the new one has been verified, so an interrupted re-image rolls itself back at the next start.
 *
 * What is deliberately not migrated: apt packages (their lists live in /var, which belongs to the old
 * system; they are exported to a script the user runs in a session) and Python virtual environments
 * (a newer python3 cannot use them; they have to be recreated).
 */
object Reimage {
    private const val TAG = "Reimage"

    /** The Ubuntu release this app's bootstrap archive is built from. */
    const val TARGET_BASE = "26.04"

    /** Where the previous system waits until the new one is verified. */
    const val OLD_ROOT_NAME = "cortex-old"

    /** Where the migration's files live while the two systems are being swapped. */
    private const val MIGRATION_DIR_NAME = "cortex-migration"

    /** Headroom on top of the measured old system for the new one being extracted. */
    private const val REQUIRED_HEADROOM_BYTES = 1024L * 1024L * 1024L // 1 GB

    /**
     * True while a re-image runs: during it, the root is half-swapped by design (the old system waits
     * in cortex-old while the new one is extracted), so the settling at the start must stay out of the
     * way (it would bring the old system back in the middle of the extraction).
     */
    @Volatile
    internal var reimageRunning = false

    /** The base release of the installed system; every system without the marker is 24.04. */
    fun installedBase(context: Context): String {
        val marker = File(Environment.getCortexRoot(context), ".cortex_base")
        return try {
            marker.readText().trim().ifEmpty { "24.04" }
        } catch (e: Exception) {
            "24.04"
        }
    }

    /** Whether the settings item is shown: a healthy 24.04 system and a bootstrap archive to move to. */
    fun reimageAvailable(context: Context): Boolean {
        return BootstrapManager.isBootstrapInstalled(context) &&
            installedBase(context) != TARGET_BASE &&
            BootstrapManager.findBootstrapAsset(context) != null
    }

    /** What a session runs to reinstall the apt packages of the previous system (inside the home). */
    fun reinstallScript(home: File): File = File(File(home, MIGRATION_DIR_NAME), "reinstall.sh")

    /**
     * Runs the whole re-image. Returns null on success, else a message for the user; the previous
     * system is never deleted before the new one has come out whole, so a failure leaves the old
     * system in place (or brings it back at the next start). Call it from a background thread.
     */
    @Synchronized
    fun reimage(context: Context, onProgress: ((Int) -> Unit)? = null): String? {
        reimageRunning = true
        try {
            return reimageInner(context, onProgress)
        } finally {
            reimageRunning = false
        }
    }

    private fun reimageInner(context: Context, onProgress: ((Int) -> Unit)?): String? {
        val filesDir = context.filesDir
        val root = Environment.getCortexRoot(context)
        val oldRoot = File(filesDir, OLD_ROOT_NAME)
        if (!BootstrapManager.isBootstrapInstalled(context)) {
            return "The base system is not installed completely."
        }
        if (oldRoot.exists()) {
            // A previous re-image left its side copy: it cannot be the verified new system (that
            // deletes it at the first start), so the old system it holds would be lost. Refuse
            // instead of guessing which of the two is the one the user wants.
            return "A previous upgrade did not finish. Restart the app once, then try again."
        }
        val oldSize = try { sizeOf(root) } catch (e: Exception) { 0L }
        val available = try { StatFs(filesDir.absolutePath).availableBytes } catch (e: Exception) { -1L }
        if (available in 0 until (oldSize + REQUIRED_HEADROOM_BYTES)) {
            return "Not enough free space: the two systems are next to each other for a moment " +
                "(about ${(oldSize + REQUIRED_HEADROOM_BYTES) / (1024L * 1024L)} MB is needed)."
        }

        val migrationDir = File(filesDir, MIGRATION_DIR_NAME)
        try {
            DocumentPaths.deleteTree(migrationDir)
            migrationDir.mkdirs()
            exportPackages(File(root, "var/lib/dpkg/status"), File(migrationDir, "package-list.txt"))
            FileInstaller.install(
                File(migrationDir, "reinstall.sh"),
                "#!/bin/sh\n" +
                "# Reinstalls the apt packages of the base system before the upgrade to Ubuntu " + TARGET_BASE + ".\n" +
                "apt-get update\n" +
                "xargs apt-get install -y --no-install-recommends < \"\$HOME/.cortex-migration/package-list.txt\"\n",
                493 // 0755
            )
        } catch (e: Exception) {
            Log.e(TAG, "Exporting the package list failed", e)
            return "Reading the list of installed packages failed: ${e.message}"
        }

        // The swap: the old system aside, the new one in. Both are renames in the same directory,
        // so they cannot fail half way and together take milliseconds.
        try {
            android.system.Os.rename(root.absolutePath, oldRoot.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "Moving the old system aside failed", e)
            return "Moving the old system aside failed: ${e.message}"
        }
        val installed = try {
            BootstrapManager.installBootstrapFromAssets(context, onProgress)
        } catch (e: Exception) {
            Log.e(TAG, "Extracting the new base system failed", e)
            false
        }
        if (!installed) {
            Log.e(TAG, "The new base system did not come out whole; the previous one comes back")
            try {
                DocumentPaths.deleteTree(root)
            } catch (e: Exception) {
                Log.w(TAG, "Could not remove the incomplete new system", e)
            }
            try {
                android.system.Os.rename(oldRoot.absolutePath, root.absolutePath)
            } catch (e: Exception) {
                Log.e(TAG, "Bringing the old system back failed; the next start tries again", e)
            }
            return "The new base system did not install completely. Your previous system is in place again."
        }

        // The home directory of the fresh system is empty (the image's home/ubuntu is not used):
        // the user's own one takes its place.
        val newHome = File(root, "home")
        val oldHome = File(oldRoot, "home")
        try {
            DocumentPaths.deleteTree(newHome)
            moveTree(oldHome, newHome)
        } catch (e: Exception) {
            Log.e(TAG, "Moving the home directory over failed", e)
            return "Moving your files into the new system failed: ${e.message}. They are still in $oldRoot/home."
        }

        // The migration's files live in the home from now on (the script the user runs, the package
        // list it reads).
        try {
            val homeMigration = File(newHome, MIGRATION_DIR_NAME)
            DocumentPaths.deleteTree(homeMigration)
            migrationDir.renameTo(homeMigration)
        } catch (e: Exception) {
            Log.w(TAG, "Could not move the migration files into the home", e)
        }

        Log.i(TAG, "Re-imaged the base system to $TARGET_BASE; the previous system waits in ${oldRoot.path}")
        return null
    }

    /** Extracts the "Package:" of every block marked "install ok installed" from dpkg's status file. */
    private fun exportPackages(statusFile: File, into: File) {
        val packages = mutableListOf<String>()
        if (statusFile.isFile) {
            val content = statusFile.readText()
            for (block in content.split(Regex("\\n\\n+"))) {
                var name: String? = null
                var installed = false
                for (line in block.lines()) {
                    when {
                        line.startsWith("Package:") -> name = line.substringAfter(':').trim()
                        line.startsWith("Status:") -> installed = line.trim() == "Status: install ok installed"
                    }
                }
                if (installed && !name.isNullOrEmpty()) packages += name
            }
        }
        if (packages.isEmpty()) {
            into.writeText("")
        } else {
            into.writeText(packages.joinToString("\n") + "\n")
        }
    }

    /** Renames [from] to [to] when it can (same file system), else copies it and takes the original away. */
    private fun moveTree(from: File, to: File) {
        try {
            android.system.Os.rename(from.absolutePath, to.absolutePath)
            return
        } catch (e: Exception) {
            Log.w(TAG, "Renaming ${from.path} to ${to.path} failed, copying instead", e)
        }
        if (!from.copyRecursively(to, overwrite = true)) {
            throw java.io.IOException("${from.path} could not be copied to ${to.path}")
        }
        DocumentPaths.deleteTree(from)
    }

    /** The size of everything under [dir], links not followed. */
    private fun sizeOf(dir: File): Long {
        var total = 0L
        val names = dir.list() ?: return 0L
        for (name in names) {
            val file = File(dir, name)
            if (android.system.OsConstants.S_ISLNK(try {
                    android.system.Os.lstat(file.path).st_mode
                } catch (e: Exception) { 0 })) continue
            if (file.isDirectory) total += sizeOf(file) else total += file.length()
        }
        return total
    }

    /**
     * What the first start does with a side copy of the previous system: when the root is a healthy
     * system, the side copy is not needed any more; when the root is empty (the swap happened but the
     * extraction did not start, say the process was killed), the previous system comes back.
     */
    internal fun settleOldRoot(root: File) {
        if (reimageRunning) return
        val oldRoot = File(root.parentFile, OLD_ROOT_NAME)
        if (!oldRoot.isDirectory) return
        val rootHealthy = File(root, "usr/bin/bash").exists() &&
            BootstrapManager.missingEssentials(root).isEmpty() &&
            BootstrapManager.hasDynamicLinker(root)
        when {
            rootHealthy -> {
                try {
                    DocumentPaths.deleteTree(oldRoot)
                    Log.i(TAG, "Removed the previous system at ${oldRoot.path}")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not remove ${oldRoot.path}", e)
                }
            }
            !File(root, "usr/bin/bash").exists() && File(oldRoot, "usr/bin/bash").exists() -> {
                try {
                    DocumentPaths.deleteTree(root)
                    android.system.Os.rename(oldRoot.absolutePath, root.absolutePath)
                    Log.i(TAG, "An interrupted re-image was rolled back: the previous system is in place again")
                } catch (e: Exception) {
                    Log.e(TAG, "Rolling the interrupted re-image back failed", e)
                }
            }
        }
    }
}
