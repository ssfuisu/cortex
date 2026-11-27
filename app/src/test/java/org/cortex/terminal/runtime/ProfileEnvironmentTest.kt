package org.cortex.terminal.runtime

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

    private fun writeEnvScript(): Pair<File, File> {
        val root = tempFolder.newFolder("cortex")
        BootstrapManager.ensureProfileEnvironment(root, null)
        return root to File(root, "etc/profile.d/00-env.sh")
    }

    @Test
    fun hookPreloadAndLibraryPathAreSetUnconditionally() {
        val (_, script) = writeEnvScript()
        val text = script.readText()
        assertTrue("LD_PRELOAD must be exported", text.contains("export LD_PRELOAD="))
        assertTrue("LD_LIBRARY_PATH must be exported", text.contains("export LD_LIBRARY_PATH="))
    }

    @Test
    fun sourcingProfileSetsRootAndHookPaths() {
        assumeTrue(File("/bin/bash").canExecute())
        val (root, script) = writeEnvScript()
        val process = ProcessBuilder(
            "/bin/bash", "-c",
            ". \"$1\"; echo \"preload=\${LD_PRELOAD:-none} root=\${CORTEX_ROOT:-none}\"",
            "_", script.absolutePath
        ).also { pb ->
            pb.environment().remove("CORTEX_ROOT")
            pb.environment().remove("LD_PRELOAD")
            pb.environment()["HOME"] = "/home/cortex"
        }.redirectErrorStream(true).start()
        assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        val out = process.inputStream.bufferedReader().readText().trim()
        assertTrue("unexpected output: $out", out.contains("root=${root.absolutePath}"))
        assertTrue("unexpected preload: $out", out.contains("preload=${root.absolutePath}/usr/lib/libcortex-hook.so"))
    }
}
