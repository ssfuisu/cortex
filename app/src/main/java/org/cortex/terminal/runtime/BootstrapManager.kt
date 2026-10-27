package org.cortex.terminal.runtime

import android.content.Context
import android.os.Process
import android.util.Log
import org.cortex.terminal.pty.PtyNative
import org.cortex.terminal.pty.PtyProcess
import java.io.File
import java.io.InputStream
import java.io.PushbackInputStream
import java.util.zip.GZIPInputStream

object BootstrapManager {
    private const val TAG = "BootstrapManager"
    const val CURRENT_BOOTSTRAP_VERSION = 12644

    fun initializeFileSystem(context: Context) {
        val root = Environment.getCortexRoot(context)
        val home = Environment.getHomeDir(context)
        val tmp = Environment.getTmpDir(context)

        listOf(root, home, tmp).forEach { dir ->
            if (!dir.exists()) {
                dir.mkdirs()
            }
            setMode(dir, 448) // 0700
        }
        // Everything else configures the Ubuntu system; before it is extracted there is nothing to
        // configure (the extraction runs this again when it is done).
        if (!File(root, "usr/bin/bash").exists()) return

        ensureRootTools(root, context)
        ensureBrowserOpener(root, context)
        ShellScriptsInstaller.ensureAptAutoUpdate(root, context)

        // ~/.profile and ~/.bashrc are the user's own files (created once from /etc/skel, see
        // ShellProfile). Cortex's environment lives in /etc/profile.d, never in the user's files.
        ShellProfile.ensureUserStartupFiles(home = home, skel = File(root, "etc/skel"))

        val etcProfile = File(root, "etc/profile")
        if (etcProfile.exists()) {
            try {
                val pText = etcProfile.readText()
                if (pText.contains("`id -u`") || pText.contains("$(id -u)")) {
                    etcProfile.writeText(pText.replace("`id -u`", "\${EUID:-0}").replace("$(id -u)", "\${EUID:-0}"))
                }
                etcProfile.setReadable(true, true)
                etcProfile.setWritable(true, true)
                setMode(etcProfile, 384)
            } catch (e: Exception) { Log.w(TAG, "Could not adjust ${etcProfile.path}", e) }
        }

        ensureHookLibrary(context, root)
        updateDnsConfiguration(context, root)
        cleanupAptArtifacts(root)
        ensureAptSandbox(root)
        ensureDpkgTables(root)
        ensureLocale(root)
        ensureHosts(root)
        ensureNsswitch(root)
        ensureCaCertificates(root, context)
        ensureKeyrings(root, context)
        ensurePasswd(root, home)
        ensureReloadScripts(root, home, context)
        updateTimezone(context, root)

        removeOldStorageLink(root, home)

        File(root, "var/cache/apt/archives/partial").mkdirs()
        File(root, "var/lib/apt/lists/partial").mkdirs()
        File(root, "tmp").mkdirs()
        listOf("boot", "media", "mnt", "srv", "opt").forEach {
            val d = File(root, it)
            if (!d.exists()) d.mkdirs()
            setMode(d, 448)
        }

        ensureEssentialBinaries(root, home, context)
    }

    /**
     * Earlier versions made ~/storage -> /sdcard. It is not needed (/sdcard works as it is, the app has
     * access to all files) and a command that deletes through it (rm -rf of what is in ~/storage, find -L ...
     * -delete) empties the phone's shared storage, so it is no longer made. An old one is taken away
     * once: the link only, which is a pointer. One that the user makes after that stays.
     */
    private fun removeOldStorageLink(root: File, home: File) {
        val marker = File(root, "var/lib/cortex/storage-link-removed")
        if (marker.exists()) return
        try {
            val link = File(home, "storage")
            if (ElfLinkerPatcher.isSymlink(link) && android.system.Os.readlink(link.absolutePath) == "/sdcard") {
                android.system.Os.remove(link.absolutePath)
                Log.i(TAG, "Removed the old link ${link.path} -> /sdcard")
            }
            marker.parentFile?.mkdirs()
            FileInstaller.install(marker, "1", 384) // 0600
        } catch (e: Exception) {
            Log.w(TAG, "Could not remove the old storage link", e)
        }
    }

    /** chmod that never throws: a failure is logged and the file keeps its mode. */
    private fun setMode(file: File, mode: Int) {
        try {
            android.system.Os.chmod(file.absolutePath, mode)
        } catch (e: Exception) {
            Log.w(TAG, "chmod ${Integer.toOctalString(mode)} ${file.path} failed: ${e.message}")
        }
    }

    /**
     * Gives the owner access to everything under [dir]: directories rwx, files rw (the system tar
     * keeps the archive's modes, which are meant for root). Symlinks are not followed: home/storage
     * leads to the shared storage.
     */
    private fun ensureOwnerAccess(dir: File) {
        val names = dir.list() ?: return
        for (name in names) {
            val file = File(dir, name)
            val mode = try {
                android.system.Os.lstat(file.path).st_mode
            } catch (e: Exception) {
                continue
            }
            when {
                android.system.OsConstants.S_ISDIR(mode) -> {
                    if ((mode and 448) != 448) setMode(file, (mode and 4095) or 448) // u+rwx
                    ensureOwnerAccess(file)
                }
                android.system.OsConstants.S_ISREG(mode) -> {
                    if ((mode and 384) != 384) setMode(file, (mode and 4095) or 384) // u+rw
                }
            }
        }
    }

    fun hasDynamicLinker(root: File): Boolean {
        return ElfLinkerPatcher.hasDynamicLinker(root)
    }

    fun ensureDynamicLinkerSymlinks(root: File) {
        ElfLinkerPatcher.ensureDynamicLinkerSymlinks(root)
    }

    /** Whether the base system is installed; it may still need [updateInstalledSystem] for this app. */
    fun isBootstrapInstalled(context: Context): Boolean {
        val root = Environment.getCortexRoot(context)
        return File(root, "usr/bin/apt").exists() && File(root, "usr/bin/bash").exists() &&
            hasDynamicLinker(root) && File(root, ".cortex_version").exists()
    }

    /** Whether the installed base system was set up by an older version of the app. */
    fun needsUpdate(context: Context): Boolean {
        val versionFile = File(Environment.getCortexRoot(context), ".cortex_version")
        val ver = try { versionFile.readText().trim().toIntOrNull() ?: 0 } catch (e: Exception) { 0 }
        return ver < CURRENT_BOOTSTRAP_VERSION
    }

    /**
     * Brings a base system set up by an older version of the app up to date (non-destructively:
     * the hook library, apt's configuration and keyrings, Cortex's tools). A few seconds at most;
     * call it from a background thread, before the first session.
     */
    @Synchronized
    fun updateInstalledSystem(context: Context) {
        if (!needsUpdate(context)) return
        val root = Environment.getCortexRoot(context)
        val started = android.os.SystemClock.uptimeMillis()
        try {
            ensureHookLibrary(context, root)
            restoreGpgv(root)
            ensureKeyrings(root, context)
            ensureAptSandbox(root)
            ensureUbuntuSources(root)
            cleanupAptArtifacts(root)
            // Package lists of an older release may be partial or corrupted: apt fetches them
            // again. Not while apt runs.
            FileLocks.tryLock(AptConfigurator.aptLockFiles(root))?.use {
                File(root, "var/lib/apt/lists").listFiles()?.forEach { f ->
                    if (f.isFile && f.name.endsWith("_Packages")) {
                        f.delete()
                    }
                }
                File(root, "var/lib/apt/lists/partial").listFiles()?.forEach { it.delete() }
            }
            ensureMachineId(root)
            // The system users and groups (a package's scripts need them): before the installation is
            // marked as current, so that a session that starts at once can install packages
            ensurePasswd(root, Environment.getHomeDir(context))
            ensureEssentialBinaries(root, Environment.getHomeDir(context), context)
            ensureDynamicLinkerSymlinks(root)
            val versionFile = File(root, ".cortex_version")
            FileInstaller.install(versionFile, CURRENT_BOOTSTRAP_VERSION.toString(), 384) // 0600
            Log.i(TAG, "Base system updated in ${android.os.SystemClock.uptimeMillis() - started} ms")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to perform non-destructive bootstrap update", e)
        }
    }

    fun findBootstrapAsset(context: Context): String? {
        val is64 = Process.is64Bit()
        // .tar.zst since 1.26.38 (the native extractor decompresses it); the others from older builds
        val candidates = if (is64) {
            listOf("bootstrap-arm64.tar.zst", "bootstrap-arm64.tar", "bootstrap-arm64.tar.gz", "bootstrap-arm.tar.zst", "bootstrap-arm.tar", "bootstrap-arm.tar.gz")
        } else {
            listOf("bootstrap-arm.tar.zst", "bootstrap-arm.tar", "bootstrap-arm.tar.gz")
        }
        for (cand in candidates) {
            try {
                context.assets.open(cand).close()
                return cand
            } catch (e: Exception) {
                // Not found, check next candidate
            }
        }
        return null
    }

