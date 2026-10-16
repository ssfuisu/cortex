package org.cortex.terminal.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandNotificationsTest {

    @Test
    fun notifiesOnlyLongCommandsWhileTheAppIsInTheBackground() {
        assertTrue(CommandNotifications.shouldNotify(true, 30, 30_000, appInForeground = false))
        assertTrue(CommandNotifications.shouldNotify(true, 30, 600_000, appInForeground = false))
        assertFalse("shorter than the limit", CommandNotifications.shouldNotify(true, 30, 29_999, appInForeground = false))
        assertFalse("the user is looking at it", CommandNotifications.shouldNotify(true, 30, 600_000, appInForeground = true))
        assertFalse("switched off", CommandNotifications.shouldNotify(false, 30, 600_000, appInForeground = false))
    }

    @Test
    fun aZeroOrNegativeThresholdStillMeansAtLeastOneSecond() {
        assertFalse(CommandNotifications.shouldNotify(true, 0, 500, appInForeground = false))
        assertTrue(CommandNotifications.shouldNotify(true, 0, 1_000, appInForeground = false))
        assertFalse(CommandNotifications.shouldNotify(true, -5, 10, appInForeground = false))
    }

    @Test
    fun thresholdPreferenceParsing() {
        assertEquals(60L, CommandNotifications.parseThreshold("60"))
        assertEquals(30L, CommandNotifications.parseThreshold(null))
        assertEquals(30L, CommandNotifications.parseThreshold("abc"))
        assertEquals(30L, CommandNotifications.parseThreshold("0"))
        assertEquals(30L, CommandNotifications.parseThreshold("-3"))
    }

    @Test
    fun elapsedTimeIsLanguageNeutral() {
        assertEquals("0:00", CommandNotifications.formatElapsed(0))
        assertEquals("0:59", CommandNotifications.formatElapsed(59_999))
        assertEquals("1:00", CommandNotifications.formatElapsed(60_000))
        assertEquals("2:13", CommandNotifications.formatElapsed(133_000))
        assertEquals("1:02:03", CommandNotifications.formatElapsed(3_723_000))
        assertEquals("0:00", CommandNotifications.formatElapsed(-5))
    }

    @Test
    fun failureMeansANonZeroExitCode() {
        assertFalse(CommandNotifications.isFailure(0))
        assertFalse(CommandNotifications.isFailure(null))
        assertTrue(CommandNotifications.isFailure(1))
        assertTrue(CommandNotifications.isFailure(130))
    }
}
