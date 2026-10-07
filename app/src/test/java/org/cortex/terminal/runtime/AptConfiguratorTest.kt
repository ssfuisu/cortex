package org.cortex.terminal.runtime

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AptConfiguratorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun ensureUbuntuSources_writesHttpsDefaultAndRemovesLegacySourcesList() {
        val root = tempFolder.newFolder("rootfs")
        val legacyList = File(root, "etc/apt/sources.list").apply {
            parentFile?.mkdirs()
            writeText("deb http://ports.ubuntu.com/ubuntu-ports noble main\n")
        }

        AptConfigurator.ensureUbuntuSources(root)

        val ubuntuSources = File(root, "etc/apt/sources.list.d/ubuntu.sources")
        assertTrue("ubuntu.sources must be created", ubuntuSources.exists())
        val content = ubuntuSources.readText()
        assertTrue("ubuntu.sources must use https://", content.contains("URIs: https://ports.ubuntu.com/ubuntu-ports/"))
        assertFalse("ubuntu.sources must not use plaintext http://", content.contains("http://ports.ubuntu.com/ubuntu-ports/"))
        assertTrue("ubuntu.sources must include Signed-By", content.contains("Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg"))
        assertFalse("Legacy sources.list must be removed", legacyList.exists())
    }

    @Test
    fun ensureUbuntuSources_upgradesExistingHttpToHttps() {
        val root = tempFolder.newFolder("rootfs")
        val ubuntuSources = File(root, "etc/apt/sources.list.d/ubuntu.sources").apply {
            parentFile?.mkdirs()
            writeText(
                "Types: deb\n" +
                    "URIs: http://ports.ubuntu.com/ubuntu-ports/\n" +
                    "Suites: noble noble-updates\n" +
                    "Components: main universe\n" +
                    "Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg\n"
            )
        }

        AptConfigurator.ensureUbuntuSources(root)

        val updated = ubuntuSources.readText()
        assertTrue(updated.contains("URIs: https://ports.ubuntu.com/ubuntu-ports/"))
        assertFalse(updated.contains("http://ports.ubuntu.com/ubuntu-ports/"))
        assertTrue(updated.contains("Suites: noble noble-updates"))
    }

    @Test
    fun ensureUbuntuSources_preservesCustomValidHttpsSources() {
        val root = tempFolder.newFolder("rootfs")
        val customContent =
            "Types: deb\n" +
                "URIs: https://mirror.example.org/ubuntu-ports/\n" +
                "Suites: noble noble-security\n" +
                "Components: main restricted\n" +
                "Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg\n"
        val ubuntuSources = File(root, "etc/apt/sources.list.d/ubuntu.sources").apply {
            parentFile?.mkdirs()
            writeText(customContent)
        }

        AptConfigurator.ensureUbuntuSources(root)

        assertEquals(customContent, ubuntuSources.readText())
    }

    @Test
    fun hasExpectedKeyringDigest_acceptsValidSha256AndRejectsTamperedKeyIdFile() {
        val root = tempFolder.newFolder("rootfs")
        AptConfigurator.ensureKeyrings(root, null)

        val shareKeyring = File(root, "usr/share/keyrings/ubuntu-archive-keyring.gpg")
        assertTrue("Official keyring must exist", shareKeyring.exists())
        assertTrue(
            "Official keyring must pass SHA-256 verification",
            AptConfigurator.hasExpectedKeyringDigest(shareKeyring)
        )

        // Forged file containing the 8-byte key ID 871920D1991BC93C followed by attacker payload
        val forgedKeyring = File(root, "usr/share/keyrings/forged.gpg")
        val keyIdBytes = byteArrayOf(
            0x87.toByte(), 0x19.toByte(), 0x20.toByte(), 0xd1.toByte(),
            0x99.toByte(), 0x1b.toByte(), 0xc9.toByte(), 0x3c.toByte()
        )
        forgedKeyring.writeBytes(keyIdBytes + ByteArray(256) { 0x41 })
        assertFalse(
            "Forged file containing 8-byte key ID must be rejected by SHA-256 check",
            AptConfigurator.hasExpectedKeyringDigest(forgedKeyring)
        )

        // Truncated official keyring must also be rejected
        val originalBytes = shareKeyring.readBytes()
        val truncatedKeyring = File(root, "usr/share/keyrings/truncated.gpg")
        truncatedKeyring.writeBytes(originalBytes.copyOf(originalBytes.size - 1))
        assertFalse(
            "Truncated keyring must be rejected by SHA-256 check",
            AptConfigurator.hasExpectedKeyringDigest(truncatedKeyring)
        )
    }

    @Test
    fun ensureKeyrings_preservesThirdPartyKeyringsAndUserGnupg() {
        val root = tempFolder.newFolder("rootfs")
        val trustedD = File(root, "etc/apt/trusted.gpg.d").apply { mkdirs() }

        val thirdPartyGpg = File(trustedD, "docker-archive-keyring.gpg")
        val thirdPartyGpgBytes = byteArrayOf(0x99.toByte(), 0x01, 0x02, 0x03, 0x04)
        thirdPartyGpg.writeBytes(thirdPartyGpgBytes)

        val thirdPartyAsc = File(trustedD, "nodesource.asc")
        val thirdPartyAscText = "-----BEGIN PGP PUBLIC KEY BLOCK-----\nCustomKey\n-----END PGP PUBLIC KEY BLOCK-----\n"
        thirdPartyAsc.writeText(thirdPartyAscText)

        // Corrupted managed keyring containing only the old 8-byte key ID
        val corruptedManaged = File(trustedD, "ubuntu-keyring-2018-archive.gpg")
        corruptedManaged.writeBytes(
            byteArrayOf(
                0x87.toByte(), 0x19.toByte(), 0x20.toByte(), 0xd1.toByte(),
                0x99.toByte(), 0x1b.toByte(), 0xc9.toByte(), 0x3c.toByte()
            )
        )

        val userGnupg = File(root, "home/.gnupg/trustedkeys.gpg").apply {
            parentFile?.mkdirs()
            writeText("user-personal-keyring")
        }
        val rootGnupg = File(root, "root/.gnupg/trustedkeys.gpg").apply {
            parentFile?.mkdirs()
            writeText("root-personal-keyring")
        }

        AptConfigurator.ensureKeyrings(root, null)

        assertTrue("Third-party .gpg file in trusted.gpg.d must be preserved", thirdPartyGpg.exists())
        assertArrayEquals(thirdPartyGpgBytes, thirdPartyGpg.readBytes())

        assertTrue("Third-party .asc file in trusted.gpg.d must be preserved", thirdPartyAsc.exists())
        assertEquals(thirdPartyAscText, thirdPartyAsc.readText())

        assertFalse("Corrupted managed ubuntu-keyring-2018-archive.gpg must be cleaned up", corruptedManaged.exists())

        val managedTrusted = File(trustedD, "ubuntu-archive-keyring.gpg")
        assertTrue("ubuntu-archive-keyring.gpg in trusted.gpg.d must be installed", managedTrusted.exists())
        assertTrue(AptConfigurator.hasExpectedKeyringDigest(managedTrusted))

        assertEquals("user-personal-keyring", userGnupg.readText())
        assertEquals("root-personal-keyring", rootGnupg.readText())
    }

    @Test
    fun ensureAptSandbox_writesSandboxAndSynchronizedProfileScript() {
        val root = tempFolder.newFolder("rootfs")
        AptConfigurator.ensureAptSandbox(root)

        val sandbox = File(root, "etc/apt/apt.conf.d/01sandbox")
        assertTrue(sandbox.exists())
        val sandboxText = sandbox.readText()
        assertTrue(sandboxText.contains("APT::Sandbox::Seccomp \"false\";"))
        assertTrue(sandboxText.contains("Acquire::AllowInsecureRepositories \"false\";"))
        assertTrue(sandboxText.contains("APT::Get::AllowUnauthenticated \"false\";"))

        val profileCortex = File(root, "etc/profile.d/01cortex.sh")
        assertTrue(profileCortex.exists())
        val profileText = profileCortex.readText()
        assertTrue(profileText.contains("TAR_OPTIONS"))
        assertTrue(profileText.contains("GODEBUG=netdns=cgo"))
        assertTrue(profileText.contains("/etc/cortex/autostart"))
    }
}
