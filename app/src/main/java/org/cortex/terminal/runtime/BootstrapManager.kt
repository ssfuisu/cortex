package org.cortex.terminal.runtime

import android.content.Context
import org.cortex.terminal.pty.PtyNative
import java.io.File
import java.io.InputStream
import java.io.PushbackInputStream
import java.util.zip.GZIPInputStream

object BootstrapManager {

    fun initializeFileSystem(context: Context) {
        val root = Environment.getCortexRoot(context)
        val home = Environment.getHomeDir(context)
        val tmp = Environment.getTmpDir(context)

        listOf(root, home, tmp).forEach { dir ->
            if (!dir.exists()) {
                dir.mkdirs()
            }
        }

        val d = "$"
        val bashrc = File(home, ".bashrc")
        if (!bashrc.exists() || !bashrc.readText().contains("PS1=")) {
            val bashrcContent = "# Cortex Terminal Environment\n" +
                "if [ -n \"" + d + "BASH_VERSION\" ]; then\n" +
                "    export PS1='\\w " + d + " '\n" +
                "else\n" +
                "    export PS1='~ " + d + " '\n" +
                "fi\n" +
                "alias ll='ls -la'\n" +
                "alias la='ls -A'\n" +
                "alias l='ls -CF'\n" +
                "alias cls='clear'\n"
            bashrc.writeText(bashrcContent)
        }

        val profile = File(home, ".profile")
        if (!profile.exists() || !profile.readText().contains("PS1=")) {
            val profileContent = "if [ -n \"" + d + "BASH_VERSION\" ]; then\n" +
                "    export PS1='\\w " + d + " '\n" +
                "else\n" +
                "    export PS1='~ " + d + " '\n" +
                "fi\n" +
                "if [ -f \"" + d + "HOME/.bashrc\" ]; then\n" +
                "    . \"" + d + "HOME/.bashrc\"\n" +
                "fi\n"
            profile.writeText(profileContent)
        }

        // File system structure initialized
    }

    private const val CURRENT_BOOTSTRAP_VERSION = 16

    fun isBootstrapInstalled(context: Context): Boolean {
        val root = Environment.getCortexRoot(context)
        val apt = File(root, "usr/bin/apt")
        val bash = File(root, "usr/bin/bash")
        val hook = File(root, "usr/lib/libcortex-hook.so")
        val versionFile = File(root, ".cortex_version")

        if (!apt.exists() || !bash.exists() || !hook.exists() || !versionFile.exists()) {
            return false
        }
        val ver = versionFile.readText().trim().toIntOrNull() ?: 0
        return ver >= CURRENT_BOOTSTRAP_VERSION
    }

