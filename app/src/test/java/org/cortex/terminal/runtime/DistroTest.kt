package org.cortex.terminal.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DistroTest {

    @Test
    fun parse_aRootfsWithoutTheFileIsTheBundledUbuntu() {
        assertEquals(Distro.BUNDLED, Distro.parse(null))
        assertEquals(Distro.BUNDLED, Distro.parse(""))
        assertTrue(Distro.parse(null).isBundled)
        assertTrue(Distro.parse(null).usesApt)
    }

    @Test
    fun parse_readsWhatTheImageBuilderWrote() {
        val d = Distro.parse("ID=arch\nNAME=Arch Linux ARM\nVERSION=rolling\nPM=pacman\nIMAGE=20261115_04:18\n")
        assertEquals("arch", d.id)
        assertEquals(PackageManager.PACMAN, d.pm)
        assertFalse(d.isBundled)
        assertFalse(d.usesApt)
        assertEquals("Arch Linux ARM", d.label)
        assertEquals("Debian 13", Distro.parse("ID=debian-13\nNAME=Debian\nVERSION=13\nPM=apt\n").label)
    }

    @Test
    fun parseCatalog_keepsOnlyThisArchitectureAndWellFormedEntries() {
        val sha = "a".repeat(64)
        val json = """{"format": 1, "images": [
            {"id": "debian-13", "arch": "arm64", "name": "Debian", "version": "13", "pm": "apt", "file": "cortex-debian-13-arm64.tar.zst", "sha256": "$sha", "size": 10, "unpacked": 40},
            {"id": "debian-13", "arch": "armhf", "name": "Debian", "version": "13", "pm": "apt", "file": "cortex-debian-13-armhf.tar.zst", "sha256": "$sha", "size": 9, "unpacked": 30},
            {"id": "evil", "arch": "arm64", "name": "x", "version": "1", "pm": "apt", "file": "../../etc/passwd", "sha256": "$sha", "size": 1, "unpacked": 1},
            {"id": "nohash", "arch": "arm64", "name": "x", "version": "1", "pm": "apt", "file": "cortex-nohash-arm64.tar.zst", "sha256": "abc", "size": 1, "unpacked": 1},
            {"id": "odd", "arch": "arm64", "name": "x", "version": "1", "pm": "portage", "file": "cortex-odd-arm64.tar.zst", "sha256": "$sha", "size": 1, "unpacked": 1}
        ]}"""
        val arm64 = DistroInstaller.parseCatalog(json, "arm64")
        assertEquals(listOf("debian-13"), arm64.map { it.id })
        assertEquals(10L, arm64[0].size)
        assertEquals(listOf(9L), DistroInstaller.parseCatalog(json, "armhf").map { it.size })
    }

    @Test
    fun setupSteps_followTheChoices() {
        val bundled = DistroSetup.option(Distro.BUNDLED_ID)!!
        val arch = DistroSetup.option("arch")!!
        val terminalOnly = DistroSetup.steps(bundled, null, browser = false).map { it.command }
        assertEquals(3, terminalOnly.size) // check, unpack, set up: no download for the built-in one
        val withDesktop = DistroSetup.steps(arch, "kde", browser = true)
        assertEquals("pacman-key --init && pacman-key --populate", withDesktop.first { it.title == org.cortex.terminal.R.string.setup_step_configure }.command)
        assertEquals("pacman -Syu --noconfirm", withDesktop.first { it.title == org.cortex.terminal.R.string.setup_step_refresh }.command)
        assertEquals("cortex-desktop install kde", withDesktop.first { it.title == org.cortex.terminal.R.string.setup_step_desktop }.command)
        assertEquals("cortex-desktop browser", withDesktop.last().command)
    }

    @Test
    fun sudoPasswordHash_isWhatSuShComputes() {
        // su.sh: printf '%s%s' "$salt" "$pw" | sha256sum
        assertEquals("5e884898da28047151d0e56f8dc6292773603d0d6aabbdd62a11ef721d1542d8", UserAccount.passwordHash("", "password"))
        assertEquals(UserAccount.passwordHash("", "abcpw"), UserAccount.passwordHash("abc", "pw"))
    }
}
