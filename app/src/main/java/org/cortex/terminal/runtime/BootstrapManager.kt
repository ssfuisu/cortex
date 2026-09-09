package org.cortex.terminal.runtime

import android.content.Context
import org.cortex.terminal.pty.PtyNative
import org.cortex.terminal.pty.PtyProcess
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
        ensureRootTools(root)
        ensureBrowserOpener(root)

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
                bashrc.setReadable(true, false)
                bashrc.setWritable(true, false)
            }
        } catch (e: Exception) {
            android.util.Log.e("BootstrapManager", "Failed to ensure .bashrc", e)
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
                profile.setReadable(true, false)
                profile.setWritable(true, false)
            }

            // Clean up any unhidden bashrc, profile, or ubuntu directory in home
            File(home, "bashrc").delete()
            File(home, "profile").delete()
            File(home, "ubuntu").deleteRecursively()

            val localBin = File(home, ".local/bin")
            localBin.mkdirs()
            val localBinBashrc = File(localBin, "bashrc")
            localBinBashrc.writeText("#!/bin/bash\n. \"" + d + "HOME/.bashrc\"\n")
            localBinBashrc.setExecutable(true, false)
            localBinBashrc.setReadable(true, false)
            try { android.system.Os.chmod(localBinBashrc.absolutePath, 493) } catch (e: Exception) {}

            val localBinProfile = File(localBin, "profile")
            localBinProfile.writeText("#!/bin/bash\n. \"" + d + "HOME/.profile\"\n")
            localBinProfile.setExecutable(true, false)
            localBinProfile.setReadable(true, false)
            try { android.system.Os.chmod(localBinProfile.absolutePath, 493) } catch (e: Exception) {}
        } catch (e: Exception) {
            android.util.Log.e("BootstrapManager", "Failed to ensure .profile", e)
        }

        val etcProfile = File(root, "etc/profile")
        if (etcProfile.exists()) {
            try {
                val pText = etcProfile.readText()
                if (pText.contains("`id -u`") || pText.contains("$(id -u)")) {
                    etcProfile.writeText(pText.replace("`id -u`", "\${EUID:-0}").replace("$(id -u)", "\${EUID:-0}"))
                }
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
        ensurePasswd(root, home)
        ensureReloadScripts(root, home)
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
        }

        // File system structure initialized
        patchAllDynamicLinkers(root)
        fixAbsoluteSymlinks(root)
        ensureEssentialBinaries(root, home)
    }

    private const val CURRENT_BOOTSTRAP_VERSION = 12414

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
            }

            // Ensure /etc/passwd and /etc/group exist with root and cortex user definitions
            ensurePasswd(root, Environment.getHomeDir(context))
            ensureReloadScripts(root, Environment.getHomeDir(context))

            // Ensure user homes exist
            File(root, "root").mkdirs()
            val homeDir = File(root, "home")
            homeDir.mkdirs()
            File(homeDir, "ubuntu").deleteRecursively()
            listOf("boot", "media", "mnt", "srv", "opt").forEach { File(root, it).mkdirs() }

            // Configure APT sandbox so APT operates without superuser privilege drop
            ensureAptSandbox(root)

            val debianSources = File(root, "etc/apt/sources.list.d/debian.sources")
            val ubuntuSources = File(root, "etc/apt/sources.list.d/ubuntu.sources")
            val sourcesList = File(root, "etc/apt/sources.list")
            if (ubuntuSources.exists() || debianSources.exists()) {
                if (sourcesList.exists()) sourcesList.delete()
            } else if (!sourcesList.exists() || sourcesList.length() == 0L) {
                sourcesList.writeText(
                    "deb http://ports.ubuntu.com/ubuntu-ports noble main restricted universe multiverse\n" +
                    "deb http://ports.ubuntu.com/ubuntu-ports noble-updates main restricted universe multiverse\n" +
                    "deb http://ports.ubuntu.com/ubuntu-ports noble-security main restricted universe multiverse\n"
                )
            }

            // Ensure dpkg status file exists
            val dpkgDir = File(root, "var/lib/dpkg")
            dpkgDir.mkdirs()
            val statusFile = File(dpkgDir, "status")
            if (!statusFile.exists()) {
                statusFile.createNewFile()
            }

            ensureAptSandbox(root)
            ensureDpkgTables(root)
            ensureLocale(root)
            ensureHosts(root)
            ensureNsswitch(root)
            ensureCaCertificates(root, context)
            cleanupAptArtifacts(root)
            initializeFileSystem(context)

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

    fun patchAllDynamicLinkers(root: File) {
        if (!root.exists() || !root.isDirectory) return
        try {
            root.walkTopDown().forEach { file ->
                val name = file.name
                if (file.isFile && (name.startsWith("ld-linux") || name.startsWith("libc.so") || name.startsWith("libc-"))) {
                    val target = if (java.nio.file.Files.isSymbolicLink(file.toPath())) {
                        try { file.canonicalFile } catch (e: Exception) { file }
                    } else {
                        file
                    }
                    if (target.exists() && target.isFile) {
                        patchDynamicLinker(target)
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("BootstrapManager", "Error walking root to patch dynamic linkers and libc", e)
        }
    }

    fun fixAbsoluteSymlinks(root: File) {
        if (!root.exists() || !root.isDirectory) return
        try {
            val candidateDirs = listOf(
                File(root, "etc/alternatives"),
                File(root, "usr/bin"),
                File(root, "usr/sbin"),
                File(root, "bin"),
                File(root, "sbin")
            )
            for (dir in candidateDirs) {
                if (!dir.exists() || !dir.isDirectory) continue
                dir.listFiles()?.forEach { file ->
                    try {
                        val path = file.toPath()
                        if (java.nio.file.Files.isSymbolicLink(path)) {
                            val target = java.nio.file.Files.readSymbolicLink(path).toString()
                            if (target.startsWith("/")) {
                                val targetClean = target.trimStart('/')
                                val targetInRoot = File(root, targetClean)
                                val relTarget = file.parentFile?.toPath()?.relativize(targetInRoot.toPath())?.toString()
                                if (relTarget != null) {
                                    java.nio.file.Files.delete(path)
                                    java.nio.file.Files.createSymbolicLink(path, java.nio.file.Paths.get(relTarget))
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // ignore individual link error
                    }
                }
            }

            ensureEssentialBinaries(root, File(root, "home"))
        } catch (e: Exception) {
            android.util.Log.e("BootstrapManager", "Error in fixAbsoluteSymlinks", e)
        }
    }

    fun patchDynamicLinker(file: File) {
        if (!file.exists() || !file.isFile) {
            return
        }
        try {
            val bytes = file.readBytes()
            var modified = false

            // AArch64 syscall patterns
            val svcAarch64 = byteArrayOf(0x01.toByte(), 0x00.toByte(), 0x00.toByte(), 0xd4.toByte())
            val nopAarch64 = byteArrayOf(0x1f.toByte(), 0x20.toByte(), 0x03.toByte(), 0xd5.toByte())
            val movEnosysAarch64 = byteArrayOf(0xa0.toByte(), 0x04.toByte(), 0x80.toByte(), 0x92.toByte()) // mov x0, #-38

            // mov x8, #0x63 (syscall 99 set_robust_list)
            val movX8Syscall99 = byteArrayOf(0x68.toByte(), 0x0c.toByte(), 0x80.toByte(), 0xd2.toByte())
            // mov x8, #0x1b3 (syscall 435 clone3)
            val movX8Syscall435 = byteArrayOf(0x68.toByte(), 0x36.toByte(), 0x80.toByte(), 0xd2.toByte())
            // mov x8, #0x125 (syscall 293 rseq)
            val movX8Syscall293 = byteArrayOf(0xa8.toByte(), 0x24.toByte(), 0x80.toByte(), 0xd2.toByte())

            var pos = 0
            while (pos <= bytes.size - 4) {
                val isSyscall99 = (bytes[pos] == movX8Syscall99[0] && bytes[pos + 1] == movX8Syscall99[1] &&
                                   bytes[pos + 2] == movX8Syscall99[2] && bytes[pos + 3] == movX8Syscall99[3])
                val isSyscall435 = (bytes[pos] == movX8Syscall435[0] && bytes[pos + 1] == movX8Syscall435[1] &&
                                    bytes[pos + 2] == movX8Syscall435[2] && bytes[pos + 3] == movX8Syscall435[3])
                val isSyscall293 = (bytes[pos] == movX8Syscall293[0] && bytes[pos + 1] == movX8Syscall293[1] &&
                                    bytes[pos + 2] == movX8Syscall293[2] && bytes[pos + 3] == movX8Syscall293[3])

                if (isSyscall99 || isSyscall435 || isSyscall293) {
                    val replacement = if (isSyscall99) nopAarch64 else movEnosysAarch64
                    val scName = if (isSyscall99) "99 set_robust_list" else if (isSyscall435) "435 clone3" else "293 rseq"
                    val searchEnd = minOf(bytes.size - 4, pos + 64)
                    for (i in (pos + 4)..searchEnd step 4) {
                        if (bytes[i] == svcAarch64[0] &&
                            bytes[i + 1] == svcAarch64[1] &&
                            bytes[i + 2] == svcAarch64[2] &&
                            bytes[i + 3] == svcAarch64[3]) {

                            replacement.copyInto(bytes, destinationOffset = i)
                            modified = true
                            android.util.Log.i("BootstrapManager", "Patched syscall $scName svc #0 at 0x${Integer.toHexString(i)} in ${file.name}")
                            break
                        }
                    }
                }
                pos += 4
            }

            if (modified) {
                file.setWritable(true, true)
                file.writeBytes(bytes)
                file.setExecutable(true, false)
                file.setReadable(true, false)
                android.util.Log.i("BootstrapManager", "Successfully wrote patched linker: ${file.absolutePath}")
            }
        } catch (e: Exception) {
            android.util.Log.e("BootstrapManager", "Failed to patch dynamic linker: ${file.absolutePath}", e)
        }
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
            }
            val tripletTable = File(dpkgShare, "triplettable")
            if (!tripletTable.exists() || tripletTable.length() == 0L) {
                tripletTable.writeText(tupleContent)
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
            }

            val abitable = File(dpkgShare, "abitable")
            if (!abitable.exists() || abitable.length() == 0L) {
                abitable.writeText(
                    "# Version=2.0\n" +
                    "abin32\t32\n" +
                    "x32\t32\n"
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("BootstrapManager", "Failed to ensure dpkg tables", e)
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
                } catch (e: Exception) {
                    // Ignore symlink failure
                }
            } else if (!cUtf8.exists() && cUTF8.exists()) {
                try {
                    android.system.Os.symlink("C.UTF-8", cUtf8.absolutePath)
                } catch (e: Exception) {
                    // Ignore symlink failure
                }
            }

            val baseLocale = if (cUtf8.exists()) "C.utf8" else if (cUTF8.exists()) "C.UTF-8" else null
            if (baseLocale != null && !enUtf8.exists()) {
                try {
                    android.system.Os.symlink(baseLocale, enUtf8.absolutePath)
                } catch (e: Exception) {
                    // Ignore symlink failure
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("BootstrapManager", "Failed to ensure locale", e)
        }
    }

    private fun ensureAptSandbox(root: File) {
        try {
            val aptConfDir = File(root, "etc/apt/apt.conf.d")
            aptConfDir.mkdirs()
            val sbFile = File(aptConfDir, "01sandbox")
            sbFile.writeText(
                "APT::Sandbox::User \"root\";\n" +
                "APT::Sandbox::Seccomp \"false\";\n" +
                "Acquire::ForceIPv4 \"true\";\n" +
                "Acquire::Connect::AddrConfig \"false\";\n" +
                "Acquire::SRV \"false\";\n" +
                "Acquire::Languages \"none\";\n" +
                "Acquire::GzipIndexes \"true\";\n" +
                "Dir::dpkg::cputable \"/usr/share/dpkg/cputable\";\n" +
                "Dir::dpkg::tupletable \"/usr/share/dpkg/tupletable\";\n" +
                "Dir::dpkg::triplettable \"/usr/share/dpkg/triplettable\";\n" +
                "DPkg::Install::Recursive \"false\";\n" +
                "Dpkg::Progress-Fancy \"false\";\n" +
                "APT::Color \"false\";\n" +
                "DPkg::Options {\n" +
                "   \"--force-confdef\";\n" +
                "   \"--force-confold\";\n" +
                "   \"--force-unsafe-io\";\n" +
                "};\n"
            )
            val dockerClean = File(aptConfDir, "docker-clean")
            dockerClean.writeText("# Disabled for Cortex\n")

            val dpkgCfgDir = File(root, "etc/dpkg/dpkg.cfg.d")
            dpkgCfgDir.mkdirs()
            File(dpkgCfgDir, "01cortex").writeText(
                "force-confdef\n" +
                "force-confold\n" +
                "force-unsafe-io\n" +
                "no-debsig\n"
            )

            val profileD = File(root, "etc/profile.d")
            profileD.mkdirs()
            File(profileD, "01cortex.sh").writeText(
                "export DPKG_DEB_THREADS_MAX=1\n" +
                "export XZ_OPT=-T1\n" +
                "export XZ_DEFAULTS=-T1\n" +
                "export DEBIAN_FRONTEND=noninteractive\n" +
                "export DEBCONF_FRONTEND=noninteractive\n" +
                "export DEBCONF_NONINTERACTIVE_SEEN=true\n" +
                "if [ -n \"\$CORTEX_ROOT\" ]; then\n" +
                "    export SSL_CERT_FILE=\"\$CORTEX_ROOT/etc/ssl/certs/ca-certificates.crt\"\n" +
                "    export SSL_CERT_DIR=\"\$CORTEX_ROOT/etc/ssl/certs:/system/etc/security/cacerts\"\n" +
                "    export CURL_CA_BUNDLE=\"\$CORTEX_ROOT/etc/ssl/certs/ca-certificates.crt\"\n" +
                "    export NODE_EXTRA_CA_CERTS=\"\$CORTEX_ROOT/etc/ssl/certs/ca-certificates.crt\"\n" +
                "    export REQUESTS_CA_BUNDLE=\"\$CORTEX_ROOT/etc/ssl/certs/ca-certificates.crt\"\n" +
                "else\n" +
                "    export SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt\n" +
                "    export CURL_CA_BUNDLE=/etc/ssl/certs/ca-certificates.crt\n" +
                "fi\n" +
                "export TZDIR=/usr/share/zoneinfo\n"
            )

            val usrSbinDir = File(root, "usr/sbin")
            usrSbinDir.mkdirs()

            val policyScript = "#!/bin/sh\nexit 101\n"
            val dummyExitZero = "#!/bin/sh\nexit 0\n"

            val policyFile = File(usrSbinDir, "policy-rc.d")
            policyFile.writeText(policyScript)
            policyFile.setReadable(true, false)
            policyFile.setExecutable(true, false)
            try {
                android.system.Os.chmod(policyFile.absolutePath, 493)
            } catch (e: Exception) {}

            listOf("ldconfig", "start-stop-daemon").forEach { name ->
                val f = File(usrSbinDir, name)
                f.writeText(dummyExitZero)
                f.setReadable(true, false)
                f.setExecutable(true, false)
                try {
                    android.system.Os.chmod(f.absolutePath, 493)
                } catch (e: Exception) {}
            }

            val sbinDir = File(root, "sbin")
            if (sbinDir.exists() && !java.nio.file.Files.isSymbolicLink(sbinDir.toPath())) {
                listOf("policy-rc.d", "ldconfig", "start-stop-daemon").forEach { name ->
                    try {
                        val src = File(usrSbinDir, name)
                        val dst = File(sbinDir, name)
                        src.copyTo(dst, overwrite = true)
                        dst.setReadable(true, false)
                        dst.setExecutable(true, false)
                        android.system.Os.chmod(dst.absolutePath, 493)
                    } catch (e: Exception) {}
                }
            }

            File(root, "var/cache/apt/archives/partial").mkdirs()
            File(root, "var/lib/apt/lists/partial").mkdirs()
            File(root, "tmp").mkdirs()
            ensureCaCertificates(root)
        } catch (e: Exception) {
            android.util.Log.e("BootstrapManager", "Failed to ensure apt sandbox config", e)
        }
    }

    private fun ensureHookLibrary(context: Context, root: File) {
        try {
            val hookAssetName = if (CortexRuntime.is64Bit) "libcortex-hook-arm64.so" else "libcortex-hook-arm.so"
            val targetHook = File(root, "usr/lib/libcortex-hook.so")
            targetHook.parentFile?.mkdirs()
            context.assets.open(hookAssetName).use { inStream ->
                targetHook.outputStream().use { outStream ->
                    inStream.copyTo(outStream)
                }
            }
            if (targetHook.exists()) {
                targetHook.setExecutable(true, false)
                targetHook.setReadable(true, false)
            }
        } catch (e: Exception) {
            android.util.Log.e("BootstrapManager", "Failed to copy hook library from assets", e)
        }
    }

    fun ensureHosts(root: File) {
        try {
            val etcDir = File(root, "etc")
            etcDir.mkdirs()
            val hostsFile = File(etcDir, "hosts")
            val defaultHosts = "127.0.0.1 localhost localhost.localdomain\n" +
                "::1 localhost ip6-localhost ip6-loopback\n" +
                "91.189.91.103 ports.ubuntu.com\n" +
                "91.189.92.21 ports.ubuntu.com\n" +
                "91.189.91.102 ports.ubuntu.com\n" +
                "91.189.92.20 ports.ubuntu.com\n" +
                "185.125.190.81 archive.ubuntu.com\n" +
                "91.189.91.81 archive.ubuntu.com\n" +
                "185.125.190.82 security.ubuntu.com\n" +
                "91.189.91.82 security.ubuntu.com\n" +
                "151.101.130.132 deb.debian.org\n" +
                "151.101.2.132 deb.debian.org\n" +
                "151.101.66.132 deb.debian.org\n" +
                "151.101.194.132 deb.debian.org\n" +
                "151.101.130.132 security.debian.org\n" +
                "151.101.2.132 security.debian.org\n" +
                "151.101.66.132 security.debian.org\n" +
                "151.101.194.132 security.debian.org\n" +
                "151.101.130.132 cdn-fastly.deb.debian.org\n" +
                "151.101.2.132 cdn-fastly.deb.debian.org\n" +
                "142.251.127.95 oauth2.googleapis.com\n" +
                "142.251.127.84 accounts.google.com\n" +
                "142.250.74.202 www.googleapis.com\n"

            if (!hostsFile.exists()) {
                hostsFile.writeText(defaultHosts)
            } else {
                val currentText = hostsFile.readText()
                if (!currentText.contains("oauth2.googleapis.com")) {
                    hostsFile.writeText(currentText.trimEnd() + "\n" + defaultHosts)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("BootstrapManager", "Failed to ensure hosts", e)
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
            }
        } catch (e: Exception) {
            android.util.Log.e("BootstrapManager", "Failed to ensure nsswitch.conf", e)
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
            val resolvConf = selectedDns.joinToString("\n") { "nameserver $it" } + "\noptions timeout:1 attempts:2 rotate\n"
            File(etcDir, "resolv.conf").writeText(resolvConf)

            ensureHosts(root)
            ensureNsswitch(root)
        } catch (e: Exception) {
            android.util.Log.e("BootstrapManager", "Failed to update resolv.conf", e)
        }
    }

    private fun cleanupAptArtifacts(root: File) {
        try {
            val debianSources = File(root, "etc/apt/sources.list.d/debian.sources")
            val ubuntuSources = File(root, "etc/apt/sources.list.d/ubuntu.sources")
            val sourcesList = File(root, "etc/apt/sources.list")
            if ((debianSources.exists() || ubuntuSources.exists()) && sourcesList.exists()) {
                sourcesList.delete()
            }

            val dockerClean = File(root, "etc/apt/apt.conf.d/docker-clean")
            if (dockerClean.exists()) {
                dockerClean.delete()
            }
        } catch (e: Exception) {
            android.util.Log.e("BootstrapManager", "Failed to cleanup apt artifacts", e)
        }
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
                    caBundle.setReadable(true, false)
                    android.util.Log.i("BootstrapManager", "Copied bundled cacert.pem from assets (${caBundle.length()} bytes)")
                } catch (e: Exception) {
                    android.util.Log.e("BootstrapManager", "Failed to extract bundled cacert.pem", e)
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
                                    targetFile.setReadable(true, false)
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

            caBundle.setReadable(true, false)
            try {
                android.system.Os.chmod(caBundle.absolutePath, 420)
            } catch (e: Exception) {}

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
                        }
                    }
                } catch (e: Exception) {}
            }
            android.util.Log.i("BootstrapManager", "ensureCaCertificates finished (${caBundle.length()} bytes)")
        } catch (e: Exception) {
            android.util.Log.e("BootstrapManager", "Failed to ensure CA certificates", e)
        }
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

            val groupFile = File(etcDir, "group")
            var groupText = if (groupFile.exists()) groupFile.readText() else ""
            if (!groupText.contains("root:x:0:")) {
                groupText = "root:x:0:\n" + groupText
            }
            if (!groupText.contains("cortex:")) {
                groupText += "cortex:x:0:\n"
            }
            groupFile.writeText(groupText)
        } catch (e: Exception) {
            android.util.Log.e("BootstrapManager", "Failed to ensure passwd/group", e)
        }
    }

    private fun ensureReloadScripts(root: File, home: File) {
        try {
            val reloadScript = "#!/bin/bash\n" +
                "set --\n" +
                "if [ -f \"\$HOME/.bashrc\" ]; then . \"\$HOME/.bashrc\"; fi\n" +
                "if [ -f \"\$HOME/.profile\" ]; then . \"\$HOME/.profile\"; fi\n" +
                "if [ -f \"\$HOME/.bash_profile\" ]; then . \"\$HOME/.bash_profile\"; fi\n" +
                "echo \"Environment reloaded.\"\n"
            val reloadDirs = listOf(File(root, "usr/bin"), File(root, "bin"), File(home, ".local/bin"))
            reloadDirs.forEach { dir ->
                if (dir.exists()) {
                    val rFile = File(dir, "reload")
                    rFile.writeText(reloadScript)
                    rFile.setExecutable(true, false)
                    rFile.setReadable(true, false)
                    try { android.system.Os.chmod(rFile.absolutePath, 493) } catch (e: Exception) {}
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("BootstrapManager", "Failed to create reload scripts", e)
        }
    }

    fun ensureEssentialBinaries(root: File, home: File) {
        try {
            val localBin = File(home, ".local/bin")
            localBin.mkdirs()

            // 1. awk guarantee: find mawk or gawk and copy as real ELF executable
            val mawkCandidates = listOf(
                File(root, "usr/bin/mawk"),
                File(root, "bin/mawk"),
                File(root, "usr/bin/gawk"),
                File(root, "bin/gawk")
            )
            val realAwk = mawkCandidates.firstOrNull { it.exists() && it.isFile }
            val awkTargets = listOf(
                File(root, "usr/bin/awk"),
                File(root, "bin/awk"),
                File(localBin, "awk")
            )
            if (realAwk != null) {
                for (target in awkTargets) {
                    try {
                        if (target.exists()) {
                            if (java.nio.file.Files.isSymbolicLink(target.toPath())) {
                                java.nio.file.Files.deleteIfExists(target.toPath())
                            } else if (target.length() == realAwk.length()) {
                                continue
                            } else {
                                target.delete()
                            }
                        }
                        realAwk.copyTo(target, overwrite = true)
                        target.setReadable(true, false)
                        target.setExecutable(true, false)
                        try { android.system.Os.chmod(target.absolutePath, 493) } catch (e: Exception) {}
                    } catch (e: Exception) {
                        android.util.Log.e("BootstrapManager", "Failed to copy awk to ${target.absolutePath}", e)
                    }
                }
            }

            // 2. which guarantee: find which.debianutils or write native command -v wrapper
            val whichDebianCandidates = listOf(
                File(root, "usr/bin/which.debianutils"),
                File(root, "bin/which.debianutils")
            )
            val realWhich = whichDebianCandidates.firstOrNull { it.exists() && it.isFile }
            val whichTargets = listOf(
                File(root, "usr/bin/which"),
                File(root, "bin/which"),
                File(localBin, "which")
            )
            for (target in whichTargets) {
                try {
                    if (realWhich != null) {
                        if (target.exists()) {
                            if (java.nio.file.Files.isSymbolicLink(target.toPath())) {
                                java.nio.file.Files.deleteIfExists(target.toPath())
                            } else if (target.length() == realWhich.length()) {
                                continue
                            } else {
                                target.delete()
                            }
                        }
                        realWhich.copyTo(target, overwrite = true)
                    } else if (!target.exists() || target.length() == 0L) {
                        target.writeText("#!/bin/sh\ncommand -v \"\$@\"\n")
                    }
                    target.setReadable(true, false)
                    target.setExecutable(true, false)
                    try { android.system.Os.chmod(target.absolutePath, 493) } catch (e: Exception) {}
                } catch (e: Exception) {
                    android.util.Log.e("BootstrapManager", "Failed to setup which at ${target.absolutePath}", e)
                }
            }
            ensureServiceManager(root)
            ensureBrowserOpener(root)
            ensureRootTools(root)
        } catch (e: Exception) {
            android.util.Log.e("BootstrapManager", "Failed in ensureEssentialBinaries", e)
        }
    }

    fun ensureServiceManager(root: File) {
        try {
            File(root, "run").mkdirs()
            File(root, "var/run").mkdirs()
            File(root, "var/lock").mkdirs()
            File(root, "etc/init.d").mkdirs()
            File(root, "etc/cortex/autostart").mkdirs()

            val localBin = File(root, "usr/local/bin")
            localBin.mkdirs()

            val serviceScript = """
#!/bin/bash
# Cortex Service Manager for Ubuntu on Android
SERVICE="${'$'}1"
ACTION="${'$'}2"
shift 2 2>/dev/null

if [ -z "${'$'}SERVICE" ]; then
    echo "Usage: service <service-name> {start|stop|restart|status}"
    echo "       service --status-all"
    exit 1
fi

if [ "${'$'}SERVICE" = "--status-all" ]; then
    echo " [ + ] Running services"
    echo " [ - ] Stopped services"
    for initscript in /etc/init.d/*; do
        if [ -f "${'$'}initscript" ] && [ -x "${'$'}initscript" ]; then
            sname="${'$'}(basename "${'$'}initscript")"
            if [ "${'$'}sname" != "skeleton" ] && [ "${'$'}sname" != "rc" ]; then
                if "${'$'}initscript" status >/dev/null 2>&1; then
                    echo " [ + ]  ${'$'}sname"
                else
                    echo " [ - ]  ${'$'}sname"
                fi
            fi
        fi
    done
    exit 0
fi

PIDFILE="/run/${'$'}SERVICE.pid"
mkdir -p /run /var/run

if [ -x "/etc/init.d/${'$'}SERVICE" ]; then
    exec "/etc/init.d/${'$'}SERVICE" "${'$'}ACTION" "${'$'}@"
fi

case "${'$'}ACTION" in
    start)
        if [ -f "${'$'}PIDFILE" ] && kill -0 "${'$'}(cat "${'$'}PIDFILE")" 2>/dev/null; then
            echo "Service ${'$'}SERVICE is already running (PID ${'$'}(cat "${'$'}PIDFILE"))."
            exit 0
        fi
        DAEMON=""
        for p in "/usr/sbin/${'$'}SERVICE" "/usr/bin/${'$'}SERVICE"; do
            if [ -x "${'$'}p" ]; then DAEMON="${'$'}p"; break; fi
        done
        if [ -n "${'$'}DAEMON" ]; then
            echo "Starting ${'$'}SERVICE..."
            "${'$'}DAEMON" "${'$'}@" &
            echo ${'$'}! > "${'$'}PIDFILE"
            echo "${'$'}SERVICE started with PID ${'$'}!"
        else
            echo "service: unrecognized service ${'$'}SERVICE"
            exit 1
        fi
        ;;
    stop)
        if [ -f "${'$'}PIDFILE" ]; then
            PID=${'$'}(cat "${'$'}PIDFILE")
            if kill -0 "${'$'}PID" 2>/dev/null; then
                echo "Stopping ${'$'}SERVICE (PID ${'$'}PID)..."
                kill "${'$'}PID" 2>/dev/null
                rm -f "${'$'}PIDFILE"
                echo "${'$'}SERVICE stopped."
            else
                rm -f "${'$'}PIDFILE"
            fi
        else
            pkill -f "${'$'}SERVICE" 2>/dev/null && echo "Stopped ${'$'}SERVICE." || echo "${'$'}SERVICE is not running."
        fi
        ;;
    status)
        if [ -f "${'$'}PIDFILE" ] && kill -0 "${'$'}(cat "${'$'}PIDFILE")" 2>/dev/null; then
            echo "* ${'$'}SERVICE is running (PID ${'$'}(cat "${'$'}PIDFILE"))"
            exit 0
        elif pgrep -f "${'$'}SERVICE" >/dev/null 2>&1; then
            echo "* ${'$'}SERVICE is running"
            exit 0
        else
            echo "* ${'$'}SERVICE is not running"
            exit 3
        fi
        ;;
    restart)
        "${'$'}0" "${'$'}SERVICE" stop
        sleep 1
        "${'$'}0" "${'$'}SERVICE" start "${'$'}@"
        ;;
    *)
        echo "Usage: service ${'$'}SERVICE {start|stop|restart|status}"
        exit 1
        ;;
esac
""".trimIndent() + "\n"

            val systemctlScript = """
#!/bin/bash
# Cortex systemctl compatibility shim
ACTION="${'$'}1"
SERVICE="${'$'}{2%.service}"
shift 2 2>/dev/null

case "${'$'}ACTION" in
    daemon-reload|reset-failed)
        exit 0
        ;;
    is-system-running)
        echo "running"
        exit 0
        ;;
    is-active)
        if [ -z "${'$'}SERVICE" ]; then exit 1; fi
        if service "${'$'}SERVICE" status >/dev/null 2>&1; then
            echo "active"
            exit 0
        else
            echo "inactive"
            exit 3
        fi
        ;;
    is-enabled)
        if [ -f "/etc/cortex/autostart/${'$'}SERVICE" ]; then
            echo "enabled"
            exit 0
        else
            echo "disabled"
            exit 1
        fi
        ;;
    enable)
        mkdir -p /etc/cortex/autostart
        touch "/etc/cortex/autostart/${'$'}SERVICE"
        echo "Enabled ${'$'}SERVICE for automatic startup."
        exit 0
        ;;
    disable)
        rm -f "/etc/cortex/autostart/${'$'}SERVICE"
        echo "Disabled ${'$'}SERVICE from automatic startup."
        exit 0
        ;;
    start|stop|restart|status|reload|force-reload)
        if [ -z "${'$'}SERVICE" ]; then
            echo "Usage: systemctl ${'$'}ACTION <service>"
            exit 1
        fi
        exec service "${'$'}SERVICE" "${'$'}ACTION" "${'$'}@"
        ;;
    list-units|list-unit-files)
        exec service --status-all
        ;;
    *)
        if [ -n "${'$'}SERVICE" ]; then
            exec service "${'$'}SERVICE" "${'$'}ACTION" "${'$'}@"
        fi
        exit 0
        ;;
esac
""".trimIndent() + "\n"

            val serviceFile = File(localBin, "service")
            serviceFile.writeText(serviceScript)
            serviceFile.setReadable(true, false)
            serviceFile.setExecutable(true, false)
            try { android.system.Os.chmod(serviceFile.absolutePath, 493) } catch (e: Exception) {}

            val systemctlFile = File(localBin, "systemctl")
            systemctlFile.writeText(systemctlScript)
            systemctlFile.setReadable(true, false)
            systemctlFile.setExecutable(true, false)
            try { android.system.Os.chmod(systemctlFile.absolutePath, 493) } catch (e: Exception) {}

            val cortexServiceFile = File(localBin, "cortex-service")
            cortexServiceFile.writeText("#!/bin/sh\nexec service \"${'$'}@\"\n")
            cortexServiceFile.setReadable(true, false)
            cortexServiceFile.setExecutable(true, false)
            try { android.system.Os.chmod(cortexServiceFile.absolutePath, 493) } catch (e: Exception) {}
        } catch (e: Exception) {
            android.util.Log.e("BootstrapManager", "Failed to ensure service manager", e)
        }
    }

    fun ensureBrowserOpener(root: File) {
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

            val xdgOpenScript = """
#!/bin/bash
# Cortex URL & Browser Opener for Android Chrome / Default Browser
if [ -z "${'$'}1" ]; then
    echo "Usage: xdg-open <url>" >&2
    exit 1
fi

TARGET=""
for arg in "${'$'}@"; do
    case "${'$'}arg" in
        http://*|https://*|ftp://*|file://*)
            TARGET="${'$'}arg"
            break
            ;;
        --*|-*)
            ;;
        *)
            if [ -z "${'$'}TARGET" ]; then
                TARGET="${'$'}arg"
            fi
            ;;
    esac
done

if [ -z "${'$'}TARGET" ]; then
    TARGET="${'$'}1"
fi

# Send OPEN command to Cortex UrlOpenerServer on 127.0.0.1:4715
if (exec 3<>/dev/tcp/127.0.0.1/4715) 2>/dev/null; then
    echo "OPEN ${'$'}TARGET" >&3
    read -r RESPONSE <&3 2>/dev/null
    exec 3<&-
    exec 3>&-
    if [ "${'$'}RESPONSE" = "OK" ]; then
        exit 0
    fi
fi

# Fallback to Android am command if available
if command -v am >/dev/null 2>&1; then
    am start -a android.intent.action.VIEW -d "${'$'}TARGET" >/dev/null 2>&1 && exit 0
fi

echo "xdg-open: Unable to open browser for: ${'$'}TARGET" >&2
exit 1
""".trimIndent() + "\n"

            val xdgOpenFile = File(localBin, "xdg-open")
            xdgOpenFile.writeText(xdgOpenScript)
            xdgOpenFile.setReadable(true, false)
            xdgOpenFile.setExecutable(true, false)
            try { android.system.Os.chmod(xdgOpenFile.absolutePath, 493) } catch (e: Exception) {}

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

            val wrapperScript = """
#!/bin/sh
exec /usr/local/bin/xdg-open "${'$'}@"
""".trimIndent() + "\n"

            for (alias in browserAliases) {
                val aliasFile = File(localBin, alias)
                aliasFile.writeText(wrapperScript)
                aliasFile.setReadable(true, false)
                aliasFile.setExecutable(true, false)
                try { android.system.Os.chmod(aliasFile.absolutePath, 493) } catch (e: Exception) {}
            }
        } catch (e: Exception) {
            android.util.Log.e("BootstrapManager", "Failed to ensure browser opener", e)
        }
    }

    fun ensureRootTools(root: File) {
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
            }

            val localBin = File(root, "usr/local/bin")
            if (!localBin.exists()) localBin.mkdirs()

            val suScript = """
#!/bin/bash
# Cortex Root Switcher (su / tsu / sudo)

find_host_su() {
    for cand in \
        /system/bin/su \
        /system/xbin/su \
        /sbin/su \
        /data/adb/ksu/bin/su \
        /data/adb/ap/bin/su \
        /data/adb/ap/su \
        /data/adb/magisk/su \
        /vendor/bin/su \
        /system_ext/bin/su \
        /product/bin/su \
        /apex/com.android.runtime/bin/su; do
        if [ -f "${'$'}cand" ] || [ -x "${'$'}cand" ] || [ -L "${'$'}cand" ]; then
            echo "${'$'}cand"
            return 0
        fi
    done

    local host_which
    host_which=${'$'}(env -u LD_PRELOAD -u LD_LIBRARY_PATH -u GLIBC_TUNABLES PATH=/system/bin:/system/xbin:/sbin:/vendor/bin ANDROID_ROOT=/system ANDROID_DATA=/data /system/bin/sh -c 'command -v su 2>/dev/null || which su 2>/dev/null' 2>/dev/null)
    if [ -n "${'$'}host_which" ]; then
        echo "${'$'}host_which"
        return 0
    fi

    for cand in /system/bin/su /system/xbin/su /sbin/su /data/adb/ap/bin/su /data/adb/ksu/bin/su /data/adb/magisk/su; do
        if env -u LD_PRELOAD -u LD_LIBRARY_PATH -u GLIBC_TUNABLES PATH=/system/bin:/system/xbin:/sbin:/vendor/bin ANDROID_ROOT=/system ANDROID_DATA=/data ${'$'}cand -v >/dev/null 2>&1 || \
           env -u LD_PRELOAD -u LD_LIBRARY_PATH -u GLIBC_TUNABLES PATH=/system/bin:/system/xbin:/sbin:/vendor/bin ANDROID_ROOT=/system ANDROID_DATA=/data /system/bin/sh -c "${'$'}cand -v" >/dev/null 2>&1; then
            echo "${'$'}cand"
            return 0
        fi
    done

    for p in /system/bin /system/xbin /sbin /vendor/bin /system_ext/bin /product/bin; do
        if [ -x "${'$'}p/su" ] || [ -f "${'$'}p/su" ] || [ -L "${'$'}p/su" ]; then
            echo "${'$'}p/su"
            return 0
        fi
    done

    if [ -x "/data/adb/magisk/magisk" ] || [ -f "/data/adb/magisk/magisk" ]; then
        echo "/data/adb/magisk/magisk su"
        return 0
    fi

    echo "su"
    return 0
}

HOST_SU=${'$'}(find_host_su)

if [ -z "${'$'}HOST_SU" ]; then
    echo "root not found" >&2
    exit 1
fi

RUN_ANDROID_SU() {
    if [ -x "${'$'}HOST_SU" ] || [ -f "${'$'}HOST_SU" ]; then
        env -u LD_PRELOAD -u LD_LIBRARY_PATH -u GLIBC_TUNABLES \
          PATH=/system/bin:/system/xbin:/sbin:/vendor/bin \
          ANDROID_ROOT=/system \
          ANDROID_DATA=/data \
          TERM="${'$'}{TERM:-xterm-256color}" \
          COLORTERM="${'$'}{COLORTERM:-truecolor}" \
          ${'$'}HOST_SU "${'$'}@"
    else
        env -u LD_PRELOAD -u LD_LIBRARY_PATH -u GLIBC_TUNABLES \
          PATH=/system/bin:/system/xbin:/sbin:/vendor/bin \
          ANDROID_ROOT=/system \
          ANDROID_DATA=/data \
          TERM="${'$'}{TERM:-xterm-256color}" \
          COLORTERM="${'$'}{COLORTERM:-truecolor}" \
          /system/bin/sh -c "exec ${'$'}HOST_SU \"\$@\"" _ "${'$'}@"
    fi
}

CHECK_ROOT() {
    local uid
    uid=${'$'}(RUN_ANDROID_SU -c 'id -u 2>/dev/null || /system/bin/id -u 2>/dev/null || /system/xbin/id -u 2>/dev/null || /system/bin/toybox id -u 2>/dev/null || echo "${'$'}UID" || echo "${'$'}USER_ID"' 2>/dev/null)
    if [ -n "${'$'}uid" ] && [ "${'$'}uid" -eq 0 ] 2>/dev/null; then
        return 0
    fi

    uid=${'$'}(env -u LD_PRELOAD -u LD_LIBRARY_PATH -u GLIBC_TUNABLES PATH=/system/bin:/system/xbin:/sbin:/vendor/bin ANDROID_ROOT=/system ANDROID_DATA=/data /system/bin/sh -c "${'$'}HOST_SU -c 'id -u 2>/dev/null || /system/bin/id -u 2>/dev/null || /system/xbin/id -u 2>/dev/null || /system/bin/toybox id -u 2>/dev/null || echo \${'$'}UID || echo \${'$'}USER_ID'" 2>/dev/null)
    if [ -n "${'$'}uid" ] && [ "${'$'}uid" -eq 0 ] 2>/dev/null; then
        return 0
    fi

    if RUN_ANDROID_SU -c 'true' 2>/dev/null; then
        return 0
    fi

    if env -u LD_PRELOAD -u LD_LIBRARY_PATH -u GLIBC_TUNABLES PATH=/system/bin:/system/xbin:/sbin:/vendor/bin ANDROID_ROOT=/system ANDROID_DATA=/data /system/bin/sh -c "${'$'}HOST_SU -c 'true'" 2>/dev/null; then
        return 0
    fi

    return 1
}

if ! CHECK_ROOT; then
    echo "root not found" >&2
    exit 1
fi

if [ -z "${'$'}CORTEX_ROOT" ]; then
    if [ -d "/data/user/0/org.cortex.terminal/files/cortex" ]; then
        CORTEX_ROOT="/data/user/0/org.cortex.terminal/files/cortex"
    elif [ -d "/data/data/org.cortex.terminal/files/cortex" ]; then
        CORTEX_ROOT="/data/data/org.cortex.terminal/files/cortex"
    fi
fi

ROOT_HOME="${'$'}CORTEX_ROOT/root"
if [ ! -d "${'$'}ROOT_HOME" ]; then
    mkdir -p "${'$'}ROOT_HOME" 2>/dev/null || ROOT_HOME="${'$'}CORTEX_ROOT/home"
fi

CORTEX_SHELL=""
for s in "${'$'}CORTEX_ROOT/bin/bash" "${'$'}CORTEX_ROOT/usr/bin/bash" "${'$'}CORTEX_ROOT/bin/sh" "${'$'}CORTEX_ROOT/usr/bin/sh"; do
    if [ -x "${'$'}s" ]; then
        CORTEX_SHELL="${'$'}s"
        break
    fi
done
[ -z "${'$'}CORTEX_SHELL" ] && CORTEX_SHELL="/system/bin/sh"

CORTEX_PATH="${'$'}CORTEX_ROOT/usr/local/sbin:${'$'}CORTEX_ROOT/usr/sbin:${'$'}CORTEX_ROOT/sbin:${'$'}CORTEX_ROOT/usr/local/bin:${'$'}CORTEX_ROOT/bin:${'$'}CORTEX_ROOT/usr/bin:/system/bin:/system/xbin"
CORTEX_LD="${'$'}CORTEX_ROOT/lib:${'$'}CORTEX_ROOT/usr/lib:${'$'}CORTEX_ROOT/lib/aarch64-linux-gnu:${'$'}CORTEX_ROOT/usr/lib/aarch64-linux-gnu:${'$'}CORTEX_ROOT/lib/arm-linux-gnueabihf:${'$'}CORTEX_ROOT/usr/lib/arm-linux-gnueabihf:${'$'}CORTEX_ROOT/usr/local/lib"
CORTEX_PRELOAD=""
if [ -f "${'$'}CORTEX_ROOT/usr/lib/libcortex-hook.so" ]; then
    CORTEX_PRELOAD="${'$'}CORTEX_ROOT/usr/lib/libcortex-hook.so"
elif [ -f "${'$'}CORTEX_ROOT/lib/libcortex-hook.so" ]; then
    CORTEX_PRELOAD="${'$'}CORTEX_ROOT/lib/libcortex-hook.so"
fi

CERT_FILE="${'$'}CORTEX_ROOT/etc/ssl/certs/ca-certificates.crt"
CERT_DIR="${'$'}CORTEX_ROOT/etc/ssl/certs:/system/etc/security/cacerts"
CURRENT_DIR="${'$'}PWD"

ENV_SETUP="export CORTEX_ROOT='${'$'}CORTEX_ROOT'; \
export PATH='${'$'}CORTEX_PATH'; \
export LD_LIBRARY_PATH='${'$'}CORTEX_LD'; \
export GLIBC_TUNABLES='glibc.pthread.rseq=0'; \
export LANG='C.UTF-8'; \
export LC_ALL='C.UTF-8'; \
export LOCPATH='${'$'}CORTEX_ROOT/usr/lib/locale'; \
export USER='root'; \
export LOGNAME='root'; \
export HOSTNAME='cortex-android'; \
export TERM='${'$'}{TERM:-xterm-256color}'; \
export COLORTERM='${'$'}{COLORTERM:-truecolor}'; \
export SSL_CERT_FILE='${'$'}CERT_FILE'; \
export SSL_CERT_DIR='${'$'}CERT_DIR'; \
export CURL_CA_BUNDLE='${'$'}CERT_FILE'; \
export NODE_EXTRA_CA_CERTS='${'$'}CERT_FILE'; \
export REQUESTS_CA_BUNDLE='${'$'}CERT_FILE'; \
export TZDIR='${'$'}CORTEX_ROOT/usr/share/zoneinfo'; \
export TERMINFO='${'$'}CORTEX_ROOT/usr/share/terminfo'; \
export TERMINFO_DIRS='${'$'}CORTEX_ROOT/usr/share/terminfo:${'$'}CORTEX_ROOT/lib/terminfo:${'$'}CORTEX_ROOT/etc/terminfo:/usr/share/terminfo'; \
export GODEBUG='netdns=cgo'; \
export BROWSER='/usr/local/bin/xdg-open'; \
export PS1='\[\033[01;31m\]\u@\h\[\033[00m\]:\[\033[01;34m\]\w\[\033[00m\]# ';"

if [ -n "${'$'}CORTEX_PRELOAD" ]; then
    ENV_SETUP="${'$'}ENV_SETUP export LD_PRELOAD='${'$'}CORTEX_PRELOAD';"
fi

CORTEX_LD_SO=""
for cand_ld in \
    "${'$'}CORTEX_ROOT/usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1" \
    "${'$'}CORTEX_ROOT/lib/ld-linux-aarch64.so.1" \
    "${'$'}CORTEX_ROOT/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1" \
    "${'$'}CORTEX_ROOT/usr/lib/ld-linux-aarch64.so.1" \
    "${'$'}CORTEX_ROOT/usr/lib/arm-linux-gnueabihf/ld-linux-armhf.so.3" \
    "${'$'}CORTEX_ROOT/lib/ld-linux-armhf.so.3" \
    "${'$'}CORTEX_ROOT/lib/arm-linux-gnueabihf/ld-linux-armhf.so.3"; do
    if [ -f "${'$'}cand_ld" ] || [ -x "${'$'}cand_ld" ] || [ -L "${'$'}cand_ld" ]; then
        CORTEX_LD_SO="${'$'}cand_ld"
        break
    fi
done

if [ -n "${'$'}CORTEX_LD_SO" ]; then
    LAUNCH_SHELL="'${'$'}CORTEX_LD_SO' --library-path '${'$'}CORTEX_LD' '${'$'}CORTEX_SHELL'"
else
    LAUNCH_SHELL="'${'$'}CORTEX_SHELL'"
fi

if [ -x "${'$'}HOST_SU" ] || [ -f "${'$'}HOST_SU" ]; then
    RUN_SU="env -u LD_PRELOAD -u LD_LIBRARY_PATH -u GLIBC_TUNABLES PATH=/system/bin:/system/xbin:/sbin:/vendor/bin ANDROID_ROOT=/system ANDROID_DATA=/data TERM='${'$'}{TERM:-xterm-256color}' COLORTERM='${'$'}{COLORTERM:-truecolor}' ${'$'}HOST_SU"
else
    RUN_SU="env -u LD_PRELOAD -u LD_LIBRARY_PATH -u GLIBC_TUNABLES PATH=/system/bin:/system/xbin:/sbin:/vendor/bin ANDROID_ROOT=/system ANDROID_DATA=/data TERM='${'$'}{TERM:-xterm-256color}' COLORTERM='${'$'}{COLORTERM:-truecolor}' /system/bin/sh -c \"exec ${'$'}HOST_SU \\\"\\\$@\\\"\" _"
fi

if [ "${'$'}1" = "-c" ]; then
    shift
    exec ${'$'}RUN_SU -c "${'$'}ENV_SETUP export HOME='${'$'}ROOT_HOME'; cd '${'$'}CURRENT_DIR' 2>/dev/null || cd '${'$'}ROOT_HOME' 2>/dev/null; exec ${'$'}LAUNCH_SHELL -c \"\$@\"" _ "${'$'}@"
elif [ "${'$'}1" = "-" ] || [ "${'$'}1" = "-l" ] || [ "${'$'}1" = "--login" ]; then
    exec ${'$'}RUN_SU -c "${'$'}ENV_SETUP export HOME='${'$'}ROOT_HOME'; cd '${'$'}ROOT_HOME' 2>/dev/null; exec ${'$'}LAUNCH_SHELL -l -i"
elif [ "${'$'}1" = "root" ]; then
    shift
    if [ "${'$'}#" -eq 0 ]; then
        exec ${'$'}RUN_SU -c "${'$'}ENV_SETUP export HOME='${'$'}ROOT_HOME'; cd '${'$'}CURRENT_DIR' 2>/dev/null || cd '${'$'}ROOT_HOME' 2>/dev/null; exec ${'$'}LAUNCH_SHELL -i"
    else
        exec ${'$'}RUN_SU -c "${'$'}ENV_SETUP export HOME='${'$'}ROOT_HOME'; cd '${'$'}CURRENT_DIR' 2>/dev/null || cd '${'$'}ROOT_HOME' 2>/dev/null; exec ${'$'}LAUNCH_SHELL -c \"\$*\"" _ "${'$'}@"
    fi
elif [ "${'$'}#" -eq 0 ]; then
    exec ${'$'}RUN_SU -c "${'$'}ENV_SETUP export HOME='${'$'}ROOT_HOME'; cd '${'$'}CURRENT_DIR' 2>/dev/null || cd '${'$'}ROOT_HOME' 2>/dev/null; exec ${'$'}LAUNCH_SHELL -i"
else
    exec ${'$'}RUN_SU -c "${'$'}ENV_SETUP export HOME='${'$'}ROOT_HOME'; cd '${'$'}CURRENT_DIR' 2>/dev/null || cd '${'$'}ROOT_HOME' 2>/dev/null; exec ${'$'}LAUNCH_SHELL -c \"\$*\"" _ "${'$'}@"
fi
""".trimIndent() + "\n"

            val suFile = File(localBin, "su")
            suFile.writeText(suScript)
            suFile.setReadable(true, false)
            suFile.setExecutable(true, false)
            try { android.system.Os.chmod(suFile.absolutePath, 493) } catch (e: Exception) {}

            val tsuScript = """
#!/bin/sh
SCRIPT_DIR="${'$'}(cd "${'$'}(dirname "${'$'}0")" && pwd)"
if [ -x "${'$'}SCRIPT_DIR/su" ]; then
    exec "${'$'}SCRIPT_DIR/su" "${'$'}@"
elif [ -x /usr/local/bin/su ]; then
    exec /usr/local/bin/su "${'$'}@"
else
    exec su "${'$'}@"
fi
""".trimIndent() + "\n"

            val tsuFile = File(localBin, "tsu")
            tsuFile.writeText(tsuScript)
            tsuFile.setReadable(true, false)
            tsuFile.setExecutable(true, false)
            try { android.system.Os.chmod(tsuFile.absolutePath, 493) } catch (e: Exception) {}

            val sudoFile = File(localBin, "sudo")
            sudoFile.writeText(tsuScript)
            sudoFile.setReadable(true, false)
            sudoFile.setExecutable(true, false)
            try { android.system.Os.chmod(sudoFile.absolutePath, 493) } catch (e: Exception) {}

            val rootFile = File(localBin, "root")
            rootFile.writeText(tsuScript)
            rootFile.setReadable(true, false)
            rootFile.setExecutable(true, false)
            try { android.system.Os.chmod(rootFile.absolutePath, 493) } catch (e: Exception) {}

            val nanoDir = File(root, "usr/share/nano")
            if (!nanoDir.exists()) nanoDir.mkdirs()
            val defaultNanorc = File(nanoDir, "default.nanorc")
            if (!defaultNanorc.exists()) {
                defaultNanorc.writeText("## Default syntax highlighting\nsyntax \"default\"\n")
                defaultNanorc.setReadable(true, false)
            }
        } catch (e: Exception) {
            android.util.Log.e("BootstrapManager", "Failed to ensure root tools", e)
        }
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
                    localTimeFile.setReadable(true, false)
                } catch (e: Exception) {
                    android.util.Log.e("BootstrapManager", "Failed to copy zoneinfo to localtime", e)
                }
            } else {
                val tzifBytes = createTzifBytes(offsetSeconds, abbr, posixTz)
                try {
                    if (localTimeFile.exists()) {
                        localTimeFile.delete()
                    }
                    localTimeFile.writeBytes(tzifBytes)
                    localTimeFile.setReadable(true, false)
                } catch (e: Exception) {
                    android.util.Log.e("BootstrapManager", "Failed to write generated localtime", e)
                }
            }

            val effectiveTz = if (zoneinfoFile.exists() && zoneinfoFile.isFile) tzId else posixTz
            tzFile.writeText(effectiveTz + "\n")
            tzFile.setReadable(true, false)
        } catch (e: Exception) {
            android.util.Log.e("BootstrapManager", "Failed to update timezone", e)
        }
    }

    private fun createTzifBytes(offsetSeconds: Int, tzAbbr: String, posixStr: String): ByteArray {
        val abbrBytes = tzAbbr.toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
        val charcnt = abbrBytes.size

        val bb = java.nio.ByteBuffer.allocate(256).order(java.nio.ByteOrder.BIG_ENDIAN)

        // Header 1 (32-bit v1)
        bb.put("TZif2".toByteArray(Charsets.US_ASCII))
        bb.put(ByteArray(15))
        bb.putInt(0) // ttisgmtcnt
        bb.putInt(0) // ttisstdcnt
        bb.putInt(0) // leapcnt
        bb.putInt(0) // timecnt
        bb.putInt(1) // typecnt
        bb.putInt(charcnt) // charcnt

        // ttinfo 1
        bb.putInt(offsetSeconds)
        bb.put(0.toByte()) // isdst
        bb.put(0.toByte()) // abbridx
        bb.put(abbrBytes)

        // Header 2 (64-bit v2)
        bb.put("TZif2".toByteArray(Charsets.US_ASCII))
        bb.put(ByteArray(15))
        bb.putInt(0)
        bb.putInt(0)
        bb.putInt(0)
        bb.putInt(0)
        bb.putInt(1)
        bb.putInt(charcnt)

        // ttinfo 2
        bb.putInt(offsetSeconds)
        bb.put(0.toByte())
        bb.put(0.toByte())
        bb.put(abbrBytes)

        // Footer
        bb.put("\n$posixStr\n".toByteArray(Charsets.US_ASCII))

        bb.flip()
        val out = ByteArray(bb.remaining())
        bb.get(out)
        return out
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
            android.util.Log.e("BootstrapManager", "Background command stream error", e)
        }
        return pty.waitFor()
    }
}

