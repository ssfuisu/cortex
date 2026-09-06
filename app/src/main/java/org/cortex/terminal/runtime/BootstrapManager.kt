package org.cortex.terminal.runtime

import android.content.Context
import java.io.File
import java.io.FileOutputStream

object BootstrapManager {

    fun initializeFileSystem(context: Context) {
        val root = Environment.getCortexRoot(context)
        val home = Environment.getHomeDir(context)
        val tmp = Environment.getTmpDir(context)
        val usr = Environment.getUsrDir(context)

        val dirs = listOf(
            root,
            home,
            tmp,
            usr,
            File(root, "bin"),
            File(root, "etc"),
            File(usr, "bin"),
            File(usr, "lib"),
            File(usr, "share"),
            File(root, "var/log")
        )

        for (dir in dirs) {
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

        val cortexInfo = File(root, "bin/cortex-info")
        if (!cortexInfo.exists()) {
            val script = "#!/system/bin/sh\n" +
                "echo \"Cortex Glibc Terminal\"\n" +
                "echo \"Architecture: " + d + "(uname -m)\"\n" +
                "echo \"Kernel: " + d + "(uname -r)\"\n" +
                "echo \"Cortex Root: " + d + "CORTEX_ROOT\"\n" +
                "echo \"Prefix: " + d + "PREFIX\"\n"
            cortexInfo.writeText(script)
            cortexInfo.setExecutable(true, false)
        }

        if (!isBootstrapInstalled(context)) {
            installBootstrapFromAssets(context)
        }
    }

    fun isBootstrapInstalled(context: Context): Boolean {
        val root = Environment.getCortexRoot(context)
        val apt = File(root, "usr/bin/apt")
        val bash = File(root, "usr/bin/bash")
        return apt.exists() && bash.exists()
    }

    fun installBootstrapFromAssets(context: Context): Boolean {
        val root = Environment.getCortexRoot(context)
        val is64 = CortexRuntime.is64Bit
        val assetName = if (is64) "bootstrap-arm64.tar.gz" else "bootstrap-arm.tar.gz"

        val hasAsset = try {
            context.assets.list("")?.contains(assetName) == true
        } catch (e: Exception) {
            false
        }

        if (!hasAsset) return false

        val tmpArchive = File(context.cacheDir, assetName)
        return try {
            context.assets.open(assetName).use { input ->
                tmpArchive.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            val tarBin = File("/system/bin/tar")
            if (tarBin.exists() && tarBin.canExecute()) {
                val process = ProcessBuilder(
                    tarBin.absolutePath,
                    "-xzf",
                    tmpArchive.absolutePath,
                    "-C",
                    root.absolutePath
                ).redirectErrorStream(true).start()
                process.waitFor()
            }

            // Fix executable permissions on extracted binary directories
            listOf("bin", "sbin", "usr/bin", "usr/sbin").forEach { sub ->
                File(root, sub).listFiles()?.forEach { file ->
                    file.setExecutable(true, false)
                }
            }

            // Configure DNS
            val etcDir = File(root, "etc")
            etcDir.mkdirs()
            File(etcDir, "resolv.conf").writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")

            // Ensure dpkg status file exists
            val dpkgDir = File(root, "var/lib/dpkg")
            dpkgDir.mkdirs()
            val statusFile = File(dpkgDir, "status")
            if (!statusFile.exists()) {
                statusFile.createNewFile()
            }

            File(root, "var/lib/apt/lists/partial").mkdirs()
            File(root, "var/cache/apt/archives/partial").mkdirs()
            true
        } catch (e: Exception) {
            android.util.Log.e("BootstrapManager", "Error extracting bootstrap from APK", e)
            false
        } finally {
            tmpArchive.delete()
        }
    }

    fun getInitialShellCommand(context: Context): String {
        val root = Environment.getCortexRoot(context)
        val debianBash = File(root, "usr/bin/bash")
        if (debianBash.exists() && debianBash.canExecute()) {
            return debianBash.absolutePath
        }
        val customBash = File(root, "bin/bash")
        if (customBash.exists() && customBash.canExecute()) {
            return customBash.absolutePath
        }
        val customSh = File(root, "bin/sh")
        if (customSh.exists() && customSh.canExecute()) {
            return customSh.absolutePath
        }
        return if (File("/system/bin/sh").exists()) "/system/bin/sh" else "/bin/sh"
    }
}
