package org.cortex.terminal.runtime

import org.cortex.terminal.runtime.MirrorSelector.OFFICIAL
import org.cortex.terminal.runtime.MirrorSelector.Probe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MirrorSelectorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val now = 1_800_000_000_000L
    private val hour = 3600_000L
    private val yandex = "https://mirror.yandex.ru/ubuntu-ports/"
    private val ustc = "https://mirrors.ustc.edu.cn/ubuntu-ports/"

    @Test
    fun aMirrorReplacesTheOfficialServerOnlyWhenClearlyFaster() {
        assertEquals(yandex, MirrorSelector.choose(listOf(Probe(OFFICIAL, 1000, now), Probe(yandex, 300, now))))
        assertEquals("only a little faster", OFFICIAL,
            MirrorSelector.choose(listOf(Probe(OFFICIAL, 1000, now), Probe(yandex, 800, now))))
        assertEquals(OFFICIAL, MirrorSelector.choose(listOf(Probe(OFFICIAL, 200, now), Probe(yandex, 300, now))))
    }

    @Test
    fun aMirrorBehindTheCurrentIndexIsNotUsed() {
        assertEquals("12 hours behind", OFFICIAL,
            MirrorSelector.choose(listOf(Probe(OFFICIAL, 1000, now), Probe(yandex, 100, now - 12 * hour))))
        assertEquals("5 hours behind is fine", yandex,
            MirrorSelector.choose(listOf(Probe(OFFICIAL, 1000, now), Probe(yandex, 100, now - 5 * hour))))
    }

    @Test
    fun withoutTheOfficialServerTheFastestCurrentMirrorIsUsed() {
        assertEquals(ustc, MirrorSelector.choose(listOf(
            Probe(OFFICIAL, null, null),
            Probe(yandex, 900, now - 10 * hour),
            Probe(ustc, 400, now)
        )))
        assertEquals("the newest index sets what is current", ustc, MirrorSelector.choose(listOf(
            Probe(OFFICIAL, null, null),
            Probe(yandex, 100, now - 10 * hour),
            Probe(ustc, 400, now)
        )))
        assertNull(MirrorSelector.choose(listOf(Probe(OFFICIAL, null, null), Probe(yandex, null, null))))
    }

    @Test
    fun releaseDatesAsUbuntuWritesThem() {
        val text = "Origin: Ubuntu\nLabel: Ubuntu\nSuite: noble-updates\nDate: Fri, 23 Oct 2026 14:21:13 UTC\nValid-Until: x\n"
        assertEquals(1792765273000L, MirrorSelector.releaseDate(text))
        // a single-digit hour is padded with a space, not a zero
        assertEquals(1792734681000L, MirrorSelector.releaseDate("Date: Fri, 23 Oct 2026  5:51:21 UTC\n"))
        assertNull(MirrorSelector.releaseDate("Origin: Ubuntu\n"))
        assertNull(MirrorSelector.releaseDate("Date: yesterday\n"))
    }

    @Test
    fun theSourcesKeepSecurityOnTheOfficialServer() {
        val sources = MirrorSelector.sourcesFor(yandex)
        val stanzas = sources.split("\n\n")
        assertTrue(stanzas[0].contains("URIs: $yandex") && stanzas[0].contains("Suites: noble noble-updates noble-backports"))
        assertTrue(stanzas[1].contains("URIs: $OFFICIAL") && stanzas[1].contains("Suites: noble-security"))
        assertEquals(AptConfigurator.DEFAULT_UBUNTU_SOURCES, MirrorSelector.sourcesFor(OFFICIAL))
        assertEquals(yandex, MirrorSelector.mirrorOf(sources))
        assertEquals(OFFICIAL, MirrorSelector.mirrorOf(AptConfigurator.DEFAULT_UBUNTU_SOURCES))
        assertNull("sources changed by hand", MirrorSelector.mirrorOf(sources + "\n# mine\n"))
    }

    @Test
    fun setMirrorWritesSourcesThatEnsureUbuntuSourcesKeeps() {
        val root = tempFolder.newFolder("rootfs")
        MirrorSelector.setMirror(root, yandex)
        AptConfigurator.ensureUbuntuSources(root)
        assertEquals(yandex, MirrorSelector.status(root))
        File(root, "etc/apt/sources.list.d/ubuntu.sources").appendText("# mine\n")
        assertTrue(MirrorSelector.status(root).startsWith("custom sources"))
    }

    private fun listsOf(root: File) = File(root, "var/lib/apt/lists").apply { mkdirs() }

    @Test
    fun anotherServerDropsThePackageListsOfTheOldOne() {
        val root = tempFolder.newFolder("rootfs")
        MirrorSelector.setMirror(root, OFFICIAL)
        val packages = File(listsOf(root), "ports.ubuntu.com_ubuntu-ports_dists_noble_main_binary-arm64_Packages")
        val release = File(listsOf(root), "ports.ubuntu.com_ubuntu-ports_dists_noble_InRelease")
        packages.writeText("old")
        release.writeText("old")

        assertTrue(MirrorSelector.setMirror(root, OFFICIAL))
        assertTrue("the same server keeps its lists", packages.exists())

        assertTrue(MirrorSelector.setMirror(root, yandex))
        assertFalse("so that the apt wrapper fetches the new server's", packages.exists())
        assertTrue(release.exists())
        assertEquals(yandex, MirrorSelector.status(root))
    }

    @Test
    fun whileAptRunsTheSourcesAndListsStay() {
        val root = tempFolder.newFolder("rootfs")
        MirrorSelector.setMirror(root, OFFICIAL)
        val packages = File(listsOf(root), "ports.ubuntu.com_ubuntu-ports_dists_noble_main_binary-arm64_Packages")
        packages.writeText("in use")

        FileLocks.tryLock(AptConfigurator.aptLockFiles(root))!!.use {
            assertFalse(MirrorSelector.setMirror(root, yandex))
        }

        assertEquals(OFFICIAL, MirrorSelector.status(root))
        assertTrue(packages.exists())
    }
}
