package org.cortex.terminal.session

import java.util.Locale

/** Pure decision/formatting helpers for "a long command finished" notifications. */
internal object CommandNotifications {
    const val DEFAULT_THRESHOLD_SECONDS = 30L

    /** Notify only for commands at least [thresholdSeconds] long, and only if the user is not looking at the app. */
    fun shouldNotify(enabled: Boolean, thresholdSeconds: Long, durationMs: Long, appInForeground: Boolean): Boolean {
        if (!enabled || appInForeground) return false
        return durationMs >= thresholdSeconds.coerceAtLeast(1L) * 1000L
    }

    fun parseThreshold(value: String?): Long =
        value?.toLongOrNull()?.takeIf { it > 0 } ?: DEFAULT_THRESHOLD_SECONDS

    /** `m:ss` or `h:mm:ss`, the same in every language. */
    fun formatElapsed(durationMs: Long): String {
        val total = (durationMs / 1000L).coerceAtLeast(0L)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.ROOT, "%d:%02d", m, s)
        }
    }

    fun isFailure(exitCode: Int?): Boolean = exitCode != null && exitCode != 0
}
