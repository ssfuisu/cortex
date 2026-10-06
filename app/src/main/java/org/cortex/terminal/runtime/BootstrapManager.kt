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
    const val CURRENT_BOOTSTRAP_VERSION = 12523

    fun initializeFileSystem(context: Context) {
        val root = Environment.getCortexRoot(context)
        val home = Environment.getHomeDir(context)
        val tmp = Environment.getTmpDir(context)

        listOf(root, home, tmp).forEach { dir ->
            if (!dir.exists()) {
                dir.mkdirs()
            }
            dir.setReadable(true, true)
            dir.setWritable(true, true)
            dir.setExecutable(true, true)
            try { android.system.Os.chmod(dir.absolutePath, 448) } catch (e: Exception) {} // 0700
        }
        ensureRootTools(root, context)
        ensureBrowserOpener(root, context)

        val d = "$"
        val certExportSnippet = "if [ -z \"" + d + "CORTEX_ROOT\" ]; then\n" +
            "    if [ -d \"" + d + "HOME/../etc\" ]; then\n" +
            "        export CORTEX_ROOT=\"$(cd \"" + d + "HOME/..\" && pwd)\"\n" +
            "    fi\n" +
            "fi\n" +
            "if [ -n \"" + d + "CORTEX_ROOT\" ]; then\n" +
            "    export SSL_CERT_FILE=\"" + d + "CORTEX_ROOT/etc/ssl/certs/ca-certificates.crt\"\n" +
            "    export SSL_CERT_DIR=\"" + d + "CORTEX_ROOT/etc/ssl/certs:/system/etc/security/cacerts\"\n" +
            "    export CURL_CA_BUNDLE=\"" + d + "CORTEX_ROOT/etc/ssl/certs/ca-certificates.crt\"\n" +
            "    export NODE_EXTRA_CA_CERTS=\"" + d + "CORTEX_ROOT/etc/ssl/certs/ca-certificates.crt\"\n" +
            "    export REQUESTS_CA_BUNDLE=\"" + d + "CORTEX_ROOT/etc/ssl/certs/ca-certificates.crt\"\n" +
            "else\n" +
            "    export SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt\n" +
            "    export CURL_CA_BUNDLE=/etc/ssl/certs/ca-certificates.crt\n" +
            "fi\n"

        try {
            val bashrc = File(home, ".bashrc")
            var bashrcText = if (bashrc.exists()) {
                try { bashrc.readText() } catch (e: Exception) { "" }
            } else ""
            var changed = false

            // Strip legacy source() override if present
            if (bashrcText.contains("source()")) {
                bashrcText = bashrcText.replace(Regex("source\\s*\\(\\)\\s*\\{[\\s\\S]*?\\n\\}"), "")
                changed = true
            }

            if (!bashrcText.contains("unset PREFIX")) {
                bashrcText = "unset PREFIX\n" + bashrcText
                changed = true
            }

            if (!bashrcText.contains("checkwinsize")) {
                bashrcText = "shopt -s checkwinsize 2>/dev/null\n" + bashrcText
                changed = true
            }

            if (!bashrcText.contains("GODEBUG")) {
                bashrcText = "export GODEBUG=netdns=cgo\n" + bashrcText
                changed = true
            }

            if (!bashrcText.contains("PS1=")) {
                bashrcText += "# Cortex Terminal Environment\n" +
                    "if [ -n \"" + d + "BASH_VERSION\" ]; then\n" +
                    "    export PS1='\\w " + d + " '\n" +
                    "else\n" +
                    "    export PS1='~ " + d + " '\n" +
                    "fi\n" +
                    "alias ll='ls -la'\n" +
                    "alias la='ls -A'\n" +
                    "alias l='ls -CF'\n" +
                    "alias cls='clear'\n"
                changed = true
            }
            if (bashrcText.contains(".opencode/bin")) {
                bashrcText = bashrcText.replace(d + "HOME/.opencode/bin:", "")
                bashrcText = bashrcText.replace("/home/.opencode/bin:", "")
                changed = true
            }
            if (!bashrcText.contains(".local/bin")) {
                bashrcText += "export PATH=\"" + d + "HOME/.local/bin:" + d + "PATH\"\n"
                changed = true
            }

            if (bashrcText.contains("export SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt")) {
                bashrcText = bashrcText.replace(
                    "export SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt\nexport CURL_CA_BUNDLE=/etc/ssl/certs/ca-certificates.crt\n",
                    certExportSnippet
                )
                if (bashrcText.contains("export SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt")) {
                    bashrcText = bashrcText.replace("export SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt", certExportSnippet)
                }
                changed = true
            } else if (!bashrcText.contains("SSL_CERT_FILE")) {
                bashrcText += certExportSnippet
                changed = true
            }
            if (!bashrcText.contains("TZDIR")) {
                bashrcText += "if [ -d \"" + d + "HOME/../usr/share/zoneinfo\" ]; then\n" +
                    "    export TZDIR=\"" + d + "HOME/../usr/share/zoneinfo\"\n" +
                    "elif [ -d \"/usr/share/zoneinfo\" ]; then\n" +
                    "    export TZDIR=\"/usr/share/zoneinfo\"\n" +
                    "fi\n"
                changed = true
            }
            if (!bashrcText.contains("export TZ=")) {
                bashrcText += "if [ -f /etc/timezone ]; then export TZ=\"$(cat /etc/timezone 2>/dev/null)\"; fi\n"
                changed = true
            }

            val reloadFn = "reload() {\n" +
                "    set --\n" +
                "    if [ -f \"" + d + "HOME/.bashrc\" ]; then . \"" + d + "HOME/.bashrc\"; fi\n" +
                "    if [ -f \"" + d + "HOME/.profile\" ]; then . \"" + d + "HOME/.profile\"; fi\n" +
                "    if [ -f \"" + d + "HOME/.bash_profile\" ]; then . \"" + d + "HOME/.bash_profile\"; fi\n" +
                "    echo \"Configuration reloaded successfully.\"\n" +
                "}\n" +
                "alias reload='reload'\n"

            if (bashrcText.contains("reload()")) {
                bashrcText = bashrcText.replace(Regex("reload\\s*\\(\\)\\s*\\{[\\s\\S]*?\\n\\}"), "")
                bashrcText = bashrcText.lines().filter { !it.startsWith("alias reload=") }.joinToString("\n").trimEnd()
                bashrcText += "\n\n" + reloadFn
                changed = true
            } else {
                bashrcText = bashrcText.trimEnd() + "\n\n" + reloadFn
                changed = true
            }

            if (!bashrcText.contains("_CORTEX_PROF_GUARD")) {
                bashrcText += "\nif [ -f \"" + d + "HOME/.profile\" ] && [ -z \"" + d + "_CORTEX_PROF_GUARD\" ]; then\n" +
                    "    _CORTEX_PROF_GUARD=1\n" +
                    "    . \"" + d + "HOME/.profile\"\n" +
                    "    unset _CORTEX_PROF_GUARD\n" +
                    "fi\n"
                changed = true
            }

            if (!bashrcText.contains("/etc/cortex/autostart")) {
                bashrcText += "\nif [ -d /etc/cortex/autostart ]; then\n" +
                    "    for s in /etc/cortex/autostart/*; do\n" +
                    "        if [ -f \"" + d + "s\" ]; then\n" +
                    "            sname=\"" + d + "(basename \"" + d + "s\")\"\n" +
                    "            service \"" + d + "sname\" start >/dev/null 2>&1 || true\n" +
                    "        fi\n" +
                    "    done\n" +
                    "fi\n"
                changed = true
            }

            if (changed) {
                bashrc.writeText(bashrcText.trim() + "\n")
                bashrc.setReadable(true, true)
                bashrc.setWritable(true, true)
                try { android.system.Os.chmod(bashrc.absolutePath, 384) } catch (e: Exception) {} // 0600
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ensure .bashrc", e)
        }

        try {
            val profile = File(home, ".profile")
            var profileText = if (profile.exists()) {
                try { profile.readText() } catch (e: Exception) { "" }
            } else ""
            var changed = false

            // Strip legacy source() override if present
            if (profileText.contains("source()")) {
                profileText = profileText.replace(Regex("source\\s*\\(\\)\\s*\\{[\\s\\S]*?\\n\\}"), "")
                changed = true
            }

            if (!profileText.contains("unset PREFIX")) {
                profileText = "unset PREFIX\n" + profileText
                changed = true
            }

            if (!profileText.contains("checkwinsize")) {
                profileText = "shopt -s checkwinsize 2>/dev/null\n" + profileText
                changed = true
            }

            if (!profileText.contains("GODEBUG")) {
                profileText = "export GODEBUG=netdns=cgo\n" + profileText
                changed = true
            }

            if (!profileText.contains("PS1=")) {
                profileText += "if [ -n \"" + d + "BASH_VERSION\" ]; then\n" +
                    "    export PS1='\\w " + d + " '\n" +
                    "else\n" +
                    "    export PS1='~ " + d + " '\n" +
                    "fi\n"
                changed = true
            }
            if (!profileText.contains(".local/bin")) {
                profileText += "export PATH=\"" + d + "HOME/.local/bin:" + d + "PATH\"\n"
                changed = true
            }
            if (profileText.contains("export SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt")) {
                profileText = profileText.replace(
                    "export SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt\nexport CURL_CA_BUNDLE=/etc/ssl/certs/ca-certificates.crt\n",
                    certExportSnippet
                )
                if (profileText.contains("export SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt")) {
                    profileText = profileText.replace("export SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt", certExportSnippet)
                }
                changed = true
            } else if (!profileText.contains("SSL_CERT_FILE")) {
                profileText += certExportSnippet
                changed = true
            }
            if (!profileText.contains("TZDIR")) {
                profileText += "if [ -d \"" + d + "HOME/../usr/share/zoneinfo\" ]; then\n" +
                    "    export TZDIR=\"" + d + "HOME/../usr/share/zoneinfo\"\n" +
                    "elif [ -d \"/usr/share/zoneinfo\" ]; then\n" +
                    "    export TZDIR=\"/usr/share/zoneinfo\"\n" +
                    "fi\n"
                changed = true
            }
            if (!profileText.contains("export TZ=")) {
                profileText += "if [ -f /etc/timezone ]; then export TZ=\"$(cat /etc/timezone 2>/dev/null)\"; fi\n"
                changed = true
            }

            val reloadFn = "reload() {\n" +
                "    set --\n" +
                "    if [ -f \"" + d + "HOME/.bashrc\" ]; then . \"" + d + "HOME/.bashrc\"; fi\n" +
                "    if [ -f \"" + d + "HOME/.profile\" ]; then . \"" + d + "HOME/.profile\"; fi\n" +
                "    if [ -f \"" + d + "HOME/.bash_profile\" ]; then . \"" + d + "HOME/.bash_profile\"; fi\n" +
                "    echo \"Configuration reloaded successfully.\"\n" +
                "}\n" +
                "alias reload='reload'\n"

            if (profileText.contains("reload()")) {
                profileText = profileText.replace(Regex("reload\\s*\\(\\)\\s*\\{[\\s\\S]*?\\n\\}"), "")
                profileText = profileText.lines().filter { !it.startsWith("alias reload=") }.joinToString("\n").trimEnd()
                profileText += "\n\n" + reloadFn
                changed = true
            } else {
                profileText = profileText.trimEnd() + "\n\n" + reloadFn
                changed = true
            }

            if (!profileText.contains("_CORTEX_RC_GUARD")) {
                profileText += "\nif [ -f \"" + d + "HOME/.bashrc\" ] && [ -z \"" + d + "_CORTEX_RC_GUARD\" ]; then\n" +
                    "    _CORTEX_RC_GUARD=1\n" +
                    "    . \"" + d + "HOME/.bashrc\"\n" +
                    "    unset _CORTEX_RC_GUARD\n" +
                    "fi\n"
                changed = true
            }

            if (changed) {
                profile.writeText(profileText.trim() + "\n")
                profile.setReadable(true, true)
                profile.setWritable(true, true)
                try { android.system.Os.chmod(profile.absolutePath, 384) } catch (e: Exception) {} // 0600
            }

            // Clean up any unhidden bashrc, profile, or ubuntu directory in home
            File(home, "bashrc").delete()
            File(home, "profile").delete()
            File(home, "ubuntu").deleteRecursively()

            val localBin = File(home, ".local/bin")
            localBin.mkdirs()
            val localBinBashrc = File(localBin, "bashrc")
            localBinBashrc.writeText("#!/bin/bash\n. \"" + d + "HOME/.bashrc\"\n")
            localBinBashrc.setExecutable(true, true)
            localBinBashrc.setReadable(true, true)
            localBinBashrc.setWritable(true, true)
            try { android.system.Os.chmod(localBinBashrc.absolutePath, 448) } catch (e: Exception) {} // 0700

            val localBinProfile = File(localBin, "profile")
            localBinProfile.writeText("#!/bin/bash\n. \"" + d + "HOME/.profile\"\n")
            localBinProfile.setExecutable(true, true)
            localBinProfile.setReadable(true, true)
            localBinProfile.setWritable(true, true)
            try { android.system.Os.chmod(localBinProfile.absolutePath, 448) } catch (e: Exception) {} // 0700
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ensure .profile", e)
        }

        val etcProfile = File(root, "etc/profile")
        if (etcProfile.exists()) {
            try {
                val pText = etcProfile.readText()
                if (pText.contains("`id -u`") || pText.contains("$(id -u)")) {
                    etcProfile.writeText(pText.replace("`id -u`", "\${EUID:-0}").replace("$(id -u)", "\${EUID:-0}"))
                }
                etcProfile.setReadable(true, true)
                etcProfile.setWritable(true, true)
                try { android.system.Os.chmod(etcProfile.absolutePath, 384) } catch (e: Exception) {}
            } catch (e: Exception) {}
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

        val storageLink = File(home, "storage")
        if (!storageLink.exists()) {
            try {
                android.system.Os.symlink("/sdcard", storageLink.absolutePath)
            } catch (e: Exception) {}
        }

        File(root, "var/cache/apt/archives/partial").mkdirs()
        File(root, "var/lib/apt/lists/partial").mkdirs()
        File(root, "tmp").mkdirs()
        listOf("boot", "media", "mnt", "srv", "opt").forEach {
            val d = File(root, it)
            if (!d.exists()) d.mkdirs()
            d.setReadable(true, true)
            d.setWritable(true, true)
            d.setExecutable(true, true)
            try { android.system.Os.chmod(d.absolutePath, 448) } catch (e: Exception) {}
        }

        ensureEssentialBinaries(root, home, context)
    }

    fun hasDynamicLinker(root: File): Boolean {
        return ElfLinkerPatcher.hasDynamicLinker(root)
    }

    fun ensureDynamicLinkerSymlinks(root: File) {
        ElfLinkerPatcher.ensureDynamicLinkerSymlinks(root)
    }

    fun isBootstrapInstalled(context: Context): Boolean {
        val root = Environment.getCortexRoot(context)
        val apt = File(root, "usr/bin/apt")
        val bash = File(root, "usr/bin/bash")
        val versionFile = File(root, ".cortex_version")

        if (apt.exists() && bash.exists() && hasDynamicLinker(root) && versionFile.exists()) {
            val ver = versionFile.readText().trim().toIntOrNull() ?: 0
            if (ver < CURRENT_BOOTSTRAP_VERSION) {
                // Non-destructive update: refresh hook library and version marker
                try {
                    ensureHookLibrary(context, root)
                    restoreGpgv(root)
                    ensureKeyrings(root, context)
                    ensureAptSandbox(root)
                    ensureUbuntuSources(root)
                    cleanupAptArtifacts(root)
                    // Clear any corrupted or partial package lists from prior interrupted runs
                    try {
                        File(root, "var/lib/apt/lists").listFiles()?.forEach { f ->
                            if (f.isFile && f.name.endsWith("_Packages")) {
                                f.delete()
                            }
                        }
                        File(root, "var/lib/apt/lists/partial").listFiles()?.forEach { it.delete() }
                    } catch (e: Exception) {}
                    ensureMachineId(root)
                    ensureEssentialBinaries(root, Environment.getHomeDir(context), context)
                    ensureDynamicLinkerSymlinks(root)
                    versionFile.writeText(CURRENT_BOOTSTRAP_VERSION.toString())
                    versionFile.setReadable(true, true)
                    versionFile.setWritable(true, true)
                    try { android.system.Os.chmod(versionFile.absolutePath, 384) } catch (e: Exception) {}
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to perform non-destructive bootstrap update", e)
                }
            }
            return true
        }
        return false
    }

    fun findBootstrapAsset(context: Context): String? {
        val is64 = Process.is64Bit()
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
                    } catch (e: Exception) {}
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
                if (file.isDirectory) {
                    file.setReadable(true, true)
                    file.setWritable(true, true)
                    file.setExecutable(true, true)
                    try { android.system.Os.chmod(file.absolutePath, 448) } catch (e: Exception) {}
                } else if (file.isFile) {
                    val pName = file.parentFile?.name
                    if (pName in listOf("bin", "sbin") || file.name.startsWith("ld-linux") || file.name.startsWith("ld-2.")) {
                        file.setExecutable(true, true)
                        file.setReadable(true, true)
                        file.setWritable(true, true)
                        try { android.system.Os.chmod(file.absolutePath, 448) } catch (e: Exception) {}
                    } else {
                        file.setReadable(true, true)
                        file.setWritable(true, true)
                        try { android.system.Os.chmod(file.absolutePath, 384) } catch (e: Exception) {}
                    }
                }
            }

            ensureDynamicLinkerSymlinks(root)
            patchAllDynamicLinkers(root)
            fixAbsoluteSymlinks(root)

            // Ensure Glibc cortex-hook library is present and executable
            ensureHookLibrary(context, root)

            // Configure DNS
            updateDnsConfiguration(context, root)

            val etcDir = File(root, "etc")
            val etcProfile = File(etcDir, "profile")
            if (etcProfile.exists()) {
                val pText = etcProfile.readText()
                if (pText.contains("`id -u`") || pText.contains("$(id -u)")) {
                    etcProfile.writeText(pText.replace("`id -u`", "\${EUID:-0}").replace("$(id -u)", "\${EUID:-0}"))
                }
                etcProfile.setReadable(true, true)
                etcProfile.setWritable(true, true)
                try { android.system.Os.chmod(etcProfile.absolutePath, 384) } catch (e: Exception) {}
            }

            // Ensure /etc/passwd and /etc/group exist with root and cortex user definitions
            ensurePasswd(root, Environment.getHomeDir(context))
            ensureReloadScripts(root, Environment.getHomeDir(context), context)

            // Ensure user homes exist
            File(root, "root").mkdirs()
            val homeDir = File(root, "home")
            homeDir.mkdirs()
            File(homeDir, "ubuntu").deleteRecursively()
            listOf("boot", "media", "mnt", "srv", "opt").forEach { File(root, it).mkdirs() }

            // Configure APT sandbox so APT operates with owner permissions
            ensureAptSandbox(root)
            ensureUbuntuSources(root)

            // Ensure dpkg status file exists
            val dpkgDir = File(root, "var/lib/dpkg")
            dpkgDir.mkdirs()
            val statusFile = File(dpkgDir, "status")
            if (!statusFile.exists()) {
                statusFile.createNewFile()
            }
            statusFile.setReadable(true, true)
            statusFile.setWritable(true, true)
            try { android.system.Os.chmod(statusFile.absolutePath, 384) } catch (e: Exception) {}

            ensureAptSandbox(root)
            ensureDpkgTables(root)
            ensureLocale(root)
            ensureHosts(root)
            ensureNsswitch(root)
            ensureCaCertificates(root, context)
            ensureKeyrings(root, context)
            cleanupAptArtifacts(root)
            initializeFileSystem(context)

            File(root, "var/lib/apt/lists/partial").mkdirs()
            File(root, "var/cache/apt/archives/partial").mkdirs()

            // Mark bootstrap version only if dynamic linker is present and functional
            if (hasDynamicLinker(root)) {
                val versionFile = File(root, ".cortex_version")
                versionFile.writeText(CURRENT_BOOTSTRAP_VERSION.toString())
                versionFile.setReadable(true, true)
                versionFile.setWritable(true, true)
                try { android.system.Os.chmod(versionFile.absolutePath, 384) } catch (e: Exception) {}
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
                } catch (e: Exception) {}
            } else if (!cUtf8.exists() && cUTF8.exists()) {
                try {
                    android.system.Os.symlink("C.UTF-8", cUtf8.absolutePath)
                } catch (e: Exception) {}
            }

            val baseLocale = if (cUtf8.exists()) "C.utf8" else if (cUTF8.exists()) "C.UTF-8" else null
            if (baseLocale != null && !enUtf8.exists()) {
                try {
                    android.system.Os.symlink(baseLocale, enUtf8.absolutePath)
                } catch (e: Exception) {}
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
            targetHook.parentFile?.mkdirs()

            val targetPath = targetHook.toPath()
            // Clean up any broken/circular symlinks created by older buggy releases
            if (java.nio.file.Files.isSymbolicLink(targetPath)) {
                java.nio.file.Files.deleteIfExists(targetPath)
            }

            val tmpHook = File(root, "usr/lib/libcortex-hook.so.tmp")
            val tmpPath = tmpHook.toPath()
            if (java.nio.file.Files.isSymbolicLink(tmpPath)) {
                java.nio.file.Files.deleteIfExists(tmpPath)
            }

            context.assets.open(hookAssetName).use { inStream ->
                tmpHook.outputStream().use { outStream ->
                    inStream.copyTo(outStream)
                }
            }
            if (tmpHook.exists() && tmpHook.length() > 0) {
                tmpHook.setExecutable(true, true)
                tmpHook.setReadable(true, true)
                tmpHook.setWritable(true, true)
                try { android.system.Os.chmod(tmpHook.absolutePath, 448) } catch (e: Exception) {}

                try {
                    java.nio.file.Files.move(
                        tmpPath,
                        targetPath,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE
                    )
                } catch (e: Exception) {
                    java.nio.file.Files.move(
                        tmpPath,
                        targetPath,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING
                    )
                }
                targetHook.setExecutable(true, true)
                targetHook.setReadable(true, true)
                targetHook.setWritable(true, true)
                try { android.system.Os.chmod(targetHook.absolutePath, 448) } catch (e: Exception) {}
            }

            val libDir = File(root, "lib")
            if (libDir.exists() && !java.nio.file.Files.isSymbolicLink(libDir.toPath())) {
                val libHook = File(libDir, "libcortex-hook.so")
                val libHookPath = libHook.toPath()
                try {
                    if (java.nio.file.Files.isSymbolicLink(libHookPath)) {
                        java.nio.file.Files.deleteIfExists(libHookPath)
                    }
                    if (libHook.exists()) {
                        libHook.delete()
                    }
                    android.system.Os.symlink(targetHook.absolutePath, libHook.absolutePath)
                } catch (e: Exception) {}
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
                        name.startsWith(".ctx_sock_") || name.startsWith("opencode") ||
                        name.startsWith("bun-") || name.startsWith("node-")) {
                        try {
                            file.deleteRecursively()
                        } catch (e: Exception) {}
                    }
                }
            } else {
                tmpDir.mkdirs()
            }
            try {
                android.system.Os.chmod(tmpDir.absolutePath, 448) // 0700
            } catch (e: Exception) {}

            val opencodeDataDir = File(home, ".local/share/opencode")
            if (opencodeDataDir.exists() && opencodeDataDir.isDirectory) {
                opencodeDataDir.listFiles()?.forEach { file ->
                    val name = file.name
                    if (name.endsWith(".sock") || name.endsWith(".socket") ||
                        name.endsWith(".lock") || name.endsWith(".pid")) {
                        try {
                            file.deleteRecursively()
                        } catch (e: Exception) {}
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
                        } catch (e: Exception) {}
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
                try { android.system.Os.chmod(hostsFile.absolutePath, 384) } catch (e: Exception) {}
            } else {
                val currentText = hostsFile.readText()
                if (currentText.contains("ports.ubuntu.com") || currentText.contains("api.meta.ai")) {
                    hostsFile.writeText(defaultHosts)
                    hostsFile.setReadable(true, true)
                    hostsFile.setWritable(true, true)
                    try { android.system.Os.chmod(hostsFile.absolutePath, 384) } catch (e: Exception) {}
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
                try { android.system.Os.chmod(nssFile.absolutePath, 384) } catch (e: Exception) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ensure nsswitch.conf", e)
        }
    }

    fun updateDnsConfiguration(context: Context, root: File) {
        try {
            val dnsServers = mutableListOf<String>()
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            val activeNet = cm?.activeNetwork
            if (activeNet != null) {
                val lp = cm.getLinkProperties(activeNet)
                lp?.dnsServers?.forEach { addr ->
                    if (addr is java.net.Inet4Address) {
                        val host = addr.hostAddress
                        if (!host.isNullOrBlank() && !host.startsWith("127.") && !host.contains("%")) {
                            dnsServers.add(host)
                        }
                    }
                }
            }
            if (!dnsServers.contains("8.8.8.8")) dnsServers.add("8.8.8.8")
            if (!dnsServers.contains("1.1.1.1")) dnsServers.add("1.1.1.1")

            val selectedDns = dnsServers.distinct().take(3)

            val etcDir = File(root, "etc")
            etcDir.mkdirs()
            val resolvFile = File(etcDir, "resolv.conf")
            try {
                java.nio.file.Files.deleteIfExists(resolvFile.toPath())
            } catch (_: Exception) {
                resolvFile.delete()
            }
            val resolvConf = selectedDns.joinToString("\n") { "nameserver $it" } + "\noptions timeout:1 attempts:2 rotate\n"
            resolvFile.writeText(resolvConf)
            resolvFile.setReadable(true, true)
            resolvFile.setWritable(true, true)
            try { android.system.Os.chmod(resolvFile.absolutePath, 384) } catch (_: Exception) {}

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
                    context.assets.open("cacert.pem").use { input ->
                        java.io.FileOutputStream(caBundle).use { output ->
                            input.copyTo(output)
                        }
                    }
                    caBundle.setReadable(true, true)
                    caBundle.setWritable(true, true)
                    try { android.system.Os.chmod(caBundle.absolutePath, 384) } catch (e: Exception) {}
                    Log.i(TAG, "Copied bundled cacert.pem from assets (${caBundle.length()} bytes)")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to extract bundled cacert.pem", e)
                }
            }

            val existingText = if (caBundle.exists() && caBundle.length() > 0) {
                try { caBundle.readText() } catch (e: Exception) { "" }
            } else ""

            val sb = StringBuilder(existingText)
            var appended = false

            val certDirs = listOf(
                File("/system/etc/security/cacerts"),
                File("/apex/com.android.conscrypt/cacerts")
            )
            for (androidCertsDir in certDirs) {
                if (androidCertsDir.exists() && androidCertsDir.isDirectory) {
                    androidCertsDir.listFiles()?.forEach { f ->
                        if (f.isFile && f.name.endsWith(".0")) {
                            try {
                                val targetFile = File(certsDir, f.name)
                                if (!targetFile.exists() || targetFile.length() == 0L) {
                                    f.copyTo(targetFile, overwrite = true)
                                    targetFile.setReadable(true, true)
                                    targetFile.setWritable(true, true)
                                    try { android.system.Os.chmod(targetFile.absolutePath, 384) } catch (e: Exception) {}
                                }
                            } catch (e: Exception) {}
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
                            } catch (e: Exception) {}
                        }
                    }
                }
            }

            if (!caBundle.exists() || caBundle.length() < 1000L || appended) {
                if (sb.isNotEmpty()) {
                    caBundle.writeText(sb.toString())
                }
            }

            caBundle.setReadable(true, true)
            caBundle.setWritable(true, true)
            try { android.system.Os.chmod(caBundle.absolutePath, 384) } catch (e: Exception) {}

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
                            aliasFile.writeBytes(caBundle.readBytes())
                            aliasFile.setReadable(true, true)
                            aliasFile.setWritable(true, true)
                            try { android.system.Os.chmod(aliasFile.absolutePath, 384) } catch (e: Exception) {}
                        }
                    }
                } catch (e: Exception) {}
            }
            Log.i(TAG, "ensureCaCertificates finished (${caBundle.length()} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ensure CA certificates", e)
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
            passwdFile.writeText(passwdText)
            passwdFile.setReadable(true, true)
            passwdFile.setWritable(true, true)
            try { android.system.Os.chmod(passwdFile.absolutePath, 384) } catch (e: Exception) {}

            val groupFile = File(etcDir, "group")
            var groupText = if (groupFile.exists()) groupFile.readText() else ""
            if (!groupText.contains("root:x:0:")) {
                groupText = "root:x:0:\n" + groupText
            }
            if (!groupText.contains("cortex:")) {
                groupText += "cortex:x:0:\n"
            }
            groupFile.writeText(groupText)
            groupFile.setReadable(true, true)
            groupFile.setWritable(true, true)
            try { android.system.Os.chmod(groupFile.absolutePath, 384) } catch (e: Exception) {}
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
            val isBinDirSymlink = binDir.exists() && java.nio.file.Files.isSymbolicLink(binDir.toPath())

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
                        try { android.system.Os.chmod(tmp.absolutePath, 448) } catch (e: Exception) {}
                        try {
                            java.nio.file.Files.move(
                                tmp.toPath(),
                                target.toPath(),
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                                java.nio.file.StandardCopyOption.ATOMIC_MOVE
                            )
                        } catch (e: Exception) {
                            java.nio.file.Files.move(
                                tmp.toPath(),
                                target.toPath(),
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING
                            )
                        }
                        target.setReadable(true, true)
                        target.setWritable(true, true)
                        target.setExecutable(true, true)
                        try { android.system.Os.chmod(target.absolutePath, 448) } catch (e: Exception) {}
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
                    try { android.system.Os.chmod(tmp.absolutePath, 448) } catch (e: Exception) {}
                    try {
                        java.nio.file.Files.move(
                            tmp.toPath(),
                            target.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE
                        )
                    } catch (e: Exception) {
                        java.nio.file.Files.move(
                            tmp.toPath(),
                            target.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING
                        )
                    }
                    target.setReadable(true, true)
                    target.setWritable(true, true)
                    target.setExecutable(true, true)
                    try { android.system.Os.chmod(target.absolutePath, 448) } catch (e: Exception) {}
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to setup which at ${target.absolutePath}", e)
                }
            }
            ensureServiceManager(root, context)
            ensureBrowserOpener(root, context)
            ensureRootTools(root, context)
            restoreGpgv(root)
            ensureMachineId(root)
            ensureShm(root)
            ensureSystemdSharedLibs(root)
            ensureMountpoint(root)
            ensureJavaCaDirs(root)
            cleanupAutoLaunchers(root)
            ensureProfileEnvironment(root)
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
                try { android.system.Os.chmod(gpgv.absolutePath, 448) } catch (e: Exception) {}
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
                try { android.system.Os.chmod(machineId.absolutePath, 384) } catch (e: Exception) {}
            }
            val dbusDir = File(root, "var/lib/dbus")
            dbusDir.mkdirs()
            val dbusMachineId = File(dbusDir, "machine-id")
            if (!dbusMachineId.exists()) {
                try {
                    android.system.Os.symlink("/etc/machine-id", dbusMachineId.absolutePath)
                } catch (e: Exception) {
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
                        val tb = File(bDir, tool)
                        tb.parentFile?.mkdirs()
                        tb.writeText(scriptContent)
                        tb.setReadable(true, true)
                        tb.setWritable(true, true)
                        tb.setExecutable(true, true)
                        try { android.system.Os.chmod(tb.absolutePath, 448) } catch (_: Exception) {}
                    } catch (_: Exception) {}
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
                try { android.system.Os.chmod(diversionsFile.absolutePath, 384) } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ensure machine-id", e)
        }
    }

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
                if (cand.exists()) {
                    val text = try { cand.readText() } catch (_: Exception) { "" }
                    if (text.contains("OpenCode CLI is not yet installed") ||
                        text.contains("Meta Muse Code CLI is not yet installed") ||
                        text.contains("opencode.ai") ||
                        text.contains("dev.meta.ai")) {
                        cand.delete()
                        Log.i(TAG, "Removed auto-installer wrapper: ${cand.absolutePath}")
                    }
                }
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
            try { android.system.Os.chmod(tmpShm.absolutePath, 448) } catch (_: Exception) {}

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
                    try { android.system.Os.chmod(devShm.absolutePath, 448) } catch (_: Exception) {}
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
                                    try { libFile.copyTo(destUsr, overwrite = false) } catch (_: Exception) {}
                                }
                            }
                            val destLib = File(libDir, libFile.name)
                            if (!destLib.exists()) {
                                try {
                                    android.system.Os.symlink(libFile.absolutePath, destLib.absolutePath)
                                } catch (_: Exception) {
                                    try { libFile.copyTo(destLib, overwrite = false) } catch (_: Exception) {}
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
                } catch (_: Exception) {}
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
                        try { android.system.Os.chmod(t.absolutePath, 448) } catch (_: Exception) {}
                    } catch (_: Exception) {}
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

    fun ensureProfileEnvironment(root: File) {
        try {
            val profileD = File(root, "etc/profile.d")
            profileD.mkdirs()
            val envSh = File(profileD, "00-env.sh")
            val envContent = "if [ -z \"\$CORTEX_ROOT\" ]; then\n" +
                "    if [ -d \"\$HOME/../etc\" ]; then\n" +
                "        export CORTEX_ROOT=\"\$(cd \"\$HOME/..\" && pwd)\"\n" +
                "    else\n" +
                "        export CORTEX_ROOT=\"" + root.absolutePath + "\"\n" +
                "    fi\n" +
                "fi\n" +
                "export LD_LIBRARY_PATH=\"\$CORTEX_ROOT/lib:\$CORTEX_ROOT/usr/lib:\$CORTEX_ROOT/lib/aarch64-linux-gnu:\$CORTEX_ROOT/usr/lib/aarch64-linux-gnu:\$CORTEX_ROOT/lib/arm-linux-gnueabihf:\$CORTEX_ROOT/usr/lib/arm-linux-gnueabihf:\$CORTEX_ROOT/usr/local/lib:\$CORTEX_ROOT/usr/lib/systemd:\$CORTEX_ROOT/lib/systemd:\$CORTEX_ROOT/usr/lib/aarch64-linux-gnu/systemd:\$CORTEX_ROOT/lib/aarch64-linux-gnu/systemd:\$CORTEX_ROOT/usr/lib/arm-linux-gnueabihf/systemd:\$CORTEX_ROOT/lib/arm-linux-gnueabihf/systemd\"\n" +
                "export LD_PRELOAD=\"\$CORTEX_ROOT/usr/lib/libcortex-hook.so\"\n" +
                "export PATH=\"/home/.local/bin:\$HOME/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:\$PATH\"\n"
            envSh.writeText(envContent)
            envSh.setReadable(true, true)
            envSh.setWritable(true, true)
            try { android.system.Os.chmod(envSh.absolutePath, 384) } catch (e: Exception) {}
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ensure profile environment", e)
        }
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

            if (zoneinfoFile.exists() && zoneinfoFile.isFile) {
                try {
                    if (localTimeFile.exists()) {
                        localTimeFile.delete()
                    }
                    zoneinfoFile.copyTo(localTimeFile, overwrite = true)
                    localTimeFile.setReadable(true, true)
                    localTimeFile.setWritable(true, true)
                    try { android.system.Os.chmod(localTimeFile.absolutePath, 384) } catch (e: Exception) {}
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to copy zoneinfo to localtime", e)
                }
            } else {
                val tzifBytes = TzifGenerator.createTzifBytes(offsetSeconds, abbr, posixTz)
                try {
                    if (localTimeFile.exists()) {
                        localTimeFile.delete()
                    }
                    localTimeFile.writeBytes(tzifBytes)
                    localTimeFile.setReadable(true, true)
                    localTimeFile.setWritable(true, true)
                    try { android.system.Os.chmod(localTimeFile.absolutePath, 384) } catch (e: Exception) {}
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to write generated localtime", e)
                }
            }

            val effectiveTz = if (zoneinfoFile.exists() && zoneinfoFile.isFile) tzId else posixTz
            tzFile.writeText(effectiveTz + "\n")
            tzFile.setReadable(true, true)
            tzFile.setWritable(true, true)
            try { android.system.Os.chmod(tzFile.absolutePath, 384) } catch (e: Exception) {}
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
            val stream = pty.inputStream
            val buffer = ByteArray(2048)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                val str = String(buffer, 0, minOf(read, 256), Charsets.UTF_8)
                onProgress?.invoke(str)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Background command stream error", e)
        }
        return pty.waitFor()
    }
}
