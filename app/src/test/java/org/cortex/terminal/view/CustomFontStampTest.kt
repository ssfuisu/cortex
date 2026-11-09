package org.cortex.terminal.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CustomFontStampTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun theStampChangesWhenTheFileIsAddedReplacedOrRemoved() {
        val font = java.io.File(tmp.root, "font.ttf")
        val missing = customFontStamp(font)
        assertEquals("none", missing)

        font.writeBytes(ByteArray(100))
        font.setLastModified(1_000_000L)
        val added = customFontStamp(font)
        assertNotEquals("a font copied in is loaded", missing, added)
        assertEquals("the same file keeps its stamp", added, customFontStamp(font))

        font.writeBytes(ByteArray(200))
        font.setLastModified(2_000_000L)
        assertNotEquals("a replaced font is loaded again", added, customFontStamp(font))

        font.delete()
        assertEquals(missing, customFontStamp(font))
    }
}