    fun findBootstrapAsset(context: Context): String? {
        val is64 = CortexRuntime.is64Bit
        val candidates = if (is64) {
            listOf("bootstrap-arm64.tar", "bootstrap-arm64.tar.gz", "bootstrap-arm.tar", "bootstrap-arm.tar.gz")
        } else {
            listOf("bootstrap-arm.tar", "bootstrap-arm.tar.gz")
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

    fun installBootstrapFromAssets(context: Context): Boolean {
        val root = Environment.getCortexRoot(context)
        val assetName = findBootstrapAsset(context) ?: return false

        val tmpTar = File(context.cacheDir, "bootstrap.tar")
        return try {
            context.assets.open(assetName).use { rawIn ->
                val pushback = PushbackInputStream(rawIn, 2)
                val header = ByteArray(2)
                val bytesRead = pushback.read(header)
                if (bytesRead > 0) {
                    pushback.unread(header, 0, bytesRead)
                }
                val isGzip = (bytesRead >= 2 && (header[0].toInt() and 0xFF) == 0x1F && (header[1].toInt() and 0xFF) == 0x8B)
                val stream: InputStream = if (isGzip) GZIPInputStream(pushback) else pushback

                tmpTar.outputStream().use { out ->
                    stream.copyTo(out)
                }
            }

            // Clean up any non-symlink directories in root from previous runs that conflict with Debian UsrMerge
            listOf("bin", "sbin", "lib", "lib64").forEach { sub ->
                val f = File(root, sub)
                if (f.exists() && f.isDirectory) {
                    try {
                        if (!java.nio.file.Files.isSymbolicLink(f.toPath())) {
                            f.deleteRecursively()
                        }
                    } catch (e: Exception) {
                        f.deleteRecursively()
                    }
                }
            }

            // Extract archive using high performance native C extractor
            val extractResult = PtyNative.extractTar(tmpTar.absolutePath, root.absolutePath)
            if (extractResult != 0) {
                // Fallback to system tar
                val tarCmd = when {
                    File("/system/bin/tar").exists() -> listOf("/system/bin/tar", "-xf", tmpTar.absolutePath, "-C", root.absolutePath)
                    File("/system/bin/toybox").exists() -> listOf("/system/bin/toybox", "tar", "-xf", tmpTar.absolutePath, "-C", root.absolutePath)
                    else -> listOf("tar", "-xf", tmpTar.absolutePath, "-C", root.absolutePath)
                }
                val process = ProcessBuilder(tarCmd).redirectErrorStream(true).start()
                process.waitFor()
            }

            // Fix executable permissions on extracted binary directories and dynamic linkers
            root.walkTopDown().forEach { file ->
                if (file.isFile) {
                    val pName = file.parentFile?.name
                    if (pName in listOf("bin", "sbin") || file.name.startsWith("ld-linux")) {
                        file.setExecutable(true, false)
                        file.setReadable(true, false)
                    }
                }
            }

            // Ensure Glibc cortex-hook library is present and executable
            val hookAssetName = if (CortexRuntime.is64Bit) "libcortex-hook-arm64.so" else "libcortex-hook-arm.so"
            val targetHook = File(root, "usr/lib/libcortex-hook.so")
            try {
                targetHook.parentFile?.mkdirs()
                context.assets.open(hookAssetName).use { inStream ->
                    targetHook.outputStream().use { outStream ->
                        inStream.copyTo(outStream)
                    }
                }
            } catch (e: Exception) {
                // Handled if already inside tar
            }
            if (targetHook.exists()) {
                targetHook.setExecutable(true, false)
                targetHook.setReadable(true, false)
            }

            // Configure DNS
            val etcDir = File(root, "etc")
            etcDir.mkdirs()
            File(etcDir, "resolv.conf").writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")

            // Ensure /etc/passwd and /etc/group exist with root and cortex user definitions
            val passwdFile = File(etcDir, "passwd")
            var passwdText = if (passwdFile.exists()) passwdFile.readText() else ""
            if (!passwdText.contains("root:x:0:0")) {
                passwdText = "root:x:0:0:root:/root:/bin/bash\n" + passwdText
            }
            if (!passwdText.contains("cortex:")) {
                passwdText += "cortex:x:0:0:Cortex:/home:/bin/bash\n"
            }
            passwdFile.writeText(passwdText)

            val groupFile = File(etcDir, "group")
            var groupText = if (groupFile.exists()) groupFile.readText() else ""
            if (!groupText.contains("root:x:0:")) {
                groupText = "root:x:0:\n" + groupText
            }
            groupFile.writeText(groupText)

            // Ensure user homes exist
            File(root, "root").mkdirs()
            File(root, "home").mkdirs()

            // Configure APT sandbox so APT operates without superuser privilege drop
            val aptConfDir = File(root, "etc/apt/apt.conf.d")
            aptConfDir.mkdirs()
            File(aptConfDir, "01sandbox").writeText("APT::Sandbox::User \"root\";\n")

            // Ensure sources.list exists
            val sourcesList = File(root, "etc/apt/sources.list")
            if (!sourcesList.exists() || sourcesList.length() == 0L) {
                sourcesList.writeText(
                    "deb http://deb.debian.org/debian bookworm main contrib non-free non-free-firmware\n" +
                    "deb http://deb.debian.org/debian-security bookworm-security main contrib non-free non-free-firmware\n" +
                    "deb http://deb.debian.org/debian bookworm-updates main contrib non-free non-free-firmware\n"
                )
            }

            // Ensure dpkg status file exists
            val dpkgDir = File(root, "var/lib/dpkg")
            dpkgDir.mkdirs()
            val statusFile = File(dpkgDir, "status")
            if (!statusFile.exists()) {
                statusFile.createNewFile()
            }

            File(root, "var/lib/apt/lists/partial").mkdirs()
            File(root, "var/cache/apt/archives/partial").mkdirs()

            // Mark bootstrap version
            File(root, ".cortex_version").writeText(CURRENT_BOOTSTRAP_VERSION.toString())
            true
        } catch (e: Exception) {
            android.util.Log.e("BootstrapManager", "Error extracting bootstrap from APK", e)
            false
        } finally {
            if (tmpTar.exists()) {
                tmpTar.delete()
            }
        }
    }

    fun getInitialShellCommand(context: Context): String {
        val root = Environment.getCortexRoot(context)
        val debianBash = File(root, "usr/bin/bash")
        if (debianBash.exists()) {
            debianBash.setExecutable(true, false)
            return debianBash.absolutePath
        }
        val customBash = File(root, "bin/bash")
        if (customBash.exists()) {
            customBash.setExecutable(true, false)
            return customBash.absolutePath
        }
        val customSh = File(root, "bin/sh")
        if (customSh.exists()) {
            customSh.setExecutable(true, false)
            return customSh.absolutePath
        }
        return if (File("/system/bin/sh").exists()) "/system/bin/sh" else "/bin/sh"
    }
}
