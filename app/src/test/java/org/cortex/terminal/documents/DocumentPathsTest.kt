package org.cortex.terminal.documents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class DocumentPathsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun base(): File = tmp.newFolder("home")

    @Test
    fun docIdsRoundTrip() {
        val base = base()
        val file = File(base, "projects/app/main.c").apply { parentFile.mkdirs(); writeText("x") }

        assertEquals("home", DocumentPaths.docIdFor(base, base))
        assertEquals("home/projects/app/main.c", DocumentPaths.docIdFor(base, file))
        assertEquals(base, DocumentPaths.resolve(base, "home"))
        assertEquals(file.canonicalFile, DocumentPaths.resolve(base, "home/projects/app/main.c")!!.canonicalFile)
    }

    @Test
    fun malformedOrHostileIdsNeverResolve() {
        val base = base()
        for (id in listOf(
            null, "", "other", "home2/x", "/etc/passwd", "home/../etc", "home/a/../../b", "home//x",
            "home/./x", "home/", "home/a\u0000b", "../home/x", "home/..", "home/x/.."
        )) {
            assertNull("'$id' must not resolve", DocumentPaths.resolve(base, id))
        }
    }

    @Test
    fun symlinksLeadingOutsideAreNotInside() {
        val base = base()
        val outside = tmp.newFolder("outside")
        File(outside, "secret.txt").writeText("secret")
        Files.createSymbolicLink(File(base, "storage").toPath(), outside.toPath())
        Files.createSymbolicLink(File(base, "link-to-file").toPath(), File(outside, "secret.txt").toPath())
        File(base, "real").mkdirs()
        Files.createSymbolicLink(File(base, "alias").toPath(), File(base, "real").toPath())

        assertFalse(DocumentPaths.isInside(base, File(base, "storage")))
        assertNull(DocumentPaths.resolve(base, "home/storage"))
        assertNull(DocumentPaths.resolve(base, "home/storage/secret.txt"))
        assertNull(DocumentPaths.resolve(base, "home/link-to-file"))
        assertTrue("a link that stays inside is fine", DocumentPaths.isInside(base, File(base, "alias")))
        assertNotNull(DocumentPaths.resolve(base, "home/alias"))
    }

    @Test
    fun namesAreValidatedAndNeverOverwrite() {
        assertTrue(DocumentPaths.isValidName("notes.txt"))
        assertTrue(DocumentPaths.isValidName(".bashrc"))
        for (bad in listOf(null, "", ".", "..", "a/b", "a\u0000b", "x".repeat(256))) {
            assertFalse("'$bad' must be rejected", DocumentPaths.isValidName(bad))
        }

        val dir = tmp.newFolder("dir")
        assertEquals("a.txt", DocumentPaths.uniqueChild(dir, "a.txt").name)
        File(dir, "a.txt").writeText("1")
        assertEquals("a (1).txt", DocumentPaths.uniqueChild(dir, "a.txt").name)
        File(dir, "a (1).txt").writeText("2")
        assertEquals("a (2).txt", DocumentPaths.uniqueChild(dir, "a.txt").name)
        File(dir, "Makefile").writeText("")
        assertEquals("Makefile (1)", DocumentPaths.uniqueChild(dir, "Makefile").name)
    }

    @Test
    fun deletingNeverFollowsSymlinks() {
        val base = base()
        val outside = tmp.newFolder("sdcard")
        val precious = File(outside, "photos/holiday.jpg").apply { parentFile.mkdirs(); writeText("jpg") }
        Files.createSymbolicLink(File(base, "storage").toPath(), outside.toPath())
        val tree = File(base, "tree").apply { mkdirs() }
        File(tree, "f.txt").writeText("f")
        File(tree, "sub").mkdirs()
        File(tree, "sub/g.txt").writeText("g")
        Files.createSymbolicLink(File(tree, "sub/out").toPath(), outside.toPath())

        assertTrue(DocumentPaths.deleteTree(File(base, "storage")))
        assertTrue("deleting the link must not touch its target", precious.exists())

        assertTrue(DocumentPaths.deleteTree(tree))
        assertFalse(tree.exists())
        assertTrue("a link inside a deleted tree must not be followed either", precious.exists())
    }
}
