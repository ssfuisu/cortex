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

        val etcProfile = File(root, "etc/profile")
        if (etcProfile.exists()) {
            val pText = etcProfile.readText()
            if (pText.contains("`id -u`") || pText.contains("$(id -u)")) {
                etcProfile.writeText(pText.replace("`id -u`", "\${EUID:-0}").replace("$(id -u)", "\${EUID:-0}"))
            }
        }

        ensureHookLibrary(context, root)
        updateDnsConfiguration(context, root)
        cleanupAptArtifacts(root)
        ensureAptSandbox(root)
        ensureDpkgTables(root)
        ensureLocale(root)
        ensureHosts(root)
        ensureNsswitch(root)
        ensureCaCertificates(root)

        File(root, "var/cache/apt/archives/partial").mkdirs()
        File(root, "var/lib/apt/lists/partial").mkdirs()
        File(root, "tmp").mkdirs()

        // File system structure initialized
        patchAllDynamicLinkers(root)
    }

    private const val CURRENT_BOOTSTRAP_VERSION = 12412

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
            ensureAptSandbox(root)

            // Ensure sources.list exists without duplicating debian.sources
            val debianSources = File(root, "etc/apt/sources.list.d/debian.sources")
            val sourcesList = File(root, "etc/apt/sources.list")
            if (debianSources.exists()) {
                if (sourcesList.exists()) sourcesList.delete()
            } else if (!sourcesList.exists() || sourcesList.length() == 0L) {
                sourcesList.writeText(
                    "deb http://deb.debian.org/debian bookworm main contrib non-free non-free-firmware\n" +
                    "deb http://security.debian.org/debian-security bookworm-security main contrib non-free non-free-firmware\n" +
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

            ensureAptSandbox(root)
            ensureDpkgTables(root)
            ensureLocale(root)
            ensureHosts(root)
            ensureNsswitch(root)
            ensureCaCertificates(root)
            cleanupAptArtifacts(root)

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
                if (file.isFile && file.name.startsWith("ld-linux") && !java.nio.file.Files.isSymbolicLink(file.toPath())) {
                    patchDynamicLinker(file)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("BootstrapManager", "Error walking root to patch dynamic linkers", e)
        }
    }

    fun patchDynamicLinker(file: File) {
        if (!file.exists() || !file.isFile || java.nio.file.Files.isSymbolicLink(file.toPath())) {
            return
        }
        try {
            val bytes = file.readBytes()
            var modified = false

            // AArch64: mov x8, #0x63 (syscall 99 set_robust_list)
            // Little-endian bytes: 68 0c 80 d2
            val movX8Syscall99 = byteArrayOf(0x68.toByte(), 0x0c.toByte(), 0x80.toByte(), 0xd2.toByte())
            // svc #0 in AArch64: 01 00 00 d4
            val svcAarch64 = byteArrayOf(0x01.toByte(), 0x00.toByte(), 0x00.toByte(), 0xd4.toByte())
            // nop in AArch64: 1f 20 03 d5
            val nopAarch64 = byteArrayOf(0x1f.toByte(), 0x20.toByte(), 0x03.toByte(), 0xd5.toByte())

            var pos = 0
            while (pos <= bytes.size - 4) {
                if (bytes[pos] == movX8Syscall99[0] &&
                    bytes[pos + 1] == movX8Syscall99[1] &&
                    bytes[pos + 2] == movX8Syscall99[2] &&
                    bytes[pos + 3] == movX8Syscall99[3]) {

                    val searchEnd = minOf(bytes.size - 4, pos + 64)
                    for (i in (pos + 4)..searchEnd step 4) {
                        if (bytes[i] == svcAarch64[0] &&
                            bytes[i + 1] == svcAarch64[1] &&
                            bytes[i + 2] == svcAarch64[2] &&
                            bytes[i + 3] == svcAarch64[3]) {

                            nopAarch64.copyInto(bytes, destinationOffset = i)
                            modified = true
                            android.util.Log.i("BootstrapManager", "Patched syscall 99 svc #0 at 0x${Integer.toHexString(i)} in ${file.name}")
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
                "export SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt\n" +
                "export CURL_CA_BUNDLE=/etc/ssl/certs/ca-certificates.crt\n"
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
                "151.101.130.132 deb.debian.org\n" +
                "151.101.2.132 deb.debian.org\n" +
                "151.101.66.132 deb.debian.org\n" +
                "151.101.194.132 deb.debian.org\n" +
                "151.101.130.132 security.debian.org\n" +
                "151.101.2.132 security.debian.org\n" +
                "151.101.66.132 security.debian.org\n" +
                "151.101.194.132 security.debian.org\n" +
                "151.101.130.132 cdn-fastly.deb.debian.org\n" +
                "151.101.2.132 cdn-fastly.deb.debian.org\n"

            if (!hostsFile.exists()) {
                hostsFile.writeText(defaultHosts)
            } else {
                val currentText = hostsFile.readText()
                if (!currentText.contains("deb.debian.org")) {
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
            val sourcesList = File(root, "etc/apt/sources.list")
            if (debianSources.exists() && sourcesList.exists()) {
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

    fun ensureCaCertificates(root: File) {
        try {
            val certsDir = File(root, "etc/ssl/certs")
            certsDir.mkdirs()
            val caBundle = File(certsDir, "ca-certificates.crt")
            if (!caBundle.exists() || caBundle.length() < 1000L) {
                val androidCertsDir = File("/system/etc/security/cacerts")
                val sb = StringBuilder()
                if (androidCertsDir.exists() && androidCertsDir.isDirectory) {
                    androidCertsDir.listFiles()?.forEach { f ->
                        if (f.isFile && f.name.endsWith(".0")) {
                            try {
                                val content = f.readText()
                                val start = content.indexOf("-----BEGIN CERTIFICATE-----")
                                val end = content.indexOf("-----END CERTIFICATE-----")
                                if (start != -1 && end != -1) {
                                    sb.append(content.substring(start, end + "-----END CERTIFICATE-----".length)).append("\n")
                                }
                            } catch (e: Exception) {}
                        }
                    }
                }
                if (sb.isNotEmpty()) {
                    caBundle.writeText(sb.toString())
                    caBundle.setReadable(true, false)
                    android.util.Log.i("BootstrapManager", "Generated ca-certificates.crt (${caBundle.length()} bytes)")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("BootstrapManager", "Failed to ensure CA certificates", e)
        }
    }
}

