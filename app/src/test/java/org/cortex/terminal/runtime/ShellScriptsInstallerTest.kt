package org.cortex.terminal.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ShellScriptsInstallerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test(expected = IllegalStateException::class)
    fun requireFunctionalScript_rejectsShebangOnlyStub() {
        ShellScriptsInstaller.requireFunctionalScript("service.sh", "#!/bin/bash\n")
    }

    @Test(expected = IllegalStateException::class)
    fun requireFunctionalScript_rejectsBlankAndCommentOnlyScript() {
        ShellScriptsInstaller.requireFunctionalScript("systemctl.sh", "#!/bin/sh\n# comment\n   \n")
    }

    @Test
    fun ensureReloadScripts_installsHybridSourceAndExecScript() {
        val root = tempFolder.newFolder("root")
        val home = tempFolder.newFolder("home")
        File(root, "usr/bin").mkdirs()
        File(root, "bin").mkdirs()
        File(home, ".local/bin").mkdirs()

        ShellScriptsInstaller.ensureReloadScripts(root, home, null)

        val usrBinReload = File(root, "usr/bin/reload")
        val binReload = File(root, "bin/reload")
        val localBinReload = File(home, ".local/bin/reload")

        for (f in listOf(usrBinReload, binReload, localBinReload)) {
            assertTrue("Reload script must exist at ${f.path}", f.exists())
            val content = f.readText()
            assertTrue("Reload script must detect sourced execution", content.contains("if (return 0 2>/dev/null); then"))
            assertTrue("Reload script must source .bashrc when sourced", content.contains(". \"\$HOME/.bashrc\""))
            assertTrue("Reload script must exec login shell when executed directly", content.contains("exec \"\${SHELL:-/bin/bash}\" -l"))
        }
    }

    @Test
    fun ensureRootTools_doesNotInstallOrKeepBrokenWrappersWhenSuIsAbsent() {
        val root = tempFolder.newFolder("root")
        val localBin = File(root, "usr/local/bin").apply { mkdirs() }
        File(localBin, "tsu").writeText("stale")
        File(localBin, "sudo").writeText("stale")
        File(localBin, "root").writeText("stale")

        ShellScriptsInstaller.ensureRootTools(root, null)

        val tsuFile = File(localBin, "tsu")
        val sudoFile = File(localBin, "sudo")
        val rootCmdFile = File(localBin, "root")
        val suFile = File(localBin, "su")

        assertFalse("su must not be installed as a broken 1-line shebang stub when asset is absent", suFile.exists())
        assertFalse("tsu must not exist when su is absent", tsuFile.exists())
        assertFalse("sudo must not exist when su is absent", sudoFile.exists())
        assertFalse("root wrapper must not exist when su is absent", rootCmdFile.exists())
    }

    @Test
    fun ensureRootTools_installsFunctionalWrappersWhenSuExists() {
        val root = tempFolder.newFolder("root")
        val localBin = File(root, "usr/local/bin").apply { mkdirs() }
        val suFile = File(localBin, "su").apply {
            writeText("#!/bin/sh\nexec /bin/bash \"\$@\"\n")
            setExecutable(true, true)
        }

        ShellScriptsInstaller.ensureRootTools(root, null)

        val tsuFile = File(localBin, "tsu")
        val sudoFile = File(localBin, "sudo")
        val rootCmdFile = File(localBin, "root")

        assertTrue("su must still exist", suFile.exists())
        assertTrue("tsu must be installed when su exists", tsuFile.exists())
        assertTrue(tsuFile.readText().contains("exec /usr/local/bin/su \"\$@\""))
        assertTrue("sudo must be installed when su exists", sudoFile.exists())
        assertTrue(sudoFile.readText().contains("exec /usr/local/bin/su \"\$@\""))
        assertTrue("root wrapper must be installed when su exists", rootCmdFile.exists())
        assertTrue(rootCmdFile.readText().contains("exec /usr/local/bin/su \"\$@\""))
    }

    @Test
    fun ensureMachineId_replacesEmptyDbusMachineIdFile() {
        val root = tempFolder.newFolder("root")
        val dbusDir = File(root, "var/lib/dbus").apply { mkdirs() }
        val emptyDbusMachineId = File(dbusDir, "machine-id").apply { writeText("") }

        BootstrapManager.ensureMachineId(root)

        val etcMachineId = File(root, "etc/machine-id")
        assertTrue("etc/machine-id must be created with >= 32 chars", etcMachineId.exists() && etcMachineId.length() >= 32L)
        val isSymlink = ElfLinkerPatcher.isSymlink(emptyDbusMachineId)
        assertTrue(
            "var/lib/dbus/machine-id must be replaced with symlink to /etc/machine-id or populated with valid 32-char ID",
            isSymlink || emptyDbusMachineId.length() >= 32L
        )
    }
}
