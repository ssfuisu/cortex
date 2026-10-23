package org.cortex.terminal

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.cortex.terminal.runtime.BootstrapManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Ubuntu on a real phone: the base system is unpacked from the APK and set up as on the app's first
 * start, then commands run in a login shell as in a terminal session. Runs on Firebase Test Lab
 * (.github/workflows/device-test.yml) or on a connected phone with
 * "./gradlew connectedDebugAndroidTest" (the APK needs the bootstrap archives that CI builds).
 */
@RunWith(AndroidJUnit4::class)
class UbuntuOnDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUpUbuntu() {
        if (!BootstrapManager.isBootstrapInstalled(context)) {
            assertTrue("the base system was not installed", BootstrapManager.installBootstrapFromAssets(context))
        }
        BootstrapManager.updateInstalledSystem(context)
    }

    /** Runs [command] in a login shell, like a terminal session; its exit status and output. */
    private fun shell(command: String): Pair<Int, String> {
        val out = StringBuilder()
        val status = BootstrapManager.runBackgroundCommand(context, command) { out.append(it) }
        return status to out.toString().replace("\r\n", "\n")
    }

    private fun assertOutput(expected: String, command: String) {
        val (status, out) = shell(command)
        assertEquals("exit status of: $command\n$out", 0, status)
        assertTrue("\"$expected\" not in the output of: $command\n$out", out.contains(expected))
    }

    @Test
    fun theShellIsUbuntusBashAsRoot() {
        val arch = if (android.os.Process.is64Bit()) "arm64" else "armhf"
        assertOutput(
            "uid=0 24.04 $arch",
            ". /etc/os-release; echo \"uid=\$(id -u) \$VERSION_ID \$(dpkg --print-architecture)\""
        )
    }

    @Test
    fun pipesSignalsAndSubshells() {
        assertOutput("100000", "seq 1 100000 | sort -rn | head -n 1")
        assertOutput("status 124", "timeout 1 sleep 5; echo \"status \$?\"")
        assertOutput("sub ok", "(cd / && echo \"sub \$(echo ok)\")")
    }

    @Test
    fun filesLinksAndFifos() {
        assertOutput(
            "files ok",
            "d=\$(mktemp -d) && cd \"\$d\" && echo one > a && mv a b && ln -s b s && " +
                "[ \"\$(readlink s)\" = b ] && [ \"\$(cat s)\" = one ] && mkfifo p && [ -p p ] && " +
                "cd / && rm -rf \"\$d\" && echo files ok"
        )
        // Hard links are emulated: ln and stat, separate processes, agree on the link
        assertOutput(
            "links 2 1",
            "d=\$(mktemp -d) && cd \"\$d\" && echo one > a && ln a b && n=\$(stat -c %h a) && " +
                "[ \"\$(stat -c %i a)\" = \"\$(stat -c %i b)\" ] && rm b && echo \"links \$n \$(stat -c %h a)\""
        )
        // and link() and stat() in one process too
        assertOutput(
            "links=2 same inode",
            "d=\$(mktemp -d) && cd \"\$d\" && echo one > a && perl -e 'link(\"a\", \"b\") or die \"link: \$!\\n\"; " +
                "my @a = stat(\"a\"); my @b = stat(\"b\"); " +
                "print \"links=\$a[3] \", (\$a[1] == \$b[1] ? \"same\" : \"different\"), \" inode\\n\"'"
        )
    }

    @Test
    fun aptInstallsAPackageFromUbuntusArchive() {
        // The app comes without package lists: apt-get fetches them first by itself
        assertOutput("Hello, world!", "apt-get install -y --no-install-recommends hello && hello")
    }
}
