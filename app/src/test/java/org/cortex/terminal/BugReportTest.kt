package org.cortex.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BugReportTest {

    @Test
    fun format_writesSectionsWithKeysAndMarksMissingValues() {
        val text = BugReport.format(
            linkedMapOf(
                "Cortex" to listOf("version" to "1.26.37 (137)", "root mode" to "false"),
                "Ubuntu" to listOf("release" to null)
            )
        )
        assertEquals(
            "== Cortex\nversion: 1.26.37 (137)\nroot mode: false\n\n== Ubuntu\nrelease: (unknown)\n\n",
            text
        )
    }

    @Test
    fun osRelease_isThePrettyName() {
        val text = "NAME=\"Ubuntu\"\nVERSION_ID=\"24.04\"\nPRETTY_NAME=\"Ubuntu 24.04.3 LTS\"\nID=ubuntu\n"
        assertEquals("Ubuntu 24.04.3 LTS", BugReport.osRelease(text))
        assertEquals("Debian", BugReport.osRelease("PRETTY_NAME=Debian\n"))
        assertNull(BugReport.osRelease("NAME=Ubuntu\n"))
        assertNull(BugReport.osRelease(null))
    }

    @Test
    fun packageCount_countsOnlyInstalledPackages() {
        val status = """
            Package: bash
            Status: install ok installed
            Version: 5.2.21-2ubuntu4

            Package: removed-one
            Status: deinstall ok config-files

            Package: half
            Status: install ok half-installed

            Package: coreutils
            Status: install ok installed
        """.trimIndent()
        assertEquals(2, BugReport.packageCount(status))
        assertEquals(0, BugReport.packageCount(""))
    }
}
