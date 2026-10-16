package org.cortex.terminal.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.preference.PreferenceManager
import org.cortex.terminal.CortexApp
import org.cortex.terminal.LocaleHelper
import org.cortex.terminal.MainActivity
import org.cortex.terminal.R
import java.util.concurrent.atomic.AtomicInteger

/**
 * What terminal programs can ask the Android side to do through escape sequences, no sockets or
 * extra permissions involved:
 *  - OSC 52: put text on the clipboard (tmux/vim/ssh over a remote shell).
 *  - OSC 133 (sent by the prompt hook in /etc/profile.d/02-cortex-shell.sh): notify when a long
 *    command finishes while the app is in the background.
 */
object TerminalBridge {
    private const val TAG = "TerminalBridge"
    const val PREF_ALLOW_CLIPBOARD = "allow_osc52"
    const val PREF_NOTIFY = "notify_long_commands"
    const val PREF_THRESHOLD = "long_command_threshold"
    private const val CHANNEL_ID = "cortex_commands"
    private const val NOTIFICATION_BASE_ID = 2000
    private const val NOTIFICATION_SLOTS = 20

    private val mainHandler = Handler(Looper.getMainLooper())
    private val nextSlot = AtomicInteger(0)

    /** OSC 52. Runs on the PTY reader thread, so the clipboard is touched on the main thread. */
    fun onClipboardRequest(context: Context, text: String) {
        val app = context.applicationContext
        val allowed = try {
            PreferenceManager.getDefaultSharedPreferences(app).getBoolean(PREF_ALLOW_CLIPBOARD, true)
        } catch (_: Exception) {
            true
        }
        if (!allowed) return
        mainHandler.post {
            try {
                val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return@post
                cm.setPrimaryClip(ClipData.newPlainText("Cortex", text))
                Toast.makeText(app, LocaleHelper.wrap(app).getString(R.string.clipboard_set_by_program), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.w(TAG, "Could not set clipboard", e)
            }
        }
    }

    /** OSC 133 `D`. Runs on the PTY reader thread. */
    fun onCommandFinished(context: Context, sessionNumber: Int, durationMs: Long, exitCode: Int?) {
        val app = context.applicationContext
        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(app)
            val notify = CommandNotifications.shouldNotify(
                enabled = prefs.getBoolean(PREF_NOTIFY, true),
                thresholdSeconds = CommandNotifications.parseThreshold(prefs.getString(PREF_THRESHOLD, null)),
                durationMs = durationMs,
                appInForeground = CortexApp.isInForeground
            )
            if (!notify) return
            postNotification(app, sessionNumber, durationMs, exitCode)
        } catch (e: Exception) {
            Log.w(TAG, "Could not post command notification", e)
        }
    }

    private fun postNotification(app: Context, sessionNumber: Int, durationMs: Long, exitCode: Int?) {
        val lc = LocaleHelper.wrap(app)
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                lc.getString(R.string.notif_cmd_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = lc.getString(R.string.notif_cmd_channel_description)
            }
            nm.createNotificationChannel(channel)
        }

        val openIntent = Intent(app, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val slot = Math.floorMod(nextSlot.getAndIncrement(), NOTIFICATION_SLOTS)
        val pending = PendingIntent.getActivity(
            app, NOTIFICATION_BASE_ID + slot, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val failed = CommandNotifications.isFailure(exitCode)
        val title = if (failed) {
            lc.getString(R.string.notif_cmd_failed, exitCode ?: 0)
        } else {
            lc.getString(R.string.notif_cmd_finished)
        }
        val text = lc.getString(R.string.notif_cmd_text, CommandNotifications.formatElapsed(durationMs), sessionNumber)

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(app, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(app)
        }
        builder.setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pending)
            .setAutoCancel(true)

        nm.notify(NOTIFICATION_BASE_ID + slot, builder.build())
    }
}
