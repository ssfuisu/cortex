package org.cortex.terminal.view

import org.junit.Assert.assertEquals
import org.junit.Test

class PinchTextSizeTest {

    @Test
    fun aPinchChangesTheSizeInWholeSp() {
        assertEquals(11f, pinchTextSize(11f, 1.02f))
        assertEquals("spreading the fingers to 1.5x", 17f, pinchTextSize(11f, 1.5f))
        assertEquals("pinching to 0.8x", 10f, pinchTextSize(12f, 0.8f))
    }

    @Test
    fun theSizeStaysReadable() {
        assertEquals(MIN_PINCH_TEXT_SP, pinchTextSize(10f, 0.1f))
        assertEquals(MAX_PINCH_TEXT_SP, pinchTextSize(18f, 5f))
    }
}
