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
    fun ensureRootTools_installsFunctionalTsuFallbackAndDoesNotWriteNoOpSuStub() {
        val root = tempFolder.newFolder("root")

        ShellScriptsInstaller.ensureRootTools(root, null)

        val tsuFile = File(root, "usr/local/bin/tsu")
        val sudoFile = File(root, "usr/local/bin/sudo")
        val rootCmdFile = File(root, "usr/local/bin/root")
        val suFile = File(root, "usr/local/bin/su")

        assertTrue("tsu must be installed with functional fallback", tsuFile.exists())
        assertTrue(tsuFile.readText().contains("exec /usr/local/bin/su \"\$@\""))
        assertTrue("sudo must be installed with functional fallback", sudoFile.exists())
        assertTrue(sudoFile.readText().contains("exec /usr/local/bin/su \"\$@\""))
        assertTrue("root wrapper must be installed with functional fallback", rootCmdFile.exists())
        assertFalse("su must not be installed as a broken 1-line shebang stub when asset is absent", suFile.exists())
    }
}
