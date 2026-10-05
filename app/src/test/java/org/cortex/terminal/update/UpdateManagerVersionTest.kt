package org.cortex.terminal.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManagerVersionTest {

    @Test
    fun testIsNewerVersion() {
        assertTrue(UpdateManager.isNewerVersion("1.25.23", "1.25.26"))
        assertTrue(UpdateManager.isNewerVersion("v1.25.23", "v1.25.26"))
        assertTrue(UpdateManager.isNewerVersion("1.24.99", "1.25.0"))
        assertTrue(UpdateManager.isNewerVersion("1.0", "1.0.1"))

        assertFalse(UpdateManager.isNewerVersion("1.25.26", "1.25.26"))
        assertFalse(UpdateManager.isNewerVersion("1.25.26", "1.25.23"))
        assertFalse(UpdateManager.isNewerVersion("2.0.0", "1.9.9"))
    }
}
