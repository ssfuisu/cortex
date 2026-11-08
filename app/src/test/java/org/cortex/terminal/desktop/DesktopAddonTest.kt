package org.cortex.terminal.desktop

import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopAddonTest {
    @Test
    fun desktopSizeFollowsTheScale() {
        // A 1080 x 2200 phone at density 2.75: 100 % makes one desktop pixel one dp
        assertEquals(Pair(392, 800), DesktopAddon.desktopSize(1080, 2200, 2.75f, 100))
        // smaller pixels fit more on the screen
        assertEquals(Pair(522, 1066), DesktopAddon.desktopSize(1080, 2200, 2.75f, 75))
        // never smaller than a device pixel, sizes stay even
        assertEquals(Pair(1080, 2200), DesktopAddon.desktopSize(1080, 2200, 0.5f, 100))
        assertEquals(Pair(320, 240), DesktopAddon.desktopSize(100, 100, 1f, 100))
    }

    @Test
    fun installCommand() {
        assertEquals("cortex-desktop install light", DesktopAddon.installCommand(DesktopAddon.KIND_LIGHT, false))
        assertEquals("cortex-desktop install xfce --firefox", DesktopAddon.installCommand(DesktopAddon.KIND_XFCE, true))
    }
}
