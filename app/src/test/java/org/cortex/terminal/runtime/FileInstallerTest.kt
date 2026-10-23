package org.cortex.terminal.runtime

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files

class FileInstallerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun install_writesNewFilesAndLeavesUnchangedOnesAlone() {
        val dir = tempFolder.newFolder("etc")
        val passwd = File(dir, "sub/passwd")

        assertTrue("a missing file is written", FileInstaller.install(passwd, "root:x:0:0\n", 420))
        assertEquals("root:x:0:0\n", passwd.readText())

        passwd.setLastModified(1_000_000_000L)
        val inode = Files.getAttribute(passwd.toPath(), "unix:ino")
        assertFalse("the same content is not written again", FileInstaller.install(passwd, "root:x:0:0\n", 420))
        assertEquals("not touched", 1_000_000_000L, passwd.lastModified())
        assertEquals("the same file", inode, Files.getAttribute(passwd.toPath(), "unix:ino"))

        assertTrue("other content is written", FileInstaller.install(passwd, "root:x:0:0\ncortex:x:0:0\n", 420))
        assertEquals("root:x:0:0\ncortex:x:0:0\n", passwd.readText())
        assertTrue("no temporary file is left", dir.resolve("sub").list()!!.toList() == listOf("passwd"))
    }

    @Test
    fun install_replacesTheOldFileInOneStep() {
        val dir = tempFolder.newFolder("etc")
        val conf = File(dir, "resolv.conf").apply { writeText("nameserver 8.8.8.8\n") }
        // A reader that opened the old file keeps reading the old content: the file is replaced, not
        // truncated and written again.
        RandomAccessFile(conf, "r").use { reader ->
            FileInstaller.install(conf, "nameserver 1.1.1.1\n", 384)
            val old = ByteArray(reader.length().toInt())
            reader.readFully(old)
            assertEquals("nameserver 8.8.8.8\n", String(old))
        }
        assertEquals("nameserver 1.1.1.1\n", conf.readText())
    }

    @Test
    fun install_replacesASymlinkInsteadOfWritingThroughIt() {
        val dir = tempFolder.newFolder("root")
        val elsewhere = File(dir, "elsewhere").apply { writeText("keep") }
        val link = File(dir, "resolv.conf")
        Files.createSymbolicLink(link.toPath(), elsewhere.toPath())

        assertTrue(FileInstaller.install(link, "nameserver 1.1.1.1\n", 384))
        assertFalse("a regular file now", Files.isSymbolicLink(link.toPath()))
        assertEquals("nameserver 1.1.1.1\n", link.readText())
        assertEquals("the link's target is untouched", "keep", elsewhere.readText())
    }

    @Test
    fun install_bytes() {
        val dir = tempFolder.newFolder("lib")
        val lib = File(dir, "libcortex-hook.so")
        val bytes = byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 0, 1, 2)
        assertTrue(FileInstaller.install(lib, bytes, 493))
        assertArrayEquals(bytes, lib.readBytes())
        assertFalse(FileInstaller.install(lib, bytes.copyOf(), 493))
        assertTrue("a damaged copy is replaced", FileInstaller.install(lib.also { it.writeBytes(byteArrayOf(1)) }, bytes, 493))
        assertArrayEquals(bytes, lib.readBytes())
    }

    @Test
    fun symlink_createsKeepsAndReplaces() {
        val dir = tempFolder.newFolder("bin")
        val alias = File(dir, "sensible-browser")

        assertTrue(FileInstaller.symlink("xdg-open", alias))
        assertEquals("xdg-open", Files.readSymbolicLink(alias.toPath()).toString())
        assertFalse("an existing correct link is kept", FileInstaller.symlink("xdg-open", alias))

        assertTrue(FileInstaller.symlink("other", alias))
        assertEquals("other", Files.readSymbolicLink(alias.toPath()).toString())

        val regular = File(dir, "open").apply { writeText("#!/bin/sh\n") }
        assertTrue("a file in its place is replaced", FileInstaller.symlink("xdg-open", regular))
        assertTrue(Files.isSymbolicLink(regular.toPath()))
        assertEquals(setOf("sensible-browser", "open"), dir.list()!!.toSet())
    }

    @Test
    fun fileLocks_failWhileAnotherHolderHasOne() {
        val dir = tempFolder.newFolder("dpkg")
        val lock = File(dir, "lock")
        val frontend = File(dir, "lock-frontend")

        val first = FileLocks.tryLock(listOf(frontend, lock))
        assertNotNull("free locks are taken", first)
        assertNull("held locks are not", FileLocks.tryLock(listOf(lock)))
        first!!.close()

        val again = FileLocks.tryLock(listOf(frontend, lock, File(dir, "new/lock")))
        assertNotNull("released locks can be taken again, missing files are created", again)
        again!!.close()
    }
}