    /**
     * Extracts and sets up the Ubuntu base system. [onProgress] gets the share of the archive read so
     * far (0-100) while it is extracted, from the extracting thread.
     */
    fun installBootstrapFromAssets(context: Context, onProgress: ((Int) -> Unit)? = null): Boolean {
        val assetName = findBootstrapAsset(context) ?: return false
        return installBootstrap(context, { context.assets.open(assetName) }, onProgress)
    }

    /** [installBootstrapFromAssets] with the archive (tar, tar.gz or tar.zst) from [open], which tests use. */
    internal fun installBootstrap(
        context: Context,
        open: () -> InputStream,
        onProgress: ((Int) -> Unit)? = null
    ): Boolean {
        val root = Environment.getCortexRoot(context)
        val started = android.os.SystemClock.uptimeMillis()

        val tmpTar = File(context.cacheDir, "bootstrap.tar")
        return try {
            // Clean up any non-symlink directories in root from previous runs that conflict with Debian UsrMerge
            listOf("bin", "sbin", "lib", "lib64").forEach { sub ->
                val f = File(root, sub)
                if (f.exists() && f.isDirectory) {
                    try {
                        if (!ElfLinkerPatcher.isSymlink(f)) {
                            f.deleteRecursively()
                        }
                    } catch (e: Exception) {
                        f.deleteRecursively()
                    }
                }
            }

            // Pre-create usr directories and UsrMerge symlinks
            File(root, "usr/bin").mkdirs()
            File(root, "usr/sbin").mkdirs()
            File(root, "usr/lib").mkdirs()
            File(root, "usr/lib64").mkdirs()
            mapOf("bin" to "usr/bin", "sbin" to "usr/sbin", "lib" to "usr/lib", "lib64" to "usr/lib").forEach { (link, target) ->
                val linkFile = File(root, link)
                if (!linkFile.exists()) {
                    try {
                        android.system.Os.symlink(target, linkFile.absolutePath)
                    } catch (e: Exception) { Log.w(TAG, "Could not link /$link to $target", e) }
                }
            }

            // Extract archive using high performance native C extractor. It applies the archive's
            // modes and gives the owner access to every file and directory.
            if (!extractBootstrapStreaming(open, root, onProgress)) {
                Log.w(TAG, "Native extraction failed, using the system tar")
                // Fallback to system tar, which needs the archive as a file
                openBootstrap(open).use { stream ->
                    tmpTar.outputStream().use { out -> stream.copyTo(out) }
                }
                // From the file, the native extractor can still do it (the system tar cannot read zstd)
                val fromFile = try {
                    PtyNative.extractTar(tmpTar.absolutePath, root.absolutePath) == 0
                } catch (t: Throwable) {
                    false
                }
                if (!fromFile) {
                    val tarCmd = when {
                        File("/system/bin/tar").exists() -> listOf("/system/bin/tar", "-xf", tmpTar.absolutePath, "-C", root.absolutePath)
                        File("/system/bin/toybox").exists() -> listOf("/system/bin/toybox", "tar", "-xf", tmpTar.absolutePath, "-C", root.absolutePath)
                        else -> listOf("tar", "-xf", tmpTar.absolutePath, "-C", root.absolutePath)
                    }
                    val process = ProcessBuilder(tarCmd).redirectErrorStream(true).start()
                    process.inputStream.readBytes()
                    process.waitFor()
                    ensureOwnerAccess(root)
                }
            }

            ensureDynamicLinkerSymlinks(root)
            patchAllDynamicLinkers(root)
            fixAbsoluteSymlinks(root)

            // /root, and /home, which is the user's home here (the image's home/ubuntu is not used)
            File(root, "root").mkdirs()
            File(root, "home").mkdirs()
            // (a link in it is removed as a link, never followed)
            org.cortex.terminal.documents.DocumentPaths.deleteTree(File(root, "home/ubuntu"))

            ensureUbuntuSources(root)

            // Ensure dpkg status file exists
            val statusFile = File(root, "var/lib/dpkg/status")
            statusFile.parentFile?.mkdirs()
            if (!statusFile.exists()) {
                statusFile.createNewFile()
            }

            // Hook library, DNS, users, apt, certificates, keyrings, the user's dotfiles, Cortex's
            // tools...: the same steps every start checks.
            initializeFileSystem(context)

            File(root, "var/lib/apt/lists/partial").mkdirs()
            File(root, "var/cache/apt/archives/partial").mkdirs()

            // Mark bootstrap version only if dynamic linker is present and functional
            if (hasDynamicLinker(root)) {
                val versionFile = File(root, ".cortex_version")
                versionFile.writeText(CURRENT_BOOTSTRAP_VERSION.toString())
                versionFile.setReadable(true, true)
                versionFile.setWritable(true, true)
                setMode(versionFile, 384)
                Log.i(TAG, "Base system installed in ${android.os.SystemClock.uptimeMillis() - started} ms")
                true
            } else {
                Log.e(TAG, "Bootstrap extraction finished but dynamic linker not found!")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting bootstrap from APK", e)
            false
        } finally {
            if (tmpTar.exists()) {
                tmpTar.delete()
            }
        }
    }

    /**
     * The bootstrap archive as a tar stream: unzipped when it is gzip-compressed, as it is when it is a
     * tar or zstd-compressed (which the native extractor decompresses). [onProgress]
     * gets the share of the asset read so far (0-100), when its size is known.
     */
    private fun openBootstrap(open: () -> InputStream, onProgress: ((Int) -> Unit)? = null): InputStream {
        val raw = open()
        val total = try { raw.available().toLong() } catch (e: java.io.IOException) { 0L }
        val counted = if (onProgress != null && total > 0) ProgressInputStream(raw, total, onProgress) else raw
        val pushback = PushbackInputStream(counted, 2)
        val header = ByteArray(2)
        val bytesRead = pushback.read(header)
        if (bytesRead > 0) {
            pushback.unread(header, 0, bytesRead)
        }
        val isGzip = (bytesRead >= 2 && (header[0].toInt() and 0xFF) == 0x1F && (header[1].toInt() and 0xFF) == 0x8B)
        return if (isGzip) GZIPInputStream(pushback, 64 * 1024) else pushback
    }

    /**
     * Extracts the bootstrap archive straight from the APK: a thread unpacks it into a pipe that the
     * native extractor reads, so the few hundred megabytes are never copied into the cache first (that
     * took time and needed that much free space on top). True when the whole archive was extracted.
     */
    private fun extractBootstrapStreaming(
        open: () -> InputStream,
        root: File,
        onProgress: ((Int) -> Unit)?
    ): Boolean {
        val pipe = try {
            android.os.ParcelFileDescriptor.createPipe()
        } catch (e: Exception) {
            Log.w(TAG, "No pipe for the bootstrap archive", e)
            return false
        }
        var readError: Throwable? = null
        val writer = Thread({
            try {
                android.os.ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { out ->
                    try {
                        openBootstrap(open, onProgress).use { input ->
                            val buffer = ByteArray(256 * 1024)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                try {
                                    out.write(buffer, 0, n)
                                } catch (e: java.io.IOException) {
                                    // The extractor stopped reading: done (it stops at the archive's
                                    // end marker) or failed, which its result tells.
                                    break
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        readError = t
                    }
                }
            } catch (_: Throwable) {
            }
        }, "Cortex-BootstrapStream")
        writer.start()
        val started = android.os.SystemClock.uptimeMillis()
        val result = try {
            PtyNative.extractTarFd(pipe[0].fd, root.absolutePath)
        } catch (t: Throwable) {
            Log.e(TAG, "Native extraction failed", t)
            -1
        } finally {
            try { pipe[0].close() } catch (_: Exception) {}
            writer.join()
        }
        readError?.let { Log.e(TAG, "Reading the bootstrap archive failed", it) }
        Log.i(TAG, "Extracted the base system in ${android.os.SystemClock.uptimeMillis() - started} ms (status $result)")
        return result == 0 && readError == null
    }

    /**
     * Installs the C development packages (libc6-dev and what it needs) that the pinned glibc keeps apt
     * from installing, see [DevPackages]. Unpacks about 17 MB, so call it from a background thread.
     */
    fun ensureDevPackages(context: Context, root: File) {
        val outcome = DevPackages.ensureInstalled(
            root = root,
            tmpDir = context.cacheDir,
            openOverlay = {
                DevPackages.assetNames(Process.is64Bit()).firstNotNullOfOrNull { name ->
                    try {
                        context.assets.open(name)
                    } catch (e: java.io.IOException) {
                        null
                    }
                }
            },
            extract = { tar, dest -> extractTarInto(tar, dest) },
            log = { Log.i(TAG, it) }
        )
        if (outcome.result == DevPackages.Result.FAILED) {
            Log.e(TAG, "C development packages were not installed: $outcome")
        }
    }

    private fun extractTarInto(tar: File, dest: File): Boolean {
        if (PtyNative.extractTar(tar.absolutePath, dest.absolutePath) == 0) return true
        val cmd = when {
            File("/system/bin/tar").exists() -> listOf("/system/bin/tar", "-xf", tar.absolutePath, "-C", dest.absolutePath)
            File("/system/bin/toybox").exists() -> listOf("/system/bin/toybox", "tar", "-xf", tar.absolutePath, "-C", dest.absolutePath)
            else -> return false
        }
        return try {
            val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
            process.inputStream.readBytes()
            process.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    fun getInitialShellCommand(context: Context): String {
        val root = Environment.getCortexRoot(context)
        val debianBash = File(root, "usr/bin/bash")
        if (debianBash.exists() && hasDynamicLinker(root)) {
            debianBash.setExecutable(true, true)
            return debianBash.absolutePath
        }
        val customBash = File(root, "bin/bash")
        if (customBash.exists() && hasDynamicLinker(root)) {
            customBash.setExecutable(true, true)
            return customBash.absolutePath
        }
        val customSh = File(root, "bin/sh")
        if (customSh.exists() && hasDynamicLinker(root)) {
            customSh.setExecutable(true, true)
            return customSh.absolutePath
        }
        return if (File("/system/bin/sh").exists()) "/system/bin/sh" else "/bin/sh"
    }

    fun patchAllDynamicLinkers(root: File) {
        ElfLinkerPatcher.patchAllDynamicLinkers(root)
    }

    fun fixAbsoluteSymlinks(root: File) {
        ElfLinkerPatcher.fixAbsoluteSymlinks(root)
    }

    fun patchDynamicLinker(file: File) {
        ElfLinkerPatcher.patchDynamicLinker(file)
    }

    private fun ensureDpkgTables(root: File) {
        try {
            val dpkgShare = File(root, "usr/share/dpkg")
            dpkgShare.mkdirs()
            File(root, "etc/alternatives").mkdirs()
            File(root, "var/lib/dpkg/alternatives").mkdirs()

            val cpuTable = File(dpkgShare, "cputable")
            if (!cpuTable.exists() || cpuTable.length() == 0L) {
                cpuTable.writeText(
                    "# Version=1.0\n" +
                    "alpha\talpha\talpha.*\t64\tlittle\n" +
                    "amd64\tx86_64\t(amd64|x86_64)\t64\tlittle\n" +
                    "arc\tarc\tarc\t32\tlittle\n" +
                    "armeb\tarmeb\tarm.*b\t32\tbig\n" +
                    "arm\tarm\tarm.*\t32\tlittle\n" +
                    "arm64\taarch64\taarch64\t64\tlittle\n" +
                    "hppa\thppa\thppa.*\t32\tbig\n" +
                    "loong64\tloongarch64\tloongarch64\t64\tlittle\n" +
                    "i386\ti686\t(i[34567]86|pentium)\t32\tlittle\n" +
                    "ia64\tia64\tia64\t64\tlittle\n" +
                    "m68k\tm68k\tm68k\t32\tbig\n" +
                    "mips\tmips\tmips(eb)?\t32\tbig\n" +
                    "mipsel\tmipsel\tmipsel\t32\tlittle\n" +
                    "mipsr6\tmipsisa32r6\tmipsisa32r6\t32\tbig\n" +
                    "mipsr6el\tmipsisa32r6el\tmipsisa32r6el\t32\tlittle\n" +
                    "mips64\tmips64\tmips64\t64\tbig\n" +
                    "mips64el\tmips64el\tmips64el\t64\tlittle\n" +
                    "mips64r6\tmipsisa64r6\tmipsisa64r6\t64\tbig\n" +
                    "mips64r6el\tmipsisa64r6el\tmipsisa64r6el\t64\tlittle\n" +
                    "nios2\tnios2\tnios2\t32\tlittle\n" +
                    "or1k\tor1k\tor1k\t32\tbig\n" +
                    "powerpc\tpowerpc\t(powerpc|ppc)\t32\tbig\n" +
                    "powerpcel\tpowerpcle\tpowerpcle\t32\tlittle\n" +
                    "ppc64\tpowerpc64\t(powerpc|ppc)64\t64\tbig\n" +
                    "ppc64el\tpowerpc64le\tpowerpc64le\t64\tlittle\n" +
                    "riscv64\triscv64\triscv64\t64\tlittle\n" +
                    "s390\ts390\ts390\t32\tbig\n" +
                    "s390x\ts390x\ts390x\t64\tbig\n" +
                    "sh3\tsh3\tsh3\t32\tlittle\n" +
                    "sh3eb\tsh3eb\tsh3eb\t32\tbig\n" +
                    "sh4\tsh4\tsh4\t32\tlittle\n" +
                    "sh4eb\tsh4eb\tsh4eb\t32\tbig\n" +
                    "sparc\tsparc\tsparc\t32\tbig\n" +
                    "sparc64\tsparc64\tsparc64\t64\tbig\n"
                )
                cpuTable.setReadable(true, true)
                cpuTable.setWritable(true, true)
            }

            val tupleTable = File(dpkgShare, "tupletable")
            val tupleContent =
                "# Version=1.0\n" +
                "eabi-uclibc-linux-arm\tuclibc-linux-armel\n" +
                "base-uclibc-linux-<cpu>\tuclibc-linux-<cpu>\n" +
                "eabihf-musl-linux-arm\tmusl-linux-armhf\n" +
                "base-musl-linux-<cpu>\tmusl-linux-<cpu>\n" +
                "eabihf-gnu-linux-arm\tarmhf\n" +
                "eabi-gnu-linux-arm\tarmel\n" +
                "abin32-gnu-linux-mips64r6el\tmipsn32r6el\n" +
                "abin32-gnu-linux-mips64r6\tmipsn32r6\n" +
                "abin32-gnu-linux-mips64el\tmipsn32el\n" +
                "abin32-gnu-linux-mips64\tmipsn32\n" +
                "abi64-gnu-linux-mips64r6el\tmips64r6el\n" +
                "abi64-gnu-linux-mips64r6\tmips64r6\n" +
                "abi64-gnu-linux-mips64el\tmips64el\n" +
                "abi64-gnu-linux-mips64\tmips64\n" +
                "spe-gnu-linux-powerpc\tpowerpcspe\n" +
                "x32-gnu-linux-amd64\tx32\n" +
                "base-gnu-linux-<cpu>\t<cpu>\n" +
                "base-gnu-kfreebsd-amd64\tkfreebsd-amd64\n" +
                "base-gnu-kfreebsd-i386\tkfreebsd-i386\n" +
                "base-gnu-kopensolaris-amd64\tkopensolaris-amd64\n" +
                "base-gnu-kopensolaris-i386\tkopensolaris-i386\n" +
                "base-gnu-hurd-amd64\thurd-amd64\n" +
                "base-gnu-hurd-i386\thurd-i386\n" +
                "base-bsd-dragonflybsd-amd64\tdragonflybsd-amd64\n" +
                "base-bsd-freebsd-amd64\tfreebsd-amd64\n" +
                "base-bsd-freebsd-arm\tfreebsd-arm\n" +
                "base-bsd-freebsd-arm64\tfreebsd-arm64\n" +
                "base-bsd-freebsd-i386\tfreebsd-i386\n" +
                "base-bsd-freebsd-powerpc\tfreebsd-powerpc\n" +
                "base-bsd-freebsd-ppc64\tfreebsd-ppc64\n" +
                "base-bsd-freebsd-riscv\tfreebsd-riscv\n" +
                "base-bsd-openbsd-<cpu>\topenbsd-<cpu>\n" +
                "base-bsd-netbsd-<cpu>\tnetbsd-<cpu>\n" +
                "base-bsd-darwin-amd64\tdarwin-amd64\n" +
                "base-bsd-darwin-arm\tdarwin-arm\n" +
                "base-bsd-darwin-arm64\tdarwin-arm64\n" +
                "base-bsd-darwin-i386\tdarwin-i386\n" +
                "base-bsd-darwin-powerpc\tdarwin-powerpc\n" +
                "base-bsd-darwin-ppc64\tdarwin-ppc64\n" +
                "base-sysv-aix-powerpc\taix-powerpc\n" +
                "base-sysv-aix-ppc64\taix-ppc64\n" +
                "base-sysv-solaris-amd64\tsolaris-amd64\n" +
                "base-sysv-solaris-i386\tsolaris-i386\n" +
                "base-sysv-solaris-sparc\tsolaris-sparc\n" +
                "base-sysv-solaris-sparc64\tsolaris-sparc64\n" +
                "base-tos-mint-m68k\tmint-m68k\n"

            if (!tupleTable.exists() || tupleTable.length() == 0L) {
                tupleTable.writeText(tupleContent)
                tupleTable.setReadable(true, true)
                tupleTable.setWritable(true, true)
            }
            val tripletTable = File(dpkgShare, "triplettable")
            if (!tripletTable.exists() || tripletTable.length() == 0L) {
                tripletTable.writeText(tupleContent)
                tripletTable.setReadable(true, true)
                tripletTable.setWritable(true, true)
            }

            val ostable = File(dpkgShare, "ostable")
            if (!ostable.exists() || ostable.length() == 0L) {
                ostable.writeText(
                    "# Version=2.0\n" +
                    "eabi-uclibc-linux\tlinux-uclibceabi\tlinux[^-]*-uclibceabi\n" +
                    "base-uclibc-linux\tlinux-uclibc\tlinux[^-]*-uclibc\n" +
                    "eabihf-musl-linux\tlinux-musleabihf\tlinux[^-]*-musleabihf\n" +
                    "base-musl-linux\tlinux-musl\tlinux[^-]*-musl\n" +
                    "eabihf-gnu-linux\tlinux-gnueabihf\tlinux[^-]*-gnueabihf\n" +
                    "eabi-gnu-linux\tlinux-gnueabi\tlinux[^-]*-gnueabi\n" +
                    "abin32-gnu-linux\tlinux-gnuabin32\tlinux[^-]*-gnuabin32\n" +
                    "abi64-gnu-linux\tlinux-gnuabi64\tlinux[^-]*-gnuabi64\n" +
                    "spe-gnu-linux\tlinux-gnuspe\tlinux[^-]*-gnuspe\n" +
                    "x32-gnu-linux\tlinux-gnux32\tlinux[^-]*-gnux32\n" +
                    "base-gnu-linux\tlinux-gnu\tlinux[^-]*(-gnu.*)?\n" +
                    "eabihf-gnu-kfreebsd\tkfreebsd-gnueabihf\tkfreebsd[^-]*-gnueabihf\n" +
                    "base-gnu-kfreebsd\tkfreebsd-gnu\tkfreebsd[^-]*(-gnu.*)?\n" +
                    "base-gnu-kopensolaris\tkopensolaris-gnu\tkopensolaris[^-]*(-gnu.*)?\n" +
                    "base-gnu-hurd\tgnu\tgnu[^-]*\n" +
                    "base-bsd-darwin\tdarwin\tdarwin[^-]*\n" +
                    "base-bsd-dragonflybsd\tdragonflybsd\tdragonfly[^-]*\n" +
                    "base-bsd-freebsd\tfreebsd\tfreebsd[^-]*\n" +
                    "base-bsd-netbsd\tnetbsd\tnetbsd[^-]*\n" +
                    "base-bsd-openbsd\topenbsd\topenbsd[^-]*\n" +
                    "base-sysv-aix\taix\taix[^-]*\n" +
                    "base-sysv-solaris\tsolaris\tsolaris[^-]*\n" +
                    "base-tos-mint\tmint\tmint[^-]*\n"
                )
                ostable.setReadable(true, true)
                ostable.setWritable(true, true)
            }

            val abitable = File(dpkgShare, "abitable")
            if (!abitable.exists() || abitable.length() == 0L) {
                abitable.writeText(
                    "# Version=2.0\n" +
                    "abin32\t32\n" +
                    "x32\t32\n"
                )
                abitable.setReadable(true, true)
                abitable.setWritable(true, true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ensure dpkg tables", e)
        }
    }

    private fun ensureLocale(root: File) {
        try {
            val localeDir = File(root, "usr/lib/locale")
            localeDir.mkdirs()
            val cUtf8 = File(localeDir, "C.utf8")
            val cUTF8 = File(localeDir, "C.UTF-8")
            val enUtf8 = File(localeDir, "en_US.UTF-8")

            if (cUtf8.exists() && !cUTF8.exists()) {
                try {
                    android.system.Os.symlink("C.utf8", cUTF8.absolutePath)
                } catch (e: Exception) { Log.w(TAG, "Could not link locale C.UTF-8", e) }
            } else if (!cUtf8.exists() && cUTF8.exists()) {
                try {
                    android.system.Os.symlink("C.UTF-8", cUtf8.absolutePath)
                } catch (e: Exception) { Log.w(TAG, "Could not link locale C.utf8", e) }
            }

            val baseLocale = if (cUtf8.exists()) "C.utf8" else if (cUTF8.exists()) "C.UTF-8" else null
            if (baseLocale != null && !enUtf8.exists()) {
                try {
                    android.system.Os.symlink(baseLocale, enUtf8.absolutePath)
                } catch (e: Exception) { Log.w(TAG, "Could not link locale en_US.UTF-8", e) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ensure locale", e)
        }
    }

    fun ensureAptSandbox(root: File) {
        AptConfigurator.ensureAptSandbox(root)
    }

    fun ensureUbuntuSources(root: File) {
        AptConfigurator.ensureUbuntuSources(root)
    }

    fun ensureHookLibrary(context: Context, root: File) {
        try {
            val is64 = Process.is64Bit()
            val hookAssetName = if (is64) "libcortex-hook-arm64.so" else "libcortex-hook-arm.so"
            val targetHook = File(root, "usr/lib/libcortex-hook.so")
            val bytes = context.assets.open(hookAssetName).use { it.readBytes() }
            // Replaced (in one step: running programs keep the copy they loaded) only when the app
            // brings a different one, or the file is missing or damaged. A symlink in its place, left
            // by older releases, is replaced too.
            if (bytes.isNotEmpty()) {
                FileInstaller.install(targetHook, bytes, 493) // 0755
            }

            val libDir = File(root, "lib")
            if (libDir.exists() && !ElfLinkerPatcher.isSymlink(libDir)) {
                try {
                    FileInstaller.symlink(targetHook.absolutePath, File(libDir, "libcortex-hook.so"))
                } catch (e: Exception) { Log.w(TAG, "Could not link ${libDir.path}/libcortex-hook.so", e) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update hook library from assets", e)
        }
    }

    fun cleanupStaleSocketsAndLocks(root: File, home: File) {
        try {
            val tmpDir = File(root, "tmp")
            if (tmpDir.exists() && tmpDir.isDirectory) {
                tmpDir.listFiles()?.forEach { file ->
                    val name = file.name
                    if (name.endsWith(".sock") || name.endsWith(".socket") ||
                        name.endsWith(".lock") || name.endsWith(".pid") ||
                        name.startsWith(".ctx_sock_") || name.startsWith(".cortex-rcwatch.") || name.startsWith("opencode") ||
                        name.startsWith("bun-") || name.startsWith("node-")) {
                        try {
                            file.deleteRecursively()
                        } catch (e: Exception) { Log.w(TAG, "Could not remove stale ${file.path}", e) }
                    }
                }
            } else {
                tmpDir.mkdirs()
            }
            val etcDir = File(root, "etc")
            if (etcDir.exists() && etcDir.isDirectory) {
                etcDir.listFiles()?.forEach { file ->
                    val name = file.name
                    if (name.endsWith(".lock") || name.endsWith("+") || name.endsWith(".tmp") || name.contains(".pwd.lock")) {
                        try {
                            file.deleteRecursively()
                        } catch (e: Exception) { Log.w(TAG, "Could not remove stale ${file.path}", e) }
                    }
                }
            }

            val opencodeDataDir = File(home, ".local/share/opencode")
            if (opencodeDataDir.exists() && opencodeDataDir.isDirectory) {
                opencodeDataDir.listFiles()?.forEach { file ->
                    val name = file.name
                    if (name.endsWith(".sock") || name.endsWith(".socket") ||
                        name.endsWith(".lock") || name.endsWith(".pid")) {
                        try {
                            file.deleteRecursively()
                        } catch (e: Exception) { Log.w(TAG, "Could not remove stale ${file.path}", e) }
                    }
                }
            }

            val opencodeCacheDir = File(home, ".cache/opencode")
            if (opencodeCacheDir.exists() && opencodeCacheDir.isDirectory) {
                opencodeCacheDir.listFiles()?.forEach { file ->
                    val name = file.name
                    if (name.endsWith(".sock") || name.endsWith(".lock") || name.endsWith(".pid")) {
                        try {
                            file.deleteRecursively()
                        } catch (e: Exception) { Log.w(TAG, "Could not remove stale ${file.path}", e) }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cleanup stale sockets and locks", e)
        }
    }

    fun ensureHosts(root: File) {
        try {
            val etcDir = File(root, "etc")
            etcDir.mkdirs()
            val hostsFile = File(etcDir, "hosts")
            val defaultHosts = "127.0.0.1 localhost localhost.localdomain\n" +
                "::1 localhost ip6-localhost ip6-loopback\n"

            if (!hostsFile.exists()) {
                hostsFile.writeText(defaultHosts)
                hostsFile.setReadable(true, true)
                hostsFile.setWritable(true, true)
                setMode(hostsFile, 384)
            } else {
                val currentText = hostsFile.readText()
                if (currentText.contains("ports.ubuntu.com") || currentText.contains("api.meta.ai")) {
                    hostsFile.writeText(defaultHosts)
                    hostsFile.setReadable(true, true)
                    hostsFile.setWritable(true, true)
                    setMode(hostsFile, 384)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ensure hosts", e)
        }
    }

    fun ensureNsswitch(root: File) {
        try {
            val etcDir = File(root, "etc")
            etcDir.mkdirs()
            val nssFile = File(etcDir, "nsswitch.conf")
            if (!nssFile.exists() || !nssFile.readText().contains("hosts:")) {
                nssFile.writeText(
                    "passwd:         files\n" +
                    "group:          files\n" +
                    "shadow:         files\n" +
                    "gshadow:        files\n\n" +
                    "hosts:          files dns\n" +
                    "networks:       files\n\n" +
                    "protocols:      db files\n" +
                    "services:       db files\n" +
                    "ethers:         db files\n" +
                    "rpc:            db files\n"
                )
                nssFile.setReadable(true, true)
                nssFile.setWritable(true, true)
                setMode(nssFile, 384)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ensure nsswitch.conf", e)
        }
    }

    fun updateDnsConfiguration(context: Context, root: File) {
        try {
            val ipv4Servers = mutableListOf<String>()
            val ipv6Servers = mutableListOf<String>()
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            val activeNet = cm?.activeNetwork
            if (activeNet != null) {
                val lp = cm.getLinkProperties(activeNet)
                lp?.dnsServers?.forEach { addr ->
                    val host = addr.hostAddress
                    if (host.isNullOrBlank() || addr.isLoopbackAddress || addr.isAnyLocalAddress) return@forEach
                    if (addr is java.net.Inet4Address) {
                        if (!host.contains("%")) ipv4Servers.add(host)
                    } else if (addr is java.net.Inet6Address) {
                        // A link-local server is only reachable with its interface ("fe80::1%wlan0").
                        if (!addr.isLinkLocalAddress || host.contains("%")) ipv6Servers.add(host)
                    }
                }
            }
            // IPv6 servers only on networks that have no IPv4 ones (IPv6-only mobile networks): on a
            // dual-stack network a broken IPv6 path would slow every lookup down, since resolv.conf
            // rotates through the servers.
            val dnsServers = (if (ipv4Servers.isNotEmpty()) ipv4Servers else ipv6Servers).toMutableList()
            if (!dnsServers.contains("8.8.8.8")) dnsServers.add("8.8.8.8")
            if (!dnsServers.contains("1.1.1.1")) dnsServers.add("1.1.1.1")

            val selectedDns = dnsServers.distinct().take(3)

            // Replaced in one step (a lookup running meanwhile reads the old or the new file), and
            // only when the servers changed: this runs whenever Cortex comes to the foreground.
            val resolvConf = selectedDns.joinToString("\n") { "nameserver $it" } + "\noptions timeout:1 attempts:2 rotate\n"
            FileInstaller.install(File(root, "etc/resolv.conf"), resolvConf, 384) // 0600

            ensureHosts(root)
            ensureNsswitch(root)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update resolv.conf", e)
        }
    }

    fun cleanupAptArtifacts(root: File) {
        AptConfigurator.cleanupAptArtifacts(root)
    }

    fun ensureCaCertificates(root: File, context: Context? = null) {
        try {
            val certsDir = File(root, "etc/ssl/certs")
            certsDir.mkdirs()
            val caBundle = File(certsDir, "ca-certificates.crt")

            if ((!caBundle.exists() || caBundle.length() < 100000L) && context != null) {
                try {
                    val bundled = context.assets.open("cacert.pem").use { it.readBytes() }
                    FileInstaller.install(caBundle, bundled, 384) // 0600
                    Log.i(TAG, "Copied bundled cacert.pem from assets (${caBundle.length()} bytes)")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to extract bundled cacert.pem", e)
                }
            }

            val certDirs = listOf(
                File("/system/etc/security/cacerts"),
                File("/apex/com.android.conscrypt/cacerts")
            )
            // Merging reads every one of Android's certificates and the whole bundle, so it is done
            // again only when one of them changed: a system update, or update-ca-certificates
            // rewriting the bundle.
            val stampFile = File(certsDir, ".cortex-android-cas")
            fun stamp() = buildString {
                append(caBundle.length()).append(':').append(caBundle.lastModified())
                for (dir in certDirs) {
                    append('\n').append(dir.path).append(':').append(dir.lastModified())
                        .append(':').append(dir.list()?.size ?: -1)
                }
                append('\n')
            }
            val lastStamp = try { if (stampFile.isFile) stampFile.readText() else null } catch (e: Exception) { null }
            if (caBundle.length() < 1000L || lastStamp != stamp()) {
                mergeAndroidCertificates(certsDir, caBundle, certDirs)
                try {
                    FileInstaller.install(stampFile, stamp(), 384)
                } catch (e: Exception) { Log.w(TAG, "Could not write ${stampFile.path}", e) }
            }
            setMode(caBundle, 384)

            val certAliases = listOf(
                File(root, "etc/ssl/cert.pem"),
                File(root, "etc/ssl/ca-bundle.pem"),
                File(root, "usr/lib/ssl/cert.pem")
            )
            val usrLibSsl = File(root, "usr/lib/ssl")
            if (!usrLibSsl.exists()) usrLibSsl.mkdirs()

            val pkiDir = File(root, "etc/pki/tls/certs")
            if (!pkiDir.exists()) pkiDir.mkdirs()
            val pkiBundle = File(pkiDir, "ca-bundle.crt")

            certAliases.plus(pkiBundle).forEach { aliasFile ->
                try {
                    if (!aliasFile.exists() || aliasFile.length() == 0L) {
                        try {
                            android.system.Os.symlink(caBundle.absolutePath, aliasFile.absolutePath)
                        } catch (symEx: Exception) {
                            FileInstaller.install(aliasFile, caBundle.readBytes(), 384)
                        }
                    }
                } catch (e: Exception) { Log.w(TAG, "Could not create ${aliasFile.path}", e) }
            }
            Log.i(TAG, "ensureCaCertificates finished (${caBundle.length()} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ensure CA certificates", e)
        }
    }

    /**
     * Adds Android's trusted certificates that [caBundle] lacks (a phone's own or its carrier's) to it,
     * and copies them by their hash names into [certsDir] for OpenSSL's directory lookup.
     */
    private fun mergeAndroidCertificates(certsDir: File, caBundle: File, certDirs: List<File>) {
        val existingText = if (caBundle.exists() && caBundle.length() > 0) {
            try { caBundle.readText() } catch (e: Exception) { "" }
        } else ""

        val sb = StringBuilder(existingText)
        var appended = false

        for (androidCertsDir in certDirs) {
            if (androidCertsDir.exists() && androidCertsDir.isDirectory) {
                androidCertsDir.listFiles()?.forEach { f ->
                    if (f.isFile && f.name.endsWith(".0")) {
                        try {
                            val targetFile = File(certsDir, f.name)
                            if (!targetFile.exists() || targetFile.length() == 0L) {
                                FileInstaller.install(targetFile, f.readBytes(), 384)
                            }
                        } catch (e: Exception) { Log.w(TAG, "Could not copy CA certificate ${f.name}", e) }
                        try {
                            val content = f.readText()
                            val start = content.indexOf("-----BEGIN CERTIFICATE-----")
                            val end = content.indexOf("-----END CERTIFICATE-----")
                            if (start != -1 && end != -1) {
                                val certPem = content.substring(start, end + "-----END CERTIFICATE-----".length)
                                val bodyOnly = certPem
                                    .replace("-----BEGIN CERTIFICATE-----", "")
                                    .replace("-----END CERTIFICATE-----", "")
                                    .replace("\n", "")
                                    .replace("\r", "")
                                    .trim()
                                if (bodyOnly.length > 32 && !existingText.contains(bodyOnly.substring(0, 32))) {
                                    if (sb.isNotEmpty() && !sb.endsWith("\n")) {
                                        sb.append("\n")
                                    }
                                    sb.append(certPem).append("\n")
                                    appended = true
                                }
                            }
                        } catch (e: Exception) { Log.w(TAG, "Could not read CA certificate ${f.name}", e) }
                    }
                }
            }
        }

        if ((!caBundle.exists() || caBundle.length() < 1000L || appended) && sb.isNotEmpty()) {
            FileInstaller.install(caBundle, sb.toString(), 384)
        }
    }

    fun ensureKeyrings(root: File, context: Context? = null) {
        AptConfigurator.ensureKeyrings(root, context)
    }

    private fun ensurePasswd(root: File, home: File) {
        try {
            val etcDir = File(root, "etc")
            if (!etcDir.exists()) etcDir.mkdirs()
            val passwdFile = File(etcDir, "passwd")
            var passwdText = if (passwdFile.exists()) passwdFile.readText() else ""
            val homePath = home.absolutePath
            if (!passwdText.contains("root:x:0:0")) {
                passwdText = "root:x:0:0:root:$homePath:/bin/bash\n" + passwdText
            } else {
                passwdText = passwdText.replace(Regex("root:x:0:0:root:[^:]+:/bin/bash"), "root:x:0:0:root:$homePath:/bin/bash")
            }
            if (!passwdText.contains("cortex:")) {
                passwdText += "cortex:x:0:0:Cortex:$homePath:/bin/bash\n"
            } else {
                passwdText = passwdText.replace(Regex("cortex:x:0:0:Cortex:[^:]+:/bin/bash"), "cortex:x:0:0:Cortex:$homePath:/bin/bash")
            }
            if (!passwdText.contains("_ssh:")) {
                passwdText += "_ssh:x:100:100:OpenSSH daemon:/run/sshd:/usr/sbin/nologin\n"
            }
            if (!passwdText.contains("sshd:")) {
                passwdText += "sshd:x:101:101:Privilege-separated SSH:/run/sshd:/usr/sbin/nologin\n"
            }
            // The system users and groups every Ubuntu has (man, www-data, adm, tty...): packages'
            // scripts use them, and an image that kept only root and cortex made redis, nginx and
            // PostgreSQL fail to install
            val baseDir = File(root, "usr/share/base-passwd")
            fun master(name: String) = try { File(baseDir, name).takeIf { it.isFile }?.readText() ?: "" } catch (e: Exception) { "" }
            val basePasswd = BaseUsers.mergePasswd(passwdText, master("passwd.master"))
            passwdText = basePasswd.text
            FileInstaller.install(passwdFile, passwdText, 420) // 0644

            val groupFile = File(etcDir, "group")
            var groupText = if (groupFile.exists()) groupFile.readText() else ""
            if (!groupText.contains("root:x:0:")) {
                groupText = "root:x:0:\n" + groupText
            }
            if (!groupText.contains("cortex:")) {
                groupText += "cortex:x:0:\n"
            }
            if (!groupText.contains("_ssh:")) {
                groupText += "_ssh:x:100:\n"
            }
            if (!groupText.contains("ssh:")) {
                groupText += "ssh:x:101:\n"
            }
            val baseGroup = BaseUsers.mergeGroup(groupText, master("group.master"))
            groupText = baseGroup.text
            FileInstaller.install(groupFile, groupText, 420)

            val shadowFile = File(etcDir, "shadow")
            var shadowText = if (shadowFile.exists()) shadowFile.readText() else ""
            if (!shadowText.contains("root:")) shadowText = "root:*:19700:0:99999:7:::\n" + shadowText
            if (!shadowText.contains("cortex:")) shadowText += "cortex:*:19700:0:99999:7:::\n"
            if (!shadowText.contains("_ssh:")) shadowText += "_ssh:*:19700:0:99999:7:::\n"
            if (!shadowText.contains("sshd:")) shadowText += "sshd:*:19700:0:99999:7:::\n"
            shadowText = BaseUsers.mergeShadow(shadowText, basePasswd.added, BaseUsers::shadowEntry)
            FileInstaller.install(shadowFile, shadowText, 420)

            val gshadowFile = File(etcDir, "gshadow")
            var gshadowText = if (gshadowFile.exists()) gshadowFile.readText() else ""
            if (!gshadowText.contains("root:")) gshadowText = "root:*::\n" + gshadowText
            if (!gshadowText.contains("cortex:")) gshadowText += "cortex:*::\n"
            if (!gshadowText.contains("_ssh:")) gshadowText += "_ssh:*::\n"
            if (!gshadowText.contains("ssh:")) gshadowText += "ssh:*::\n"
            gshadowText = BaseUsers.mergeShadow(gshadowText, baseGroup.added, BaseUsers::gshadowEntry)
            FileInstaller.install(gshadowFile, gshadowText, 420)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ensure passwd/group", e)
        }
    }

    private fun ensureReloadScripts(root: File, home: File, context: Context? = null) {
        ShellScriptsInstaller.ensureReloadScripts(root, home, context)
    }

    fun ensureEssentialBinaries(root: File, home: File, context: Context? = null) {
        try {
            val localBin = File(home, ".local/bin")
            localBin.mkdirs()

            val binDir = File(root, "bin")
            val isBinDirSymlink = binDir.exists() && ElfLinkerPatcher.isSymlink(binDir)

            // 1. awk guarantee: find mawk or gawk and copy as real ELF executable
            val mawkCandidates = listOf(
                File(root, "usr/bin/mawk"),
                File(root, "bin/mawk"),
                File(root, "usr/bin/gawk"),
                File(root, "bin/gawk")
            )
            val realAwk = mawkCandidates.firstOrNull { it.exists() && it.isFile }
            val awkTargets = mutableListOf(
                File(root, "usr/bin/awk"),
                File(localBin, "awk")
            )
            if (binDir.exists() && !isBinDirSymlink) {
                awkTargets.add(File(binDir, "awk"))
            }

            if (realAwk != null) {
                for (target in awkTargets) {
                    try {
                        if (target.exists() && target.canExecute()) {
                            continue
                        }
                        val tmp = File(target.parentFile ?: continue, "${target.name}.ctx_tmp")
                        realAwk.copyTo(tmp, overwrite = true)
                        tmp.setReadable(true, true)
                        tmp.setWritable(true, true)
                        tmp.setExecutable(true, true)
                        setMode(tmp, 448)
                        ElfLinkerPatcher.atomicReplace(tmp, target)
                        target.setReadable(true, true)
                        target.setWritable(true, true)
                        target.setExecutable(true, true)
                        setMode(target, 448)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to copy awk to ${target.absolutePath}", e)
                    }
                }
            }

            // 2. which guarantee: find which.debianutils or write native command -v wrapper
            val whichDebianCandidates = listOf(
                File(root, "usr/bin/which.debianutils"),
                File(root, "bin/which.debianutils")
            )
            val realWhich = whichDebianCandidates.firstOrNull { it.exists() && it.isFile }
            val whichTargets = mutableListOf(
                File(root, "usr/bin/which"),
                File(localBin, "which")
            )
            if (binDir.exists() && !isBinDirSymlink) {
                whichTargets.add(File(binDir, "which"))
            }

            for (target in whichTargets) {
                try {
                    if (target.exists() && target.canExecute()) {
                        continue
                    }
                    val tmp = File(target.parentFile ?: continue, "${target.name}.ctx_tmp")
                    if (realWhich != null) {
                        realWhich.copyTo(tmp, overwrite = true)
                    } else {
                        tmp.writeText("#!/bin/sh\ncommand -v \"\$@\"\n")
                    }
                    tmp.setReadable(true, true)
                    tmp.setWritable(true, true)
                    tmp.setExecutable(true, true)
                    setMode(tmp, 448)
                    ElfLinkerPatcher.atomicReplace(tmp, target)
                    target.setReadable(true, true)
                    target.setWritable(true, true)
                    target.setExecutable(true, true)
                    setMode(target, 448)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to setup which at ${target.absolutePath}", e)
                }
            }
            ensureServiceManager(root, context)
            ensureBrowserOpener(root, context)
            ensureRootTools(root, context)
            ShellScriptsInstaller.ensureBackupTool(root, context)
            restoreGpgv(root)
            ensureMachineId(root)
            ensureShm(root)
            ensureSystemdSharedLibs(root)
            ensureMountpoint(root)
            ensureJavaCaDirs(root)
            cleanupAutoLaunchers(root)
            ensureProfileEnvironment(root, context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed in ensureEssentialBinaries", e)
        }
    }

    fun restoreGpgv(root: File) {
        try {
            val gpgv = File(root, "usr/bin/gpgv")
            val gpgvOrig = File(root, "usr/bin/gpgv.orig")
            if (gpgvOrig.exists()) {
                gpgvOrig.copyTo(gpgv, overwrite = true)
                gpgv.setExecutable(true, true)
                gpgv.setReadable(true, true)
                gpgv.setWritable(true, true)
                setMode(gpgv, 448)
                gpgvOrig.delete()
                Log.i(TAG, "Restored native gpgv binary from gpgv.orig")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore gpgv", e)
        }
    }

    fun ensureMachineId(root: File) {
        try {
            val machineId = File(root, "etc/machine-id")
            if (!machineId.exists() || machineId.length() < 32) {
                machineId.parentFile?.mkdirs()
                val uuid = java.util.UUID.randomUUID().toString().replace("-", "")
                machineId.writeText("$uuid\n")
                machineId.setReadable(true, true)
                machineId.setWritable(true, true)
                setMode(machineId, 384)
            }
            val dbusDir = File(root, "var/lib/dbus")
            dbusDir.mkdirs()
            val dbusMachineId = File(dbusDir, "machine-id")
            val isDbusSymlink = ElfLinkerPatcher.isSymlink(dbusMachineId)
            if (!dbusMachineId.exists() || (!isDbusSymlink && dbusMachineId.length() < 32)) {
                if (dbusMachineId.exists() || isDbusSymlink) {
                    ElfLinkerPatcher.deleteIfExists(dbusMachineId)
                }
                if (!ElfLinkerPatcher.createSymlink("/etc/machine-id", dbusMachineId)) {
                    machineId.copyTo(dbusMachineId, overwrite = true)
                }
            }

            // Ensure dummy exit 0 scripts for systemd tools across bin directories
            val scriptContent = "#!/bin/sh\nexit 0\n"
            val dummyTools = listOf("systemd-machine-id-setup", "systemd-sysusers", "systemd-tmpfiles")
            val binDirs = listOf(
                File(root, "usr/bin"),
                File(root, "usr/sbin")
            )
            for (tool in dummyTools) {
                for (bDir in binDirs) {
                    try {
                        FileInstaller.install(File(bDir, tool), scriptContent, 448) // 0700
                    } catch (e: Exception) { Log.w(TAG, "Could not write $bDir/$tool", e) }
                }
            }

            // Ensure dpkg diversions prevent systemd package updates from overwriting dummy scripts
            val dpkgDir = File(root, "var/lib/dpkg")
            dpkgDir.mkdirs()
            val diversionsFile = File(dpkgDir, "diversions")
            val divEntries = listOf(
                "/usr/bin/systemd-machine-id-setup",
                "/bin/systemd-machine-id-setup",
                "/usr/sbin/systemd-machine-id-setup",
                "/usr/bin/systemd-sysusers",
                "/bin/systemd-sysusers",
                "/usr/sbin/systemd-sysusers",
                "/usr/bin/systemd-tmpfiles",
                "/bin/systemd-tmpfiles",
                "/usr/sbin/systemd-tmpfiles"
            )
            val currentDivText = if (diversionsFile.exists()) diversionsFile.readText() else ""
            val newDivBuilder = StringBuilder(currentDivText)
            for (p in divEntries) {
                if (!currentDivText.contains(p)) {
                    if (newDivBuilder.isNotEmpty() && !newDivBuilder.endsWith("\n")) {
                        newDivBuilder.append("\n")
                    }
                    newDivBuilder.append("$p\n$p.distrib\n:\n")
                }
            }
            if (newDivBuilder.toString() != currentDivText) {
                diversionsFile.writeText(newDivBuilder.toString())
                diversionsFile.setReadable(true, true)
                diversionsFile.setWritable(true, true)
                setMode(diversionsFile, 384)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ensure machine-id", e)
        }
    }

    /**
     * Removes the auto-installer wrapper scripts old releases put into /usr/local/bin. Only small
     * regular shell scripts carrying the wrapper's own banner are touched: the real tool (a
     * large Bun/Go binary that merely mentions its own URL) or a symlink to it must never be
     * read into memory or deleted.
     */
    fun cleanupAutoLaunchers(root: File) {
        try {
            val candidates = listOf(
                File(root, "usr/local/bin/opencode"),
                File(root, "usr/local/bin/muse"),
                File(root, "usr/bin/opencode"),
                File(root, "usr/bin/muse"),
                File(root, "bin/opencode"),
                File(root, "bin/muse")
            )
            for (cand in candidates) {
                if (!ShellScriptsInstaller.isLegacyAutoLauncher(cand)) continue
                cand.delete()
                Log.i(TAG, "Removed auto-installer wrapper: ${cand.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cleanup auto-launchers", e)
        }
    }

    fun ensureShm(root: File) {
        try {
            val tmpShm = File(root, "tmp/shm")
            tmpShm.mkdirs()
            tmpShm.setReadable(true, true)
            tmpShm.setWritable(true, true)
            tmpShm.setExecutable(true, true)
            setMode(tmpShm, 448)

            val devDir = File(root, "dev")
            devDir.mkdirs()
            val devShm = File(devDir, "shm")
            if (!devShm.exists()) {
                try {
                    android.system.Os.symlink("/tmp/shm", devShm.absolutePath)
                } catch (_: Exception) {
                    devShm.mkdirs()
                    devShm.setReadable(true, true)
                    devShm.setWritable(true, true)
                    devShm.setExecutable(true, true)
                    setMode(devShm, 448)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ensure shm", e)
        }
    }

    fun ensureSystemdSharedLibs(root: File) {
        try {
            val systemdLibDirs = listOf(
                File(root, "usr/lib/aarch64-linux-gnu/systemd"),
                File(root, "lib/aarch64-linux-gnu/systemd")
            )
            val usrLib = File(root, "usr/lib")
            val libDir = File(root, "lib")
            usrLib.mkdirs()
            libDir.mkdirs()

            for (sDir in systemdLibDirs) {
                if (sDir.exists() && sDir.isDirectory) {
                    sDir.listFiles()?.forEach { libFile ->
                        if (libFile.name.startsWith("libsystemd-shared")) {
                            val destUsr = File(usrLib, libFile.name)
                            if (!destUsr.exists()) {
                                try {
                                    android.system.Os.symlink(libFile.absolutePath, destUsr.absolutePath)
                                } catch (_: Exception) {
                                    try { libFile.copyTo(destUsr, overwrite = false) } catch (e: Exception) { Log.w(TAG, "Could not link or copy ${libFile.name} to ${destUsr.path}", e) }
                                }
                            }
                            val destLib = File(libDir, libFile.name)
                            if (!destLib.exists()) {
                                try {
                                    android.system.Os.symlink(libFile.absolutePath, destLib.absolutePath)
                                } catch (_: Exception) {
                                    try { libFile.copyTo(destLib, overwrite = false) } catch (e: Exception) { Log.w(TAG, "Could not link or copy ${libFile.name} to ${destLib.path}", e) }
                                }
                            }
                        }
                    }
                }
            }

            val usrLibSystemd = File(usrLib, "systemd")
            val archSystemd = File(root, "usr/lib/aarch64-linux-gnu/systemd")
            if (!usrLibSystemd.exists() && archSystemd.exists()) {
                try {
                    android.system.Os.symlink(archSystemd.absolutePath, usrLibSystemd.absolutePath)
                } catch (e: Exception) { Log.w(TAG, "Could not link ${usrLibSystemd.path}", e) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ensure systemd shared libs", e)
        }
    }

    fun ensureMountpoint(root: File) {
        try {
            val script = "#!/bin/sh\n" +
                "for arg in \"\$@\"; do\n" +
                "    case \"\$arg\" in\n" +
                "        /proc|/sys|/dev|/dev/pts|/proc/|/sys/|/dev/|/dev/shm) exit 0 ;;\n" +
                "    esac\n" +
                "done\n" +
                "for cand in /bin/mountpoint.orig /usr/bin/mountpoint.orig /bin/mountpoint /usr/bin/mountpoint; do\n" +
                "    if [ -x \"\$cand\" ] && [ \"\$cand\" != \"\$0\" ]; then\n" +
                "        exec \"\$cand\" \"\$@\"\n" +
                "    fi\n" +
                "done\n" +
                "exit 0\n"
            val targets = listOf(
                File(root, "usr/local/bin/mountpoint"),
                File(root, "usr/bin/mountpoint")
            )
            for (t in targets) {
                if (!t.exists() || !t.canExecute()) {
                    try {
                        t.parentFile?.mkdirs()
                        t.writeText(script)
                        t.setExecutable(true, true)
                        t.setReadable(true, true)
                        t.setWritable(true, true)
                        setMode(t, 448)
                    } catch (e: Exception) { Log.w(TAG, "Could not write ${t.path}", e) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ensure mountpoint", e)
        }
    }

    fun ensureJavaCaDirs(root: File) {
        try {
            File(root, "etc/ssl/certs/java").mkdirs()
            File(root, "var/lib/ca-certificates-java").mkdirs()
            val etcJava = File(root, "etc/.java/.systemPrefs")
            etcJava.mkdirs()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ensure Java CA dirs", e)
        }
    }

    /**
     * Writes Cortex's own environment into /etc/profile.d (the place Ubuntu reserves for
     * packages/administrators), so ~/.bashrc and ~/.profile stay the user's files.
     */
    fun ensureProfileEnvironment(root: File, context: Context? = null) {
        try {
            val profileD = File(root, "etc/profile.d")
            profileD.mkdirs()
            val envSh = File(profileD, "00-env.sh")
            val envContent = "# Cortex environment. Managed by the app and rewritten on every start;\n" +
                "# put personal settings in ~/.bashrc and ~/.profile instead.\n" +
                "if [ -n \"\${CORTEX_CHROOT:-}\" ] || [ ! -d /system/bin ]; then\n" +
                "    # Root mode: a real chroot of this rootfs (Android's /system is not in it), so the\n" +
                "    # preloaded library is not used. Marked again for shells that reset the environment.\n" +
                "    export CORTEX_CHROOT=1\n" +
                "    unset CORTEX_ROOT LD_PRELOAD LD_LIBRARY_PATH\n" +
                "else\n" +
                "    if [ -z \"\$CORTEX_ROOT\" ]; then\n" +
                "        if [ -d \"\$HOME/../etc\" ]; then\n" +
                "            export CORTEX_ROOT=\"\$(cd \"\$HOME/..\" && pwd)\"\n" +
                "        else\n" +
                "            export CORTEX_ROOT=\"" + root.absolutePath + "\"\n" +
                "        fi\n" +
                "    fi\n" +
                "    export LD_LIBRARY_PATH=\"" + LibraryPath.forRoot("\$CORTEX_ROOT") + "\"\n" +
                "    export LD_PRELOAD=\"\$CORTEX_ROOT/usr/lib/libcortex-hook.so\"\n" +
                "fi\n" +
                "export SHELL=\"\${SHELL:-/bin/bash}\"\n" +
                "# Ubuntu's default PATH (normally already set by /etc/profile) plus ~/.local/bin,\n" +
                "# each entry only once.\n" +
                "for __cortex_d in /usr/local/sbin /usr/local/bin /usr/sbin /usr/bin /sbin /bin; do\n" +
                "    case \":\$PATH:\" in\n" +
                "        *\":\$__cortex_d:\"*) ;;\n" +
                "        *) PATH=\"\$PATH:\$__cortex_d\" ;;\n" +
                "    esac\n" +
                "done\n" +
                "unset __cortex_d\n" +
                "case \":\$PATH:\" in\n" +
                "    *\":\$HOME/.local/bin:\"*) ;;\n" +
                "    *) PATH=\"\$HOME/.local/bin:\$PATH\" ;;\n" +
                "esac\n" +
                "export PATH\n"
            FileInstaller.install(envSh, envContent, 384) // 0600
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ensure profile environment", e)
        }
        ShellScriptsInstaller.ensureShellHelpers(root, context)
    }

    fun ensureServiceManager(root: File, context: Context? = null) {
        ShellScriptsInstaller.ensureServiceManager(root, context)
    }

    fun ensureBrowserOpener(root: File, context: Context? = null) {
        ShellScriptsInstaller.ensureBrowserOpener(root, context)
    }

    fun ensureRootTools(root: File, context: Context? = null) {
        ShellScriptsInstaller.ensureRootTools(root, context)
    }

    fun updateTimezone(context: Context, root: File) {
        try {
            val tz = try {
                java.util.TimeZone.getDefault()
            } catch (e: Exception) {
                null
            }
            val tzId = try { tz?.id ?: "UTC" } catch (e: Exception) { "UTC" }
            val etcDir = File(root, "etc")
            if (!etcDir.exists()) etcDir.mkdirs()

            val now = System.currentTimeMillis()
            val offsetMillis = tz?.getOffset(now) ?: 0
            val offsetSeconds = (offsetMillis / 1000).toInt()
            val totalMinutes = offsetMillis / 60000
            val posixSign = if (totalMinutes >= 0) "-" else "+"
            val absMinutes = Math.abs(totalMinutes)
            val hours = (absMinutes / 60).toInt()
            val mins = (absMinutes % 60).toInt()

            val shortName = try {
                val name = tz?.getDisplayName(tz.inDaylightTime(java.util.Date(now)), java.util.TimeZone.SHORT, java.util.Locale.US)
                if (!name.isNullOrEmpty() && name.all { it.isLetter() }) name else "GMT"
            } catch (e: Exception) {
                "GMT"
            }

            val stdName = when {
                shortName.length >= 3 && shortName.all { it.isLetter() } -> shortName
                totalMinutes >= 0 -> "<+%02d>".format(hours)
                else -> "<-%02d>".format(hours)
            }

            val posixTz = if (mins != 0) {
                "%s%s%d:%02d".format(stdName, posixSign, hours, mins)
            } else {
                "%s%s%d".format(stdName, posixSign, hours)
            }

            val abbr = if (totalMinutes >= 0) {
                "+%02d".format(hours)
            } else {
                "-%02d".format(hours)
            }

            val zoneinfoFile = File(root, "usr/share/zoneinfo/$tzId")
            val localTimeFile = File(etcDir, "localtime")
            val tzFile = File(etcDir, "timezone")

            // Both files are replaced in one step and only when the zone changed, so a program
            // reading them meanwhile never falls back to UTC.
            if (zoneinfoFile.exists() && zoneinfoFile.isFile) {
                try {
                    FileInstaller.install(localTimeFile, zoneinfoFile.readBytes(), 384) // 0600
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to copy zoneinfo to localtime", e)
                }
            } else {
                val tzifBytes = TzifGenerator.createTzifBytes(offsetSeconds, abbr, posixTz)
                try {
                    FileInstaller.install(localTimeFile, tzifBytes, 384)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to write generated localtime", e)
                }
            }

            val effectiveTz = if (zoneinfoFile.exists() && zoneinfoFile.isFile) tzId else posixTz
            FileInstaller.install(tzFile, effectiveTz + "\n", 384)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update timezone", e)
        }
    }

    fun runBackgroundCommand(context: Context, command: String, onProgress: ((String) -> Unit)? = null): Int {
        val shell = getInitialShellCommand(context)
        val env = Environment.buildEnvironment(context)
        val homeDir = Environment.getHomeDir(context).absolutePath
        val pty = PtyProcess.create(
            cmd = shell,
            args = arrayOf("-l", "-c", command),
            envVars = env,
            cwd = homeDir,
            rows = 24,
            cols = 80,
            widthPx = 0,
            heightPx = 0
        ) ?: return -1

        try {
            // All of the output (only the first 256 bytes of each read came through), decoded as one
            // text so that a character split between two reads stays whole. Not closed here: the
            // terminal belongs to the PtyProcess, which closes it in waitFor().
            val reader = java.io.InputStreamReader(pty.inputStream, Charsets.UTF_8)
            val buffer = CharArray(2048)
            while (true) {
                val read = reader.read(buffer)
                if (read <= 0) break
                onProgress?.invoke(String(buffer, 0, read))
            }
        } catch (e: java.io.IOException) {
            // EIO: the command has ended and closed the terminal
        }
        return pty.waitFor()
    }
}

/** Reports how much of [total] bytes has been read, in whole percent, whenever that changes. */
internal class ProgressInputStream(
    input: InputStream,
    private val total: Long,
    private val onProgress: (Int) -> Unit
) : java.io.FilterInputStream(input) {
    private var done = 0L
    private var reported = -1

    private fun advance(n: Long) {
        if (n <= 0) return
        done += n
        val percent = (done * 100 / total).toInt().coerceIn(0, 100)
        if (percent != reported) {
            reported = percent
            onProgress(percent)
        }
    }

    override fun read(): Int = super.read().also { if (it >= 0) advance(1) }

    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { advance(it.toLong()) }

    override fun skip(n: Long): Long = super.skip(n).also { advance(it) }
}
