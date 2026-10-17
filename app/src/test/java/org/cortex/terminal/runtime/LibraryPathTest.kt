package org.cortex.terminal.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryPathTest {

    @Test
    fun multiarchDirectoriesComeBeforeThePlainLibDirectories() {
        val dirs = LibraryPath.DIRS
        // nearly every library lives in lib/<triplet>: ld.so must find it on the first directory it tries
        assertEquals("/lib/aarch64-linux-gnu", dirs[0])
        assertEquals("/usr/lib/aarch64-linux-gnu", dirs[1])
        assertTrue(dirs.indexOf("/lib/arm-linux-gnueabihf") < dirs.indexOf("/lib"))
        assertTrue(dirs.indexOf("/usr/lib/arm-linux-gnueabihf") < dirs.indexOf("/usr/lib"))
    }

    @Test
    fun everyDirectoryIsListedOnce() {
        assertEquals(LibraryPath.DIRS.size, LibraryPath.DIRS.toSet().size)
        assertEquals(13, LibraryPath.DIRS.size)
    }

    @Test
    fun forRootPrefixesEveryDirectory() {
        val path = LibraryPath.forRoot("/data/data/org.cortex.terminal/files/cortex")
        val entries = path.split(":")
        assertEquals(LibraryPath.DIRS.size, entries.size)
        entries.forEachIndexed { i, entry ->
            assertEquals("/data/data/org.cortex.terminal/files/cortex" + LibraryPath.DIRS[i], entry)
        }
    }

    @Test
    fun forRootAcceptsAShellVariable() {
        // 00-env.sh writes the value with the variable unexpanded
        val path = LibraryPath.forRoot("\$CORTEX_ROOT")
        assertTrue(path.startsWith("\$CORTEX_ROOT/lib/aarch64-linux-gnu:\$CORTEX_ROOT/usr/lib/aarch64-linux-gnu:"))
        assertTrue(path.endsWith("\$CORTEX_ROOT/lib/arm-linux-gnueabihf/systemd"))
    }
}
