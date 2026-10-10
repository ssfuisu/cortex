package org.cortex.terminal.runtime

import android.content.Context
import android.system.Os
import android.util.Log
import java.io.File

object ShellScriptsInstaller {
    private const val TAG = "ShellScriptsInstaller"

    fun requireFunctionalScript(assetName: String, content: String): String {
        val hasExecutableLine = content.lines().any { line ->
            line.isNotBlank() && !line.trimStart().startsWith("#")
        }
        if (!hasExecutableLine) {
            throw IllegalStateException("Missing or non-functional script asset: $assetName")
        }
        return content
    }

    internal fun loadAssetScript(context: Context?, assetName: String, fallback: String): String {
        val fromAsset = if (context != null) {
            try {
                context.assets.open("scripts/$assetName").bufferedReader().use { it.readText() }
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        val candidate = if (fromAsset != null &&
            fromAsset.lines().any { it.isNotBlank() && !it.trimStart().startsWith("#") }
        ) {
            fromAsset
        } else {
            fallback
        }
        return requireFunctionalScript(assetName, candidate)
    }

    fun ensureReloadScripts(root: File, home: File, context: Context? = null) {
        try {
            val fallbackScript = "#!/bin/bash\n" +
                "set --\n" +
                "if (return 0 2>/dev/null); then\n" +
                "    if [ -f \"\$HOME/.bashrc\" ]; then . \"\$HOME/.bashrc\"; fi\n" +
                "    if [ -f \"\$HOME/.profile\" ]; then . \"\$HOME/.profile\"; fi\n" +
                "    if [ -f \"\$HOME/.bash_profile\" ]; then . \"\$HOME/.bash_profile\"; fi\n" +
                "    echo \"Environment reloaded.\"\n" +
                "else\n" +
                "    echo \"Environment reloaded.\"\n" +
                "    exec \"\${SHELL:-/bin/bash}\" -l\n" +
                "fi\n"
            val loaded = loadAssetScript(context, "reload.sh", fallbackScript)
            val reloadScript = if (loaded.contains("return 0")) loaded else fallbackScript

            val reloadDirs = listOf(File(root, "usr/bin"), File(root, "bin"), File(home, ".local/bin"))
            reloadDirs.forEach { dir ->
                if (dir.exists()) {
                    val rFile = File(dir, "reload")
                    rFile.writeText(reloadScript)
                    rFile.setExecutable(true, true)
                    rFile.setReadable(true, true)
                    rFile.setWritable(true, true)
                    try { Os.chmod(rFile.absolutePath, 448) } catch (_: Exception) {}
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

            try {
                val serviceScript = loadAssetScript(context, "service.sh", "#!/bin/bash\n")
                val serviceFile = File(localBin, "service")
                serviceFile.writeText(serviceScript)
                serviceFile.setReadable(true, true)
                serviceFile.setWritable(true, true)
                serviceFile.setExecutable(true, true)
                try { Os.chmod(serviceFile.absolutePath, 448) } catch (_: Exception) {}
            } catch (e: Exception) {
                Log.e(TAG, "Skipping service script install: ${e.message}")
            }

            try {
                val systemctlScript = loadAssetScript(context, "systemctl.sh", "#!/bin/bash\n")
                val systemctlFile = File(localBin, "systemctl")
                systemctlFile.writeText(systemctlScript)
                systemctlFile.setReadable(true, true)
                systemctlFile.setWritable(true, true)
                systemctlFile.setExecutable(true, true)
                try { Os.chmod(systemctlFile.absolutePath, 448) } catch (_: Exception) {}
            } catch (e: Exception) {
                Log.e(TAG, "Skipping systemctl script install: ${e.message}")
            }

            val cortexServiceFile = File(localBin, "cortex-service")
            val cortexServiceScript = requireFunctionalScript(
                "cortex-service",
                "#!/bin/sh\nexec service \"\$@\"\n"
            )
            cortexServiceFile.writeText(cortexServiceScript)
            cortexServiceFile.setReadable(true, true)
            cortexServiceFile.setWritable(true, true)
            cortexServiceFile.setExecutable(true, true)
            try { Os.chmod(cortexServiceFile.absolutePath, 448) } catch (_: Exception) {}
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
                    try { f.delete() } catch (_: Exception) {}
                }
            }

            try {
                val xdgOpenScript = loadAssetScript(context, "xdg-open.sh", "#!/bin/sh\n")
                val xdgOpenFile = File(localBin, "xdg-open")
                xdgOpenFile.writeText(xdgOpenScript)
                xdgOpenFile.setReadable(true, false)
                xdgOpenFile.setWritable(true, true)
                xdgOpenFile.setExecutable(true, false)
                try { Os.chmod(xdgOpenFile.absolutePath, 493) } catch (_: Exception) {} // 0755
            } catch (e: Exception) {
                Log.e(TAG, "Skipping xdg-open script install: ${e.message}")
            }

            val browserAliases = listOf(
                "sensible-browser",
                "x-www-browser",
                "google-chrome",
                "google-chrome-stable",
                "chromium",
                "chromium-browser",
                "firefox",
                "open",
                "termux-open",
                "termux-open-url"
            )

            val wrapperScript = requireFunctionalScript(
                "xdg-open-wrapper",
                "#!/bin/sh\nexec /usr/local/bin/xdg-open \"\$@\"\n"
            )

            for (alias in browserAliases) {
                val aliasFile = File(localBin, alias)
                aliasFile.writeText(wrapperScript)
                aliasFile.setReadable(true, false)
                aliasFile.setWritable(true, true)
                aliasFile.setExecutable(true, false)
                try { Os.chmod(aliasFile.absolutePath, 493) } catch (_: Exception) {} // 0755
            }

            // Also mirror xdg-open and termux openers into /usr/bin if /usr/bin exists
            val usrBin = File(root, "usr/bin")
            if (usrBin.exists() && usrBin.isDirectory) {
                val extraBins = listOf("xdg-open", "termux-open", "termux-open-url")
                for (bname in extraBins) {
                    val bFile = File(usrBin, bname)
                    if (!bFile.exists()) {
                        bFile.writeText(wrapperScript)
                        bFile.setReadable(true, false)
                        bFile.setWritable(true, true)
                        bFile.setExecutable(true, false)
                        try { Os.chmod(bFile.absolutePath, 493) } catch (_: Exception) {}
                    }
                }
            }

            // Persist cortex_url_token into rootfs /etc/cortex_url_token so all container users can read it
            try {
                if (context != null) {
                    val token = UrlOpenerServer.getOrCreateToken(context)
                    val etcDir = File(root, "etc")
                    if (etcDir.exists()) {
                        val tokenFile = File(etcDir, "cortex_url_token")
                        tokenFile.writeText(token, Charsets.UTF_8)
                        tokenFile.setReadable(true, false)
                        try { Os.chmod(tokenFile.absolutePath, 420) } catch (_: Exception) {} // 0644
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to persist token to /etc/cortex_url_token", e)
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

            val suFile = File(localBin, "su")
            try {
                val suScript = loadAssetScript(context, "su.sh", "#!/bin/bash\n")
                suFile.writeText(suScript)
                suFile.setReadable(true, true)
                suFile.setWritable(true, true)
                suFile.setExecutable(true, true)
                try { Os.chmod(suFile.absolutePath, 448) } catch (_: Exception) {}
            } catch (e: Exception) {
                Log.e(TAG, "Skipping su script install: ${e.message}")
            }

            val tsuFile = File(localBin, "tsu")
            val sudoFile = File(localBin, "sudo")
            val rootFile = File(localBin, "root")
            if (suFile.exists()) {
                val tsuFallback = "#!/bin/sh\nexec /usr/local/bin/su \"\$@\"\n"
                val tsuScript = loadAssetScript(context, "tsu.sh", tsuFallback)
                tsuFile.writeText(tsuScript)
                tsuFile.setReadable(true, true)
                tsuFile.setWritable(true, true)
                tsuFile.setExecutable(true, true)
                try { Os.chmod(tsuFile.absolutePath, 448) } catch (_: Exception) {}

                sudoFile.writeText(tsuScript)
                sudoFile.setReadable(true, true)
                sudoFile.setWritable(true, true)
                sudoFile.setExecutable(true, true)
                try { Os.chmod(sudoFile.absolutePath, 448) } catch (_: Exception) {}

                rootFile.writeText(tsuScript)
                rootFile.setReadable(true, true)
                rootFile.setWritable(true, true)
                rootFile.setExecutable(true, true)
                try { Os.chmod(rootFile.absolutePath, 448) } catch (_: Exception) {}
            } else {
                for (wrapper in listOf(tsuFile, sudoFile, rootFile)) {
                    if (wrapper.exists()) {
                        try { wrapper.delete() } catch (_: Exception) {}
                    }
                }
            }

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
