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

    @Test
    fun ensureShellHelpers_installsProfileDScriptWithReloadFunction() {
        val root = tempFolder.newFolder("root")

        ShellScriptsInstaller.ensureShellHelpers(root, null)

        val script = File(root, "etc/profile.d/02-cortex-shell.sh")
        assertTrue("02-cortex-shell.sh must be installed in /etc/profile.d", script.isFile)
        val text = script.readText()
        assertTrue(text.contains("reload()"))
        assertTrue("it must be bash-only", text.contains("BASH_VERSION"))
        assertFalse("it must not touch the user's dotfiles", text.contains(">> \"\$HOME/.bashrc\""))
    }

    @Test
    fun isLegacyAutoLauncher_matchesOnlySmallWrapperScripts() {
        val dir = tempFolder.newFolder("bin")

        val wrapper = File(dir, "opencode").apply {
            writeText("#!/bin/bash\necho \" OpenCode CLI is not yet installed.\"\n")
        }
        assertTrue(ShellScriptsInstaller.isLegacyAutoLauncher(wrapper))

        // The real tool mentions its own URL; that alone must never get it deleted.
        val realBinary = File(dir, "opencode-real").apply {
            writeBytes("\u007fELF".toByteArray() + ByteArray(200_000) + "https://opencode.ai/update".toByteArray())
        }
        assertFalse(ShellScriptsInstaller.isLegacyAutoLauncher(realBinary))

        val scriptWithUrl = File(dir, "user-script").apply {
            writeText("#!/bin/sh\ncurl -fsSL https://opencode.ai/v2/install | bash\n")
        }
        assertFalse(ShellScriptsInstaller.isLegacyAutoLauncher(scriptWithUrl))

        assertFalse(ShellScriptsInstaller.isLegacyAutoLauncher(File(dir, "missing")))
    }

    @Test
    fun cleanupAutoLaunchers_keepsInstalledToolAndRemovesOldWrapper() {
        val root = tempFolder.newFolder("root")
        val localBin = File(root, "usr/local/bin").apply { mkdirs() }
        val tool = File(localBin, "opencode").apply {
            writeBytes("\u007fELF".toByteArray() + "https://opencode.ai".toByteArray())
        }
        val wrapper = File(localBin, "muse").apply {
            writeText("#!/bin/bash\necho \" Meta Muse Code CLI is not yet installed.\"\n")
        }

        BootstrapManager.cleanupAutoLaunchers(root)

        assertTrue("an installed opencode must survive the cleanup", tool.exists())
        assertFalse("the old auto-install wrapper must be removed", wrapper.exists())
    }

    @Test
    fun ensureBackupTool_installsAnExecutableCommand() {
        val root = tempFolder.newFolder("root")

        ShellScriptsInstaller.ensureBackupTool(root, null)

        val tool = File(root, "usr/local/bin/cortex-backup")
        assertTrue("cortex-backup must be installed in /usr/local/bin", tool.isFile)
        assertTrue(tool.readText().startsWith("#!/bin/bash"))
        assertTrue("it must be executable", tool.canExecute())
    }
}
