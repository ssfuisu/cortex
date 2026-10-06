package org.cortex.terminal.runtime

import android.content.Context
import android.util.Log
import java.io.File

object ShellScriptsInstaller {
    private const val TAG = "ShellScriptsInstaller"

    private fun loadAssetScript(context: Context?, assetName: String, fallback: String): String {
        if (context == null) return fallback
        return try {
            context.assets.open("scripts/$assetName").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            fallback
        }
    }

    fun ensureReloadScripts(root: File, home: File, context: Context? = null) {
        try {
            val fallbackScript = "#!/bin/bash\n" +
                "set --\n" +
                "if [ -f \"\$HOME/.bashrc\" ]; then . \"\$HOME/.bashrc\"; fi\n" +
                "if [ -f \"\$HOME/.profile\" ]; then . \"\$HOME/.profile\"; fi\n" +
                "if [ -f \"\$HOME/.bash_profile\" ]; then . \"\$HOME/.bash_profile\"; fi\n" +
                "echo \"Environment reloaded.\"\n"
            val reloadScript = loadAssetScript(context, "reload.sh", fallbackScript)

            val reloadDirs = listOf(File(root, "usr/bin"), File(root, "bin"), File(home, ".local/bin"))
            reloadDirs.forEach { dir ->
                if (dir.exists()) {
                    val rFile = File(dir, "reload")
                    rFile.writeText(reloadScript)
                    rFile.setExecutable(true, true)
                    rFile.setReadable(true, true)
                    rFile.setWritable(true, true)
                    try { android.system.Os.chmod(rFile.absolutePath, 448) } catch (e: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create reload scripts", e)
        }
    }

    fun ensureServiceManager(root: File, context: Context? = null) {
        try {
            File(root, "run").mkdirs()
            File(root, "var/run").mkdirs()
            File(root, "var/lock").mkdirs()
            File(root, "etc/init.d").mkdirs()
            File(root, "etc/cortex/autostart").mkdirs()

            val localBin = File(root, "usr/local/bin")
            localBin.mkdirs()

            val serviceScript = loadAssetScript(context, "service.sh", "#!/bin/bash\n")
            val serviceFile = File(localBin, "service")
            serviceFile.writeText(serviceScript)
            serviceFile.setReadable(true, true)
            serviceFile.setWritable(true, true)
            serviceFile.setExecutable(true, true)
            try { android.system.Os.chmod(serviceFile.absolutePath, 448) } catch (e: Exception) {}

            val systemctlScript = loadAssetScript(context, "systemctl.sh", "#!/bin/bash\n")
            val systemctlFile = File(localBin, "systemctl")
            systemctlFile.writeText(systemctlScript)
            systemctlFile.setReadable(true, true)
            systemctlFile.setWritable(true, true)
            systemctlFile.setExecutable(true, true)
            try { android.system.Os.chmod(systemctlFile.absolutePath, 448) } catch (e: Exception) {}

            val cortexServiceFile = File(localBin, "cortex-service")
            cortexServiceFile.writeText("#!/bin/sh\nexec service \"\$@\"\n")
            cortexServiceFile.setReadable(true, true)
            cortexServiceFile.setWritable(true, true)
            cortexServiceFile.setExecutable(true, true)
            try { android.system.Os.chmod(cortexServiceFile.absolutePath, 448) } catch (e: Exception) {}
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ensure service manager", e)
        }
    }

    fun ensureBrowserOpener(root: File, context: Context? = null) {
        try {
            val localBin = File(root, "usr/local/bin")
            localBin.mkdirs()

            // Remove deprecated audio files if they exist
            val oldAudioFiles = listOf("play-audio", "cortex-play", "speaker-test", "aplay", "paplay")
            for (fname in oldAudioFiles) {
                val f = File(localBin, fname)
                if (f.exists()) {
                    try { f.delete() } catch (e: Exception) {}
                }
            }

            val xdgOpenScript = loadAssetScript(context, "xdg-open.sh", "#!/bin/sh\n")
            val xdgOpenFile = File(localBin, "xdg-open")
            xdgOpenFile.writeText(xdgOpenScript)
            xdgOpenFile.setReadable(true, true)
            xdgOpenFile.setWritable(true, true)
            xdgOpenFile.setExecutable(true, true)
            try { android.system.Os.chmod(xdgOpenFile.absolutePath, 448) } catch (e: Exception) {}

            val browserAliases = listOf(
                "sensible-browser",
                "x-www-browser",
                "google-chrome",
                "google-chrome-stable",
                "chromium",
                "chromium-browser",
                "firefox",
                "open"
            )

            val wrapperScript = "#!/bin/sh\nexec /usr/local/bin/xdg-open \"\$@\"\n"

            for (alias in browserAliases) {
                val aliasFile = File(localBin, alias)
                aliasFile.writeText(wrapperScript)
                aliasFile.setReadable(true, true)
                aliasFile.setWritable(true, true)
                aliasFile.setExecutable(true, true)
                try { android.system.Os.chmod(aliasFile.absolutePath, 448) } catch (e: Exception) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ensure browser opener", e)
        }
    }

    fun ensureRootTools(root: File, context: Context? = null) {
        try {
            val rootHome = File(root, "root")
            if (!rootHome.exists()) {
                rootHome.mkdirs()
            }
            val rootBashrc = File(rootHome, ".bashrc")
            if (!rootBashrc.exists()) {
                rootBashrc.writeText(
                    "# Root profile for Cortex Terminal\n" +
                    "export PS1='\\[\\033[01;31m\\]\\u@\\h\\[\\033[00m\\]:\\[\\033[01;34m\\]\\w\\[\\033[00m\\]# '\n" +
                    "alias ll='ls -la'\n" +
                    "alias la='ls -A'\n" +
                    "alias l='ls -CF'\n" +
                    "alias cls='clear'\n"
                )
                rootBashrc.setReadable(true, true)
                rootBashrc.setWritable(true, true)
            }

            val localBin = File(root, "usr/local/bin")
            if (!localBin.exists()) localBin.mkdirs()

            val suScript = loadAssetScript(context, "su.sh", "#!/bin/bash\n")
            val suFile = File(localBin, "su")
            suFile.writeText(suScript)
            suFile.setReadable(true, true)
            suFile.setWritable(true, true)
            suFile.setExecutable(true, true)
            try { android.system.Os.chmod(suFile.absolutePath, 448) } catch (e: Exception) {}

            val tsuScript = loadAssetScript(context, "tsu.sh", "#!/bin/sh\n")
            val tsuFile = File(localBin, "tsu")
            tsuFile.writeText(tsuScript)
            tsuFile.setReadable(true, true)
            tsuFile.setWritable(true, true)
            tsuFile.setExecutable(true, true)
            try { android.system.Os.chmod(tsuFile.absolutePath, 448) } catch (e: Exception) {}

            val sudoFile = File(localBin, "sudo")
            sudoFile.writeText(tsuScript)
            sudoFile.setReadable(true, true)
            sudoFile.setWritable(true, true)
            sudoFile.setExecutable(true, true)
            try { android.system.Os.chmod(sudoFile.absolutePath, 448) } catch (e: Exception) {}

            val rootFile = File(localBin, "root")
            rootFile.writeText(tsuScript)
            rootFile.setReadable(true, true)
            rootFile.setWritable(true, true)
            rootFile.setExecutable(true, true)
            try { android.system.Os.chmod(rootFile.absolutePath, 448) } catch (e: Exception) {}

            val nanoDir = File(root, "usr/share/nano")
            if (!nanoDir.exists()) nanoDir.mkdirs()
            val defaultNanorc = File(nanoDir, "default.nanorc")
            if (!defaultNanorc.exists()) {
                defaultNanorc.writeText("## Default syntax highlighting\nsyntax \"default\"\n")
                defaultNanorc.setReadable(true, true)
                defaultNanorc.setWritable(true, true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ensure root tools", e)
        }
    }
}
