package org.cortex.terminal.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TzifGeneratorTest {

    @Test
    fun testCreateTzifHeaderAndMagic() {
        val bytes = TzifGenerator.createTzifBytes(10800, "TRT", "TRT-3")
        assertTrue(bytes.size > 20)
        // Check magic "TZif2"
        val magic = String(bytes.copyOfRange(0, 5), Charsets.US_ASCII)
        assertEquals("TZif2", magic)
    }
}
