package org.cortex.terminal.runtime

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.zip.GZIPOutputStream

class DevPackagesTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun stanza(name: String, version: String, status: String = "install ok installed", extra: String = "") =
        "Package: $name\nStatus: $status\nArchitecture: arm64\nVersion: $version\n$extra"

    private val libc = stanza("libc6", "2.39-0ubuntu8.9", "hold ok installed")
    private val bash = stanza("bash", "5.2.21-2ubuntu4")

    private fun overlayStanzas() = DevPackages.PACKAGES.joinToString("\n") {
        stanza(it, "2.39-0ubuntu8.9", extra = "Depends: libc6 (>= 2.39)\n")
    }

    private fun newRoot(status: String? = "$libc\n$bash\n"): File {
        val root = tempFolder.newFolder()
        if (status != null) {
            File(root, "var/lib/dpkg").mkdirs()
            File(root, "var/lib/dpkg/status").writeText(status)
        }
        return root
    }

    private fun gz(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    /** What the real extractor does: put the overlay's files into the root file system. */
    private class FakeExtractor(private val stanzas: String, private val works: Boolean = true) {
        var calls = 0
        var seen: ByteArray? = null

        fun extract(tar: File, root: File): Boolean {
            calls++
            seen = tar.readBytes()
            File(root, "usr/include").mkdirs()
            File(root, "usr/include/stdio.h").writeText("/* header */\n")
            File(root, DevPackages.STATUS_RESOURCE).apply { parentFile!!.mkdirs() }.writeText(stanzas)
            // a broken extractor has already written most of the files (the stanzas too) when it gives up
            return works
        }
    }

    private fun install(
        root: File,
        extractor: FakeExtractor,
        overlay: ByteArray? = gz("fake tar".toByteArray())
    ): DevPackages.Outcome = DevPackages.ensureInstalled(
        root = root,
        tmpDir = File(root, "cache"),
        openOverlay = { overlay?.let { ByteArrayInputStream(it) as InputStream } },
        extract = extractor::extract
    )

    @Test
    fun installsTheOverlayAndRegistersEveryPackage() {
        val root = newRoot()
        val extractor = FakeExtractor(overlayStanzas())

        val outcome = install(root, extractor)

        assertEquals(outcome.toString(), DevPackages.Result.INSTALLED, outcome.result)
        val status = File(root, "var/lib/dpkg/status").readText()
        for (name in DevPackages.PACKAGES) assertTrue("$name must be registered", DevPackages.isRegistered(status, name))
        assertEquals("2.39-0ubuntu8.9", DevPackages.installedVersion(status, "libc6-dev"))
        assertTrue("existing stanzas stay", status.startsWith(libc))
        assertTrue(status.contains("Package: bash"))
        assertTrue(File(root, "usr/include/stdio.h").exists())
        assertTrue(File(root, DevPackages.DONE_MARKER).exists())
        assertFalse("the temporary tar must be removed", File(root, "cache/devpkgs.tar").exists())
        assertFalse("no half-written status may remain", File(root, "var/lib/dpkg/status.cortex-new").exists())
        assertTrue("the status file ends like dpkg's: a blank line after the last stanza", status.endsWith("\n\n"))
    }

    @Test
    fun theExtractorGetsTheDecompressedOverlay() {
        val root = newRoot()
        val payload = ByteArray(5000) { (it * 7).toByte() }
        val extractor = FakeExtractor(overlayStanzas())

        install(root, extractor, gz(payload))

        assertArrayEquals(payload, extractor.seen)
    }

    @Test
    fun anOlderGlibc2_39LibcIsFine() {
        // the overlay's Depends say "libc6 (>= 2.39)", so an installation from before the last security update qualifies
        val root = newRoot(stanza("libc6", "2.39-0ubuntu8.5", "hold ok installed") + "\n")

        val outcome = install(root, FakeExtractor(overlayStanzas()))

        assertEquals(DevPackages.Result.INSTALLED, outcome.result)
    }

    @Test
    fun runningItAgainDoesNothing() {
        val root = newRoot()
        val extractor = FakeExtractor(overlayStanzas())
        install(root, extractor)
        val before = File(root, "var/lib/dpkg/status").readText()

        val again = install(root, extractor)

        assertEquals(DevPackages.Result.ALREADY_DONE, again.result)
        assertEquals(1, extractor.calls)
        assertEquals(before, File(root, "var/lib/dpkg/status").readText())
    }

    @Test
    fun aUserWhoRemovedThePackagesIsNotOverruled() {
        val root = newRoot()
        val extractor = FakeExtractor(overlayStanzas())
        install(root, extractor)
        // apt remove --purge libc6-dev ...: the stanzas are gone, the marker stays
        File(root, "var/lib/dpkg/status").writeText("$libc\n")

        val again = install(root, extractor)

        assertEquals(DevPackages.Result.ALREADY_DONE, again.result)
        assertFalse(DevPackages.isRegistered(File(root, "var/lib/dpkg/status").readText(), "libc6-dev"))
    }

    @Test
    fun leavesAnInstallationAloneThatHasThePackagesAlready() {
        val root = newRoot("$libc\n${stanza("libc6-dev", "2.39-0ubuntu8.9")}\n")
        val extractor = FakeExtractor(overlayStanzas())

        val outcome = install(root, extractor)

        assertEquals(DevPackages.Result.NOT_NEEDED, outcome.result)
        assertEquals(0, extractor.calls)
        assertTrue("not asked again at the next start", File(root, DevPackages.DONE_MARKER).exists())
    }

    @Test
    fun aDifferentGlibcSeriesIsNotTouched() {
        val root = newRoot(stanza("libc6", "2.35-0ubuntu3.8", "hold ok installed") + "\n")
        val extractor = FakeExtractor(overlayStanzas())

        val outcome = install(root, extractor)

        assertEquals(DevPackages.Result.NOT_POSSIBLE, outcome.result)
        assertEquals(0, extractor.calls)
        assertFalse(File(root, DevPackages.DONE_MARKER).exists())
    }

    @Test
    fun withoutLibcOrDatabaseNothingHappens() {
        val noLibc = newRoot("$bash\n")
        val noDatabase = newRoot(null)
        val extractor = FakeExtractor(overlayStanzas())

        assertEquals(DevPackages.Result.NOT_POSSIBLE, install(noLibc, extractor).result)
        assertEquals(DevPackages.Result.NOT_POSSIBLE, install(noDatabase, extractor).result)
        assertEquals(0, extractor.calls)
    }

    @Test
    fun aBuildWithoutTheOverlayLeavesTheDatabaseAlone() {
        val root = newRoot()
        val before = File(root, "var/lib/dpkg/status").readText()

        val outcome = install(root, FakeExtractor(overlayStanzas()), overlay = null)

        assertEquals(DevPackages.Result.NOT_POSSIBLE, outcome.result)
        assertEquals(before, File(root, "var/lib/dpkg/status").readText())
        assertFalse(File(root, DevPackages.DONE_MARKER).exists())
    }

    @Test
    fun aFailedUnpackRegistersNothingAndIsRetried() {
        val root = newRoot()
        val before = File(root, "var/lib/dpkg/status").readText()

        val failed = install(root, FakeExtractor(overlayStanzas(), works = false))

        assertEquals(DevPackages.Result.FAILED, failed.result)
        assertEquals(before, File(root, "var/lib/dpkg/status").readText())
        assertFalse(File(root, DevPackages.DONE_MARKER).exists())
        assertFalse(File(root, "cache/devpkgs.tar").exists())

        val retry = install(root, FakeExtractor(overlayStanzas()))
        assertEquals(DevPackages.Result.INSTALLED, retry.result)
    }

    @Test
    fun anExceptionInTheExtractorIsReportedNotThrown() {
        val root = newRoot()

        val outcome = DevPackages.ensureInstalled(
            root = root,
            tmpDir = File(root, "cache"),
            openOverlay = { ByteArrayInputStream(gz(ByteArray(10))) },
            extract = { _, _ -> throw IllegalStateException("disk full") }
        )

        assertEquals(DevPackages.Result.FAILED, outcome.result)
        assertTrue(outcome.detail.contains("disk full"))
    }

    @Test
    fun anOverlayWithoutStanzasIsAFailure() {
        val root = newRoot()
        val before = File(root, "var/lib/dpkg/status").readText()

        val outcome = DevPackages.ensureInstalled(
            root = root,
            tmpDir = File(root, "cache"),
            openOverlay = { ByteArrayInputStream(gz(ByteArray(10))) },
            extract = { _, _ -> true }
        )

        assertEquals(DevPackages.Result.FAILED, outcome.result)
        assertEquals(before, File(root, "var/lib/dpkg/status").readText())
    }

    @Test
    fun waitsForARunningDpkg() {
        val root = newRoot()
        val extractor = FakeExtractor(overlayStanzas())
        File(root, "var/lib/dpkg/lock-frontend").writeText("")
        val dpkg = RandomAccessFile(File(root, "var/lib/dpkg/lock-frontend"), "rw")
        val held = dpkg.channel.lock()
        try {
            val busy = install(root, extractor)

            assertEquals(DevPackages.Result.BUSY, busy.result)
            assertEquals(0, extractor.calls)
            assertFalse(DevPackages.isRegistered(File(root, "var/lib/dpkg/status").readText(), "libc6-dev"))
        } finally {
            held.release()
            dpkg.close()
        }

        assertEquals(DevPackages.Result.INSTALLED, install(root, extractor).result)
    }

    @Test
    fun theLocksAreReleasedAfterwards() {
        val root = newRoot()
        install(root, FakeExtractor(overlayStanzas()))

        for (name in listOf("lock-frontend", "lock")) {
            RandomAccessFile(File(root, "var/lib/dpkg/$name"), "rw").use { raf ->
                val lock = raf.channel.tryLock()
                assertTrue("$name must be free again", lock != null)
                lock!!.release()
            }
        }
    }

    // --- the status file text ------------------------------------------------------------------------

    @Test
    fun mergeAppendsOnlyUnknownPackages() {
        val current = "${stanza("a", "1")}\n${stanza("libc6-dev", "2.39-0ubuntu8.5")}\n"
        val additions = overlayStanzas()

        val merged = DevPackages.mergeStatus(current, additions)!!

        assertEquals("the installed libc6-dev keeps its version", "2.39-0ubuntu8.5", DevPackages.installedVersion(merged, "libc6-dev"))
        for (name in DevPackages.PACKAGES) {
            assertEquals(name, 1, Regex("^Package: ${Regex.escape(name)}$", RegexOption.MULTILINE).findAll(merged).count())
        }
        assertTrue(merged.startsWith(current.trimEnd('\n')))
    }

    @Test
    fun mergeHasNothingToDoWhenEverythingIsKnown() {
        val current = overlayStanzas() + "\n"

        assertNull(DevPackages.mergeStatus(current, overlayStanzas()))
    }

    @Test
    fun mergeSeparatesStanzasWithOneBlankLineWhateverTheFileEndedWith() {
        val additions = overlayStanzas()
        for (ending in listOf("", "\n", "\n\n", "\n\n\n")) {
            val merged = DevPackages.mergeStatus(stanza("a", "1").trimEnd('\n') + ending, additions)!!
            assertFalse("no run of blank lines for ending ${ending.length}", merged.contains("\n\n\n"))
            assertTrue(merged.endsWith("\n\n"))
            assertEquals("a", Regex("^Package: (.*)$", RegexOption.MULTILINE).find(merged)!!.groupValues[1])
        }
    }

    @Test
    fun installedVersionOnlyCountsInstalledPackages() {
        val status = listOf(
            stanza("held", "1", "hold ok installed"),
            stanza("normal", "2"),
            stanza("removed", "3", "deinstall ok not-installed"),
            stanza("broken", "4", "install ok half-installed"),
            stanza("unpacked", "5", "install ok unpacked"),
            stanza("config", "6", "deinstall ok config-files")
        ).joinToString("\n") + "\n"

        assertEquals("1", DevPackages.installedVersion(status, "held"))
        assertEquals("2", DevPackages.installedVersion(status, "normal"))
        assertNull(DevPackages.installedVersion(status, "removed"))
        assertNull(DevPackages.installedVersion(status, "broken"))
        assertNull(DevPackages.installedVersion(status, "unpacked"))
        assertNull(DevPackages.installedVersion(status, "config"))
        assertNull(DevPackages.installedVersion(status, "missing"))
    }

    @Test
    fun theAssetNameFollowsTheAbi() {
        assertEquals("devpkgs-arm64.tar.gz", DevPackages.assetName(true))
        assertEquals("devpkgs-arm.tar.gz", DevPackages.assetName(false))
    }
}
