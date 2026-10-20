package org.cortex.terminal.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class ProfileEnvironmentTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun writeEnvScript(): File {
        val root = tempFolder.newFolder("cortex")
        BootstrapManager.ensureProfileEnvironment(root, null)
        return File(root, "etc/profile.d/00-env.sh")
    }

    @Test
    fun theHookIsOnlySetUpOutsideRootMode() {
        val text = writeEnvScript().readText()
        val guard = text.indexOf("if [ -n \"\${CORTEX_CHROOT:-}\" ] || [ ! -d /system/bin ]; then")
        val otherwise = text.indexOf("\nelse\n")
        val preload = text.indexOf("export LD_PRELOAD=")
        val libraryPath = text.indexOf("export LD_LIBRARY_PATH=")
        val end = text.indexOf("\nfi\n", otherwise)
        assertTrue("root mode check missing", guard >= 0)
        assertTrue("LD_PRELOAD must be set in the normal-mode branch", preload in otherwise..end)
        assertTrue("LD_LIBRARY_PATH must be set in the normal-mode branch", libraryPath in otherwise..end)
    }

    @Test
    fun inRootModeTheHookIsRemovedFromTheEnvironment() {
        assumeTrue(File("/bin/bash").canExecute())
        val script = writeEnvScript()
        // Set inside the shell: a real LD_PRELOAD of a missing file would make the dynamic linker
        // print a warning into the output.
        val process = ProcessBuilder(
            "/bin/bash", "-c",
            "export LD_PRELOAD=/somewhere/libcortex-hook.so CORTEX_ROOT=/data/user/0/org.cortex.terminal/files/cortex; " +
                ". \"$1\"; echo \"chroot=\${CORTEX_CHROOT:-} preload=\${LD_PRELOAD:-none} root=\${CORTEX_ROOT:-none}\"",
            "_", script.absolutePath
        ).also { pb ->
            pb.environment()["CORTEX_CHROOT"] = "1"
            pb.environment()["HOME"] = "/home"
        }.redirectErrorStream(true).start()
        assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        val out = process.inputStream.bufferedReader().readText().trim()
        assertEquals("chroot=1 preload=none root=none", out)
    }
}
