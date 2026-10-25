package org.cortex.terminal.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionLabelsTest {

    private val root = "/data/user/0/org.cortex.terminal/files/ubuntu"

    @Test
    fun aDirectoryOfTheRootfsIsShownAsTheShellSeesIt() {
        assertEquals("/home/work", SessionLabels.virtualPath("$root/home/work", root))
        assertEquals("/", SessionLabels.virtualPath(root, root))
        assertEquals("/", SessionLabels.virtualPath("$root/", "$root/"))
        assertEquals("/sdcard/Download", SessionLabels.virtualPath("/sdcard/Download", root))
        assertNull(SessionLabels.virtualPath(null, root))
    }

    @Test
    fun aDirectoryOfAnotherNameWithTheSamePrefixIsNotInTheRootfs() {
        assertEquals("$root-other/x", SessionLabels.virtualPath("$root-other/x", root))
    }

    @Test
    fun theHomeDirectoryIsATilde() {
        assertEquals("~", SessionLabels.shortPath("/home", "/home"))
        assertEquals("~/project/src", SessionLabels.shortPath("/home/project/src", "/home"))
        assertEquals("/etc", SessionLabels.shortPath("/etc", "/home"))
        assertEquals("/homework", SessionLabels.shortPath("/homework", "/home"))
    }

    @Test
    fun aTabIsNamedAfterTheLastDirectory() {
        assertEquals("~", SessionLabels.tabLabel("/home", "/home"))
        assertEquals("src", SessionLabels.tabLabel("/home/project/src", "/home"))
        assertEquals("/", SessionLabels.tabLabel("/", "/home"))
        assertEquals("etc", SessionLabels.tabLabel("/etc", "/home"))
        assertEquals("Download", SessionLabels.tabLabel("/sdcard/Download/", "/home"))
    }
}
