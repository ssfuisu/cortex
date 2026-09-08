package org.cortex.terminal.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import org.cortex.terminal.MainActivity
import org.cortex.terminal.R
import org.cortex.terminal.session.SessionManager

class CortexService : Service() {

    companion object {
        const val NOTIFICATION_ID = 1337
        const val CHANNEL_ID = "cortex_terminal_channel"

        const val ACTION_START = "org.cortex.terminal.action.START"
        const val ACTION_STOP = "org.cortex.terminal.action.STOP"
        const val ACTION_EXIT = "org.cortex.terminal.action.EXIT"
        const val ACTION_TOGGLE_WAKELOCK = "org.cortex.terminal.action.TOGGLE_WAKELOCK"
        const val ACTION_UPDATE_NOTIFICATION = "org.cortex.terminal.action.UPDATE_NOTIFICATION"

        var instance: CortexService? = null
            private set

        private var _sessionManager: SessionManager? = null
        private var lastVersionCode: Int = 0

        fun getOrCreateSessionManager(context: Context): SessionManager {
            val appVersion = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(context.packageName, 0).versionCode
                }
            } catch (e: Exception) { 0 }

            if (lastVersionCode != 0 && lastVersionCode < appVersion && _sessionManager != null) {
                android.util.Log.i("CortexService", "App version updated from $lastVersionCode to $appVersion. Resetting sessions.")
                try {
                    _sessionManager?.destroyAll()
                } catch (e: Exception) {}
                _sessionManager = null
            }
            lastVersionCode = appVersion

            if (_sessionManager == null) {
                _sessionManager = SessionManager(context.applicationContext)
            }
            return _sessionManager!!
        }

        var isWakeLockHeld: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, CortexService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun updateNotification(context: Context) {
            val intent = Intent(context, CortexService::class.java).apply {
                action = ACTION_UPDATE_NOTIFICATION
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, CortexService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        getOrCreateSessionManager(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_EXIT -> {
                exitAll()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_WAKELOCK -> {
                toggleWakeLock()
                updateNotificationDisplay()
            }
            ACTION_UPDATE_NOTIFICATION -> {
                updateNotificationDisplay()
            }
            ACTION_STOP -> {
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                updateNotificationDisplay()
            }
        }
        return START_STICKY
    }

    private fun toggleWakeLock() {
        if (isWakeLockHeld) {
            releaseWakeLock()
        } else {
            acquireWakeLock()
        }
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cortex:wakelock")
            }
            wakeLock?.let {
                if (!it.isHeld) {
                    it.acquire()
                    isWakeLockHeld = true
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("CortexService", "Failed to acquire wakelock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
            isWakeLockHeld = false
        } catch (e: Exception) {
            android.util.Log.e("CortexService", "Failed to release wakelock", e)
        }
    }

    fun exitAll() {
        try {
            releaseWakeLock()
            _sessionManager?.destroyAll()
            _sessionManager = null
        } catch (e: Exception) {}

        stopForeground(true)
        stopSelf()

        MainActivity.instance?.let { act ->
            act.runOnUiThread {
                act.finishAndRemoveTask()
            }
        }
    }

    private fun updateNotificationDisplay() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            android.util.Log.e("CortexService", "Failed to update notification", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Cortex Terminal",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Cortex active terminal sessions"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val sessionCount = _sessionManager?.sessions?.size ?: 1
        val sessionText = if (sessionCount == 1) "1 session" else "$sessionCount sessions"
        val contentText = if (isWakeLockHeld) "$sessionText (wake lock held)" else sessionText

        val flagImmutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or flagImmutable
        )

        val exitIntent = Intent(this, CortexService::class.java).apply {
            action = ACTION_EXIT
        }
        val exitPendingIntent = PendingIntent.getService(
            this, 1, exitIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or flagImmutable
        )

        val wlIntent = Intent(this, CortexService::class.java).apply {
            action = ACTION_TOGGLE_WAKELOCK
        }
        val wlPendingIntent = PendingIntent.getService(
            this, 2, wlIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or flagImmutable
        )

        val wlActionTitle = if (isWakeLockHeld) "Release Wakelock" else "Acquire Wakelock"

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        builder.setContentTitle("Cortex")
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    0, "Exit", exitPendingIntent
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    0, wlActionTitle, wlPendingIntent
                ).build()
            )

        return builder.build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep running in foreground if there are active sessions
        if (_sessionManager == null || _sessionManager?.sessions?.isEmpty() == true) {
            stopForeground(true)
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        releaseWakeLock()
        instance = null
        super.onDestroy()
    }
}
