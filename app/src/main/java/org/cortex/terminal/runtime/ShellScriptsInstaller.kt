package org.cortex.terminal.runtime

import android.content.Context
import android.os.Build
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

    /**
     * Installs the interactive-shell helpers (`reload`, automatic re-sourcing of ~/.bashrc when an
     * installer changes it) as /etc/profile.d/02-cortex-shell.sh, the Ubuntu-standard hook that
     * every login shell sources. The user's own dotfiles are not involved.
     */
    fun ensureShellHelpers(root: File, context: Context? = null) {
        try {
            val fallback = "[ -n \"\${BASH_VERSION-}\" ] || return 0 2>/dev/null\n" +
                "reload() {\n" +
                "    set --\n" +
                "    if [ -f \"\$HOME/.bashrc\" ]; then . \"\$HOME/.bashrc\"; fi\n" +
                "    if [ -f \"\$HOME/.profile\" ]; then . \"\$HOME/.profile\"; fi\n" +
                "    echo \"Configuration reloaded successfully.\"\n" +
                "}\n"
            val script = loadAssetScript(context, "cortex-shell.sh", fallback)

            val profileD = File(root, "etc/profile.d")
            profileD.mkdirs()
            val target = File(profileD, "02-cortex-shell.sh")
            val existing = try { if (target.isFile) target.readText() else null } catch (_: Exception) { null }
            if (existing != script) {
                target.writeText(script)
            }
            target.setReadable(true, false)
            target.setWritable(true, true)
            try { Os.chmod(target.absolutePath, 420) } catch (_: Exception) {} // 0644
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install shell helpers", e)
        }
    }

    /** Installs `cortex-backup` (back up / restore the home directory) into /usr/local/bin. */
    fun ensureBackupTool(root: File, context: Context? = null) {
        try {
            val fallback = "#!/bin/bash\necho \"cortex-backup is not available in this build.\" >&2\nexit 1\n"
            val script = loadAssetScript(context, "cortex-backup.sh", fallback)
            val localBin = File(root, "usr/local/bin")
            localBin.mkdirs()
            val target = File(localBin, "cortex-backup")
            val existing = try { if (target.isFile) target.readText() else null } catch (_: Exception) { null }
            if (existing != script) {
                target.writeText(script)
            }
            target.setReadable(true, false)
            target.setWritable(true, true)
            target.setExecutable(true, false)
            try { Os.chmod(target.absolutePath, 493) } catch (_: Exception) {} // 0755
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install cortex-backup", e)
        }
    }

    /**
     * True for the small auto-install wrapper scripts old releases put into /usr/local/bin. The
     * real tool (a large Bun/Go binary that merely mentions its own URL) and symlinks to it must
     * never be read into memory or deleted.
     */
    internal fun isLegacyAutoLauncher(file: File): Boolean {
        return try {
            if (!file.isFile || ElfLinkerPatcher.isSymlink(file) || file.length() > 64 * 1024) {
                false
            } else {
                val text = file.readText()
                text.startsWith("#!") &&
                    (text.contains("OpenCode CLI is not yet installed") ||
                        text.contains("Meta Muse Code CLI is not yet installed"))
            }
        } catch (e: Exception) {
            false
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

            // Remove legacy cortex_url_token if present
            try {
                File(root, "etc/cortex_url_token").delete()
            } catch (_: Exception) {}

            val xdgOpenFile = File(localBin, "xdg-open")
            // Opens links and files in Android apps through cortex-api; prints the link when that is
            // not possible (Cortex in the background, or no app for it).
            val simpleXdgOpenScript = "#!/bin/sh\n" +
                "if [ \$# -gt 0 ] && command -v cortex-api >/dev/null 2>&1 && cortex-api open \"\$1\" 2>/dev/null; then\n" +
                "    exit 0\n" +
                "fi\n" +
                "if [ \$# -gt 0 ]; then\n" +
                "    echo \"To open this URL, copy and paste it into your browser:\"\n" +
                "    echo \"\$1\"\n" +
                "fi\n" +
                "exit 0\n"

            try {
                xdgOpenFile.writeText(simpleXdgOpenScript)
                xdgOpenFile.setReadable(true, false)
                xdgOpenFile.setWritable(true, true)
                xdgOpenFile.setExecutable(true, false)
                try { Os.chmod(xdgOpenFile.absolutePath, 493) } catch (_: Exception) {} // 0755
            } catch (e: Exception) {
                Log.e(TAG, "Skipping xdg-open install: ${e.message}")
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

            val wrapperScript = "#!/bin/sh\nexec /usr/local/bin/xdg-open \"\$@\"\n"

            for (alias in browserAliases) {
                val aliasFile = File(localBin, alias)
                var linked = false
                try {
                    aliasFile.delete()
                    Os.symlink("xdg-open", aliasFile.absolutePath)
                    linked = true
                } catch (_: Exception) {}

                if (!linked) {
                    aliasFile.writeText(wrapperScript)
                    aliasFile.setReadable(true, false)
                    aliasFile.setWritable(true, true)
                    aliasFile.setExecutable(true, false)
                    try { Os.chmod(aliasFile.absolutePath, 493) } catch (_: Exception) {} // 0755
                }
            }

            // Also mirror xdg-open and browser openers into /usr/bin if /usr/bin exists
            val usrBin = File(root, "usr/bin")
            if (usrBin.exists() && usrBin.isDirectory) {
                val extraBins = listOf("xdg-open", "termux-open", "termux-open-url", "sensible-browser", "x-www-browser")
                for (bname in extraBins) {
                    val bFile = File(usrBin, bname)
                    if (!bFile.exists()) {
                        var linked = false
                        try {
                            Os.symlink("/usr/local/bin/xdg-open", bFile.absolutePath)
                            linked = true
                        } catch (_: Exception) {}

                        if (!linked) {
                            bFile.writeText(wrapperScript)
                            bFile.setReadable(true, false)
                            bFile.setWritable(true, true)
                            bFile.setExecutable(true, false)
                            try { Os.chmod(bFile.absolutePath, 493) } catch (_: Exception) {}
                        }
                    }
                }
            }
            ensureApiTools(localBin, context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ensure browser opener", e)
        }
    }

    /** Installs cortex-api (Android features for the shell) and notify-send, which uses it. */
    private fun ensureApiTools(localBin: File, context: Context?) {
        val tools = listOf(
            "cortex-api" to "cortex-api.sh",
            "notify-send" to "notify-send.sh"
        )
        for ((name, asset) in tools) {
            try {
                val fallback = "#!/bin/sh\necho \"$name is not available in this build of Cortex.\" >&2\nexit 1\n"
                val script = loadAssetScript(context, asset, fallback)
                val target = File(localBin, name)
                val existing = try { if (target.isFile) target.readText() else null } catch (_: Exception) { null }
                if (existing != script) {
                    target.writeText(script)
                }
                target.setReadable(true, false)
                target.setWritable(true, true)
                target.setExecutable(true, false)
                try { Os.chmod(target.absolutePath, 493) } catch (_: Exception) {} // 0755
            } catch (e: Exception) {
                Log.e(TAG, "Skipping $name install: ${e.message}")
            }
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
