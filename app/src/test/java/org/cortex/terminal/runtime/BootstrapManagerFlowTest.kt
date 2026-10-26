package org.cortex.terminal.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * The steps that set up and keep up the Ubuntu system, run with a real Context on the test machine's
 * file system: the first start (extraction and set-up), every later start, and the update after a
 * new release. The archive is a small stand-in made here with tar; the native extractor is not
 * loaded on the JVM, so the system tar the app falls back to does the extraction.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [ShadowJavaFilesOs::class])
class BootstrapManagerFlowTest {

    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var home: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        root = Environment.getCortexRoot(context)
        home = Environment.getHomeDir(context)
        root.deleteRecursively()
    }

    private fun put(path: String, text: String, base: File = root): File =
        File(base, path).apply {
            parentFile!!.mkdirs()
            writeText(text)
        }

    private fun read(path: String) = File(root, path).readText()

    private fun asset(name: String): ByteArray? = try {
        context.assets.open(name).use { it.readBytes() }
    } catch (e: FileNotFoundException) {
        null
    }

    private fun isLink(file: File) = Files.isSymbolicLink(file.toPath())

    private fun linkTarget(file: File) = Files.readSymbolicLink(file.toPath()).toString()

    /** The files of an extracted Ubuntu that the set-up steps start from. */
    private fun extractedSystem(base: File = root) {
        put("usr/bin/bash", "bash", base)
        put("usr/bin/apt", "apt", base)
        put("usr/bin/mawk", "mawk", base)
        put("usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1", "ld.so", base)
        put("usr/share/base-passwd/passwd.master", "root:*:0:0:root:/root:/bin/bash\nman:*:6:12:man:/var/cache/man:/usr/sbin/nologin\nwww-data:*:33:33:www-data:/var/www:/usr/sbin/nologin\n", base)
        put("usr/share/base-passwd/group.master", "root:*:0:\nadm:*:4:\nman:*:12:\nnogroup:*:65534:\n", base)
        put("etc/skel/.bashrc", "# the image's .bashrc\n", base)
        put("etc/skel/.profile", "# the image's .profile\n", base)
        put("etc/profile", "if [ \"`id -u`\" -eq 0 ]; then PS1='# '; fi\n", base)
        // Ubuntu's merged /usr: /bin, /sbin and /lib are links
        for ((link, target) in listOf("bin" to "usr/bin", "sbin" to "usr/sbin", "lib" to "usr/lib")) {
            File(base, target).mkdirs()
            Files.createSymbolicLink(File(base, link).toPath(), File(target).toPath())
        }
    }

    /** Every regular file and symlink under [dir] with what shows a change: inode, time, content. */
    private fun snapshot(dir: File): Map<String, String> {
        val out = sortedMapOf<String, String>()
        dir.walkTopDown().onEnter { !isLink(it) || it == dir }.forEach { f ->
            val path = f.toPath()
            val rel = f.relativeTo(dir).path
            when {
                Files.isSymbolicLink(path) -> out[rel] = "-> " + linkTarget(f)
                Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) -> {
                    val ino = Files.getAttribute(path, "unix:ino", LinkOption.NOFOLLOW_LINKS)
                    out[rel] = "$ino ${f.lastModified()} ${f.readBytes().contentHashCode()}"
                }
            }
        }
        return out
    }

    @Test
    fun initializeFileSystem_withoutABaseSystem_onlyCreatesItsDirectories() {
        BootstrapManager.initializeFileSystem(context)

        assertTrue(root.isDirectory)
        assertTrue(home.isDirectory)
        assertTrue(Environment.getTmpDir(context).isDirectory)
        assertFalse(File(root, "etc/passwd").exists())
        assertFalse(File(root, "usr/local/bin/apt").exists())
    }

    @Test
    fun initializeFileSystem_setsUpAnExtractedSystem() {
        extractedSystem()
        put("etc/hosts", "203.0.113.7 ports.ubuntu.com\n")

        BootstrapManager.initializeFileSystem(context)

        // /etc/profile works without the id program
        assertEquals("if [ \"\${EUID:-0}\" -eq 0 ]; then PS1='# '; fi\n", read("etc/profile"))
        // apt and apt-get fetch the package lists the first time they need them
        val wrapper = String(asset("scripts/apt-autoupdate.sh")!!)
        for (name in listOf("apt", "apt-get")) {
            val file = File(root, "usr/local/bin/$name")
            assertEquals(wrapper, file.readText())
            assertTrue(file.canExecute())
        }
        val hookAsset = if (android.os.Process.is64Bit()) "libcortex-hook-arm64.so" else "libcortex-hook-arm.so"
        val hook = File(root, "usr/lib/libcortex-hook.so")
        // The hook libraries are built by CI, a checkout alone has none
        asset(hookAsset)?.let { assertArrayEquals(it, hook.readBytes()) } ?: assertFalse(hook.exists())

        val resolvConf = read("etc/resolv.conf")
        assertTrue(resolvConf, resolvConf.contains("nameserver 8.8.8.8\n"))
        assertTrue(resolvConf, resolvConf.endsWith("options timeout:1 attempts:2 rotate\n"))
        // A hosts file that sent the Ubuntu servers elsewhere is replaced
        assertEquals(
            "127.0.0.1 localhost localhost.localdomain\n::1 localhost ip6-localhost ip6-loopback\n",
            read("etc/hosts")
        )
        assertTrue(read("etc/nsswitch.conf").contains("hosts:          files dns\n"))

        val passwd = read("etc/passwd").lines()
        assertEquals("root:x:0:0:root:${home.absolutePath}:/bin/bash", passwd[0])
        assertTrue(passwd.contains("cortex:x:0:0:Cortex:${home.absolutePath}:/bin/bash"))
        assertTrue(read("etc/group").startsWith("root:x:0:\n"))
        // The system users and groups of every Ubuntu, which packages' scripts use (chown redis:adm,
        // setpriv --reuid=man): once there, with locked shadow entries
        assertTrue(passwd.contains("man:x:6:12:man:/var/cache/man:/usr/sbin/nologin"))
        assertTrue(passwd.contains("www-data:x:33:33:www-data:/var/www:/usr/sbin/nologin"))
        assertTrue(read("etc/group").lines().containsAll(listOf("adm:x:4:", "man:x:12:", "nogroup:x:65534:")))
        assertTrue(read("etc/shadow").lines().contains("man:*:19700:0:99999:7:::"))
        assertTrue(read("etc/gshadow").lines().contains("adm:*::"))

        val bundle = File(root, "etc/ssl/certs/ca-certificates.crt")
        assertArrayEquals(asset("cacert.pem"), bundle.readBytes())
        for (alias in listOf("etc/ssl/cert.pem", "etc/ssl/ca-bundle.pem", "usr/lib/ssl/cert.pem")) {
            assertEquals(alias, bundle.absolutePath, linkTarget(File(root, alias)))
        }
        assertArrayEquals(
            asset("ubuntu-archive-keyring.gpg"),
            File(root, "usr/share/keyrings/ubuntu-archive-keyring.gpg").readBytes()
        )

        // The user's startup files come from the image's /etc/skel
        assertTrue(File(home, ".bashrc").readText().startsWith("# the image's .bashrc\n"))
        assertTrue(File(home, ".profile").readText().startsWith("# the image's .profile\n"))
        // ~/storage -> /sdcard is no longer made (deleting through it empties the shared storage)
        assertFalse(isLink(File(home, "storage")))

        assertEquals("mawk", read("usr/bin/awk"))
        assertTrue(File(root, "usr/bin/which").canExecute())
        for (dir in listOf("var/lib/apt/lists/partial", "var/cache/apt/archives/partial", "tmp", "opt")) {
            assertTrue(dir, File(root, dir).isDirectory)
        }
    }

    @Test
    fun initializeFileSystem_takesAwayTheOldStorageLinkOnceAndLeavesALaterOne() {
        extractedSystem()
        val link = File(home, "storage")
        home.mkdirs()
        Files.createSymbolicLink(link.toPath(), File("/sdcard").toPath())

        BootstrapManager.initializeFileSystem(context)

        assertFalse("the old link to /sdcard is gone", isLink(link))
        assertFalse("and nothing was made in its place", link.exists())

        // One that the user makes afterwards (ln -s /sdcard ~/storage) is theirs
        Files.createSymbolicLink(link.toPath(), File("/sdcard").toPath())
        BootstrapManager.initializeFileSystem(context)
        assertEquals("/sdcard", linkTarget(link))
    }

    @Test
    fun initializeFileSystem_leavesAStorageDirectoryAlone() {
        extractedSystem()
        val dir = File(home, "storage")
        File(dir, "mine").also { it.parentFile?.mkdirs(); it.writeText("kept") }

        BootstrapManager.initializeFileSystem(context)

        assertEquals("kept", File(dir, "mine").readText())
    }

    @Test
    fun initializeFileSystem_leavesAStorageLinkToAnotherPlaceAlone() {
        extractedSystem()
        val link = File(home, "storage")
        home.mkdirs()
        // Only the old link to exactly /sdcard is taken away; this one points elsewhere
        Files.createSymbolicLink(link.toPath(), File("/sdcard/Download").toPath())

        BootstrapManager.initializeFileSystem(context)

        assertEquals("/sdcard/Download", linkTarget(link))
    }

    @Test
    fun initializeFileSystem_aSecondTimeChangesNothing() {
        extractedSystem()
        BootstrapManager.initializeFileSystem(context)
        val before = snapshot(root)
        // File times have a resolution of a second on some file systems
        Thread.sleep(1100)

        BootstrapManager.initializeFileSystem(context)

        val after = snapshot(root)
        val changed = (before.keys + after.keys).filter { before[it] != after[it] }
            .map { "$it: ${before[it]} -> ${after[it]}" }
        assertEquals(emptyList<String>(), changed)
    }

    @Test
    fun updateInstalledSystem_bringsAnOlderSystemUpToDate() {
        extractedSystem()
        put(".cortex_version", "12600")
        val packages = put("var/lib/apt/lists/ports.ubuntu.com_ubuntu-ports_dists_noble_main_binary-arm64_Packages", "old")
        put("var/lib/apt/lists/partial/half-downloaded", "old")
        val release = put("var/lib/apt/lists/ports.ubuntu.com_ubuntu-ports_dists_noble_InRelease", "kept")
        assertTrue(BootstrapManager.needsUpdate(context))

        BootstrapManager.updateInstalledSystem(context)

        assertEquals(BootstrapManager.CURRENT_BOOTSTRAP_VERSION.toString(), read(".cortex_version"))
        assertFalse(BootstrapManager.needsUpdate(context))
        assertTrue(BootstrapManager.isBootstrapInstalled(context))
        // Package lists of the older release are fetched again
        assertFalse(packages.exists())
        assertEquals(0, File(root, "var/lib/apt/lists/partial").list()!!.size)
        assertEquals("kept", release.readText())
        assertTrue(read("etc/apt/sources.list.d/ubuntu.sources").contains("URIs: ${MirrorSelector.OFFICIAL}"))
    }

    @Test
    fun updateInstalledSystem_leavesThePackageListsAloneWhileAptRuns() {
        extractedSystem()
        put(".cortex_version", "12600")
        val packages = put("var/lib/apt/lists/ports.ubuntu.com_ubuntu-ports_dists_noble_main_binary-arm64_Packages", "in use")

        FileLocks.tryLock(AptConfigurator.aptLockFiles(root))!!.use {
            BootstrapManager.updateInstalledSystem(context)
        }

        assertEquals("in use", packages.readText())
        assertEquals(BootstrapManager.CURRENT_BOOTSTRAP_VERSION.toString(), read(".cortex_version"))
    }

    @Test
    fun updateInstalledSystem_onACurrentSystemDoesNothing() {
        extractedSystem()
        put(".cortex_version", BootstrapManager.CURRENT_BOOTSTRAP_VERSION.toString())
        val packages = put("var/lib/apt/lists/ports.ubuntu.com_ubuntu-ports_dists_noble_main_binary-arm64_Packages", "current")
        val before = snapshot(root)

        BootstrapManager.updateInstalledSystem(context)

        assertEquals("current", packages.readText())
        assertEquals(before, snapshot(root))
    }

    /** A gzip-compressed tar of [stage], made with the system tar like the release build does. */
    private fun archive(stage: File): File {
        val tgz = File(context.cacheDir, "test-bootstrap.tar.gz")
        val process = ProcessBuilder("tar", "-czf", tgz.absolutePath, "-C", stage.absolutePath, ".")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(output, 0, process.waitFor())
        return tgz
    }

    @Test(timeout = 120_000)
    fun installBootstrap_extractsAndSetsUpTheSystem() {
        val stage = File(context.cacheDir, "stage")
        extractedSystem(stage)
        put("home/ubuntu/.bashrc", "# the image's user\n", stage)
        Files.createSymbolicLink(File(stage, "usr/bin/vi").toPath(), File("/usr/bin/mawk").toPath())
        val tgz = archive(stage)
        val progress = mutableListOf<Int>()

        assertTrue(BootstrapManager.installBootstrap(context, { tgz.inputStream() }) { progress.add(it) })

        assertTrue(BootstrapManager.isBootstrapInstalled(context))
        assertFalse(BootstrapManager.needsUpdate(context))
        assertEquals("bash", read("usr/bin/bash"))
        assertEquals("usr/bin", linkTarget(File(root, "bin")))
        // Absolute links point into the Ubuntu system, not to the phone's own /usr
        assertEquals("mawk", linkTarget(File(root, "usr/bin/vi")))
        assertFalse(File(root, "home/ubuntu").exists())
        // Set up like on every start
        assertTrue(File(root, "usr/local/bin/apt").isFile)
        assertTrue(File(home, ".bashrc").readText().startsWith("# the image's .bashrc\n"))
        assertTrue(File(root, "var/lib/dpkg/status").isFile)
        assertFalse(File(context.cacheDir, "bootstrap.tar").exists())
        // Only the native extractor reports progress; it never goes back
        assertEquals(progress.sorted(), progress)
    }

    @Test(timeout = 120_000)
    fun installBootstrap_withoutADynamicLinkerFails() {
        val stage = File(context.cacheDir, "stage")
        extractedSystem(stage)
        File(stage, "usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1").delete()
        val tgz = archive(stage)

        assertFalse(BootstrapManager.installBootstrap(context, { tgz.inputStream() }))

        assertFalse(File(root, ".cortex_version").exists())
        assertFalse(BootstrapManager.isBootstrapInstalled(context))
    }

    @Test
    fun progressInputStream_reportsEveryPercentOnceUpTo100() {
        val data = ByteArray(1000) { it.toByte() }
        val reported = mutableListOf<Int>()
        ProgressInputStream(data.inputStream(), data.size.toLong()) { reported.add(it) }.use { input ->
            assertEquals(0, input.read())
            val buffer = ByteArray(7)
            while (input.read(buffer) >= 0) Unit
        }
        assertEquals(reported.distinct(), reported)
        assertEquals(reported.sorted(), reported)
        assertEquals(100, reported.last())
    }
}
