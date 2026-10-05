package org.cortex.terminal.runtime

import android.content.Context
import android.util.Base64
import android.util.Log
import java.io.File

object AptConfigurator {
    private const val TAG = "AptConfigurator"

    private const val UBUNTU_ARCHIVE_KEYRING_BASE64 =
        "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"

    // Signing key ID: 871920D1991BC93C (Ubuntu Archive Automatic Signing Key 2018)
    private val KEY_ID_PATTERN = byteArrayOf(
        0x87.toByte(), 0x19.toByte(), 0x20.toByte(), 0xd1.toByte(),
        0x99.toByte(), 0x1b.toByte(), 0xc9.toByte(), 0x3c.toByte()
    )

    fun ensureAptSandbox(root: File) {
        try {
            val aptConfDir = File(root, "etc/apt/apt.conf.d")
            aptConfDir.mkdirs()
            val sbFile = File(aptConfDir, "01sandbox")
            // Strict GPG verification: No insecure repository bypasses
            sbFile.writeText(
                "APT::Sandbox::User \"root\";\n" +
                "APT::Sandbox::Seccomp \"false\";\n" +
                "Acquire::ForceIPv4 \"true\";\n" +
                "Acquire::Connect::AddrConfig \"false\";\n" +
                "Acquire::SRV \"false\";\n" +
                "Acquire::Languages \"none\";\n" +
                "Acquire::GzipIndexes \"true\";\n" +
                "Acquire::AllowInsecureRepositories \"false\";\n" +
                "Acquire::AllowDowngradeToInsecureRepositories \"false\";\n" +
                "APT::Get::AllowUnauthenticated \"false\";\n" +
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
            sbFile.setReadable(true, true)
            sbFile.setWritable(true, true)
            try { android.system.Os.chmod(sbFile.absolutePath, 384) } catch (e: Exception) {} // 0600

            val dockerClean = File(aptConfDir, "docker-clean")
            dockerClean.writeText("# Disabled for Cortex\n")
            dockerClean.setReadable(true, true)
            dockerClean.setWritable(true, true)
            try { android.system.Os.chmod(dockerClean.absolutePath, 384) } catch (e: Exception) {}

            val aptPrefDir = File(root, "etc/apt/preferences.d")
            aptPrefDir.mkdirs()
            val cortexPins = File(aptPrefDir, "01cortex-pins")
            cortexPins.writeText(
                "Explanation: Pin core glibc and linker packages to prevent upgrading to stock upstream packages that fail Android SECCOMP\n" +
                "Package: libc6*\n" +
                "Pin: release *\n" +
                "Pin-Priority: -1\n\n" +
                "Package: libc-bin\n" +
                "Pin: release *\n" +
                "Pin-Priority: -1\n\n" +
                "Package: libc-dev-bin\n" +
                "Pin: release *\n" +
                "Pin-Priority: -1\n\n" +
                "Package: locales\n" +
                "Pin: release *\n" +
                "Pin-Priority: -1\n"
            )
            cortexPins.setReadable(true, true)
            cortexPins.setWritable(true, true)

            val dpkgStatusFile = File(root, "var/lib/dpkg/status")
            if (dpkgStatusFile.exists() && dpkgStatusFile.isFile) {
                try {
                    val content = dpkgStatusFile.readText()
                    val packagesToHold = setOf(
                        "libc6", "libc6:arm64", "libc6:armhf",
                        "libc-bin", "libc-bin:arm64", "libc-bin:armhf",
                        "libc-dev-bin", "libc-dev-bin:arm64", "libc-dev-bin:armhf",
                        "locales", "locales:all"
                    )
                    var updated = false
                    val blocks = content.split(Regex("\\n\\n+"))
                    val newBlocks = blocks.map { block ->
                        val pkgLine = block.lines().firstOrNull { it.startsWith("Package:") }
                        val pkgName = pkgLine?.substringAfter(":")?.trim()
                        if (pkgName != null && packagesToHold.contains(pkgName)) {
                            val lines = block.lines().toMutableList()
                            val statusIdx = lines.indexOfFirst { it.startsWith("Status:") }
                            if (statusIdx != -1) {
                                if (lines[statusIdx] != "Status: hold ok installed") {
                                    lines[statusIdx] = "Status: hold ok installed"
                                    updated = true
                                }
                            } else {
                                val pkgIdx = lines.indexOfFirst { it.startsWith("Package:") }
                                lines.add(pkgIdx + 1, "Status: hold ok installed")
                                updated = true
                            }
                            lines.joinToString("\n")
                        } else {
                            block
                        }
                    }
                    if (updated) {
                        dpkgStatusFile.writeText(newBlocks.joinToString("\n\n") + "\n")
                        dpkgStatusFile.setReadable(true, true)
                        dpkgStatusFile.setWritable(true, true)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to update dpkg status holds", e)
                }
            }

            val dpkgCfgDir = File(root, "etc/dpkg/dpkg.cfg.d")
            dpkgCfgDir.mkdirs()
            val dpkgCortex = File(dpkgCfgDir, "01cortex")
            dpkgCortex.writeText(
                "force-confdef\n" +
                "force-confold\n" +
                "force-unsafe-io\n" +
                "no-debsig\n"
            )
            dpkgCortex.setReadable(true, true)
            dpkgCortex.setWritable(true, true)

            val profileD = File(root, "etc/profile.d")
            profileD.mkdirs()
            val profileCortex = File(profileD, "01cortex.sh")
            profileCortex.writeText(
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
            profileCortex.setReadable(true, true)
            profileCortex.setWritable(true, true)

            val usrSbinDir = File(root, "usr/sbin")
            usrSbinDir.mkdirs()

            val policyScript = "#!/bin/sh\nexit 101\n"
            val dummyExitZero = "#!/bin/sh\nexit 0\n"

            val policyFile = File(usrSbinDir, "policy-rc.d")
            policyFile.writeText(policyScript)
            policyFile.setReadable(true, true)
            policyFile.setExecutable(true, true)
            try { android.system.Os.chmod(policyFile.absolutePath, 448) } catch (e: Exception) {} // 0700

            listOf("ldconfig", "start-stop-daemon").forEach { name ->
                val f = File(usrSbinDir, name)
                f.writeText(dummyExitZero)
                f.setReadable(true, true)
                f.setExecutable(true, true)
                try { android.system.Os.chmod(f.absolutePath, 448) } catch (e: Exception) {}
            }

            val sbinDir = File(root, "sbin")
            if (sbinDir.exists() && !java.nio.file.Files.isSymbolicLink(sbinDir.toPath())) {
                listOf("policy-rc.d", "ldconfig", "start-stop-daemon").forEach { name ->
                    try {
                        val src = File(usrSbinDir, name)
                        val dst = File(sbinDir, name)
                        src.copyTo(dst, overwrite = true)
                        dst.setReadable(true, true)
                        dst.setExecutable(true, true)
                        android.system.Os.chmod(dst.absolutePath, 448)
                    } catch (e: Exception) {}
                }
            }

            File(root, "var/cache/apt/archives/partial").mkdirs()
            File(root, "var/lib/apt/lists/partial").mkdirs()
            File(root, "tmp").mkdirs()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ensure apt sandbox config", e)
        }
    }

    fun ensureUbuntuSources(root: File) {
        try {
            val sourcesDir = File(root, "etc/apt/sources.list.d")
            sourcesDir.mkdirs()
            val ubuntuSources = File(sourcesDir, "ubuntu.sources")
            // Secure repository configuration with Signed-By GPG verification
            ubuntuSources.writeText(
                "Types: deb\n" +
                "URIs: http://ports.ubuntu.com/ubuntu-ports/\n" +
                "Suites: noble noble-updates noble-backports\n" +
                "Components: main restricted universe multiverse\n" +
                "Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg\n\n" +
                "Types: deb\n" +
                "URIs: http://ports.ubuntu.com/ubuntu-ports/\n" +
                "Suites: noble-security\n" +
                "Components: main restricted universe multiverse\n" +
                "Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg\n"
            )
            ubuntuSources.setReadable(true, true)
            ubuntuSources.setWritable(true, true)
            try { android.system.Os.chmod(ubuntuSources.absolutePath, 384) } catch (e: Exception) {}

            val sourcesList = File(root, "etc/apt/sources.list")
            if (sourcesList.exists()) {
                sourcesList.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ensure ubuntu.sources", e)
        }
    }

    fun ensureKeyrings(root: File, context: Context? = null) {
        try {
            val shareKeyrings = File(root, "usr/share/keyrings")
            shareKeyrings.mkdirs()
            val trustedD = File(root, "etc/apt/trusted.gpg.d")
            trustedD.mkdirs()
            val aptKeyrings = File(root, "etc/apt/keyrings")
            aptKeyrings.mkdirs()

            // 1. Get official verified keyring bytes
            val keyBytes: ByteArray = try {
                context?.assets?.open("ubuntu-archive-keyring.gpg")?.use { it.readBytes() }
            } catch (e: Exception) {
                null
            }?.takeIf { it.isNotEmpty() } ?: Base64.decode(UBUNTU_ARCHIVE_KEYRING_BASE64, Base64.DEFAULT)

            fun containsKeyId(f: File): Boolean {
                if (!f.exists() || f.length() < KEY_ID_PATTERN.size) return false
                return try {
                    val content = f.readBytes()
                    var found = false
                    for (i in 0..(content.size - KEY_ID_PATTERN.size)) {
                        var match = true
                        for (j in KEY_ID_PATTERN.indices) {
                            if (content[i + j] != KEY_ID_PATTERN[j]) {
                                match = false
                                break
                            }
                        }
                        if (match) {
                            found = true
                            break
                        }
                    }
                    found
                } catch (e: Exception) {
                    false
                }
            }

            val targetKeyrings = listOf(
                File(shareKeyrings, "ubuntu-archive-keyring.gpg"),
                File(shareKeyrings, "ubuntu-keyring-2018-archive.gpg"),
                File(aptKeyrings, "ubuntu-archive-keyring.gpg"),
                File(aptKeyrings, "ubuntu-keyring-2018-archive.gpg"),
                File(trustedD, "ubuntu-archive-keyring.gpg"),
                File(trustedD, "ubuntu-keyring-2018-archive.gpg"),
                File(root, "etc/apt/trusted.gpg"),
                File(root, "etc/gnupg/trustedkeys.gpg"),
                File(root, "home/.gnupg/trustedkeys.gpg"),
                File(root, "root/.gnupg/trustedkeys.gpg")
            )

            for (target in targetKeyrings) {
                if (!containsKeyId(target)) {
                    try {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { it.write(keyBytes) }
                        target.setReadable(true, true)
                        target.setWritable(true, true)
                        try { android.system.Os.chmod(target.absolutePath, 384) } catch (e: Exception) {}
                        Log.i(TAG, "Wrote verified Ubuntu archive keyring to ${target.absolutePath}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed writing keyring to ${target.absolutePath}", e)
                    }
                }
            }

            // Clean up any obsolete/corrupted keyrings from trusted.gpg.d
            try {
                trustedD.listFiles()?.forEach { file ->
                    if (!containsKeyId(file)) {
                        file.delete()
                    }
                }
            } catch (e: Exception) {}

            // Copy secondary keyrings from assets if available to usr/share/keyrings and etc/apt/keyrings
            if (context != null) {
                val assetKeyrings = listOf(
                    "ubuntu-master-keyring.gpg",
                    "ubuntu-archive-removed-keys.gpg",
                    "ubuntu-keyring-2012-cdimage.gpg",
                    "ubuntu-cloudimage-keyring.gpg"
                )
                for (name in assetKeyrings) {
                    try {
                        val targets = listOf(File(shareKeyrings, name), File(aptKeyrings, name))
                        context.assets.open(name).use { inStream ->
                            val bytes = inStream.readBytes()
                            if (bytes.isNotEmpty()) {
                                for (target in targets) {
                                    if (!target.exists() || target.length() != bytes.size.toLong()) {
                                        target.parentFile?.mkdirs()
                                        target.outputStream().use { it.write(bytes) }
                                        target.setReadable(true, true)
                                        target.setWritable(true, true)
                                        try { android.system.Os.chmod(target.absolutePath, 384) } catch (e: Exception) {}
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {}
                }
            }

            // Clear any stale partial lists
            val partialLists = File(root, "var/lib/apt/lists/partial")
            if (partialLists.exists()) {
                try {
                    partialLists.listFiles()?.forEach { it.delete() }
                } catch (e: Exception) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ensure keyrings", e)
        }
    }

    fun cleanupAptArtifacts(root: File) {
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

            // Remove any downloaded or corrupted glibc deb archives
            listOf(
                File(root, "var/cache/apt/archives"),
                File(root, "var/cache/apt/archives/partial")
            ).forEach { dir ->
                if (dir.exists() && dir.isDirectory) {
                    dir.listFiles()?.forEach { file ->
                        val n = file.name
                        if (file.isFile && n.endsWith(".deb")) {
                            if (n.startsWith("libc6") || n.startsWith("libc-bin") || n.startsWith("locales") || n.startsWith("libc-dev-bin")) {
                                file.delete()
                            }
                        }
                    }
                }
            }

            // Clean stale lock files if 0 bytes
            listOf(
                File(root, "var/lib/dpkg/lock"),
                File(root, "var/lib/dpkg/lock-frontend"),
                File(root, "var/cache/apt/archives/lock"),
                File(root, "var/lib/apt/lists/lock")
            ).forEach { lockFile ->
                if (lockFile.exists() && lockFile.length() == 0L) {
                    try { lockFile.delete() } catch (e: Exception) {}
                }
            }

            // Clean any corrupted MergeList package lists
            val listsDir = File(root, "var/lib/apt/lists")
            if (listsDir.exists() && listsDir.isDirectory) {
                listsDir.listFiles()?.forEach { file ->
                    if (file.isFile && file.name.endsWith("_Packages")) {
                        var isCorrupted = false
                        try {
                            file.bufferedReader().use { reader ->
                                var inSection = false
                                var hasPackageHeader = false
                                var line: String?
                                while (reader.readLine().also { line = it } != null) {
                                    val l = line!!
                                    if (l.isEmpty()) {
                                        if (inSection && !hasPackageHeader) {
                                            isCorrupted = true
                                            break
                                        }
                                        inSection = false
                                        hasPackageHeader = false
                                    } else {
                                        if (!inSection) {
                                            inSection = true
                                            if (l.startsWith("Package:")) {
                                                hasPackageHeader = true
                                            } else {
                                                isCorrupted = true
                                                break
                                            }
                                        }
                                    }
                                }
                                if (inSection && !hasPackageHeader) {
                                    isCorrupted = true
                                }
                            }
                        } catch (e: Exception) {
                            isCorrupted = true
                        }
                        if (isCorrupted) {
                            file.delete()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cleanup apt artifacts", e)
        }
    }
}
