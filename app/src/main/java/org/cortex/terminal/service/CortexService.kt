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
            try {
                val intent = Intent(context, CortexService::class.java).apply {
                    action = ACTION_START
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {
                        context.startForegroundService(intent)
                    } catch (e: IllegalStateException) {
                        // App in background on Android 8+: fallback to normal start.
                        // onStartCommand will promote to foreground when allowed.
                        android.util.Log.w("CortexService", "startForegroundService blocked, using startService", e)
                        try { context.startService(intent) } catch (e2: Exception) {
                            android.util.Log.e("CortexService", "Failed to start service", e2)
                        }
                    }
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                android.util.Log.e("CortexService", "Failed to start service", e)
            }
        }

        fun updateNotification(context: Context) {
            try {
                val intent = Intent(context, CortexService::class.java).apply {
                    action = ACTION_UPDATE_NOTIFICATION
                }
                // Never promote a stopped service with startForegroundService here:
                // use plain startService so updateNotification() can never cause
                // "Context.startForegroundService() did not then call Service.startForeground()" ANR.
                // If the process is in background on Android 8+, this may throw
                // IllegalStateException — safe to ignore, UI is not visible anyway.
                context.startService(intent)
            } catch (e: IllegalStateException) {
                android.util.Log.w("CortexService", "updateNotification skipped (background)", e)
            } catch (e: Exception) {
                android.util.Log.e("CortexService", "updateNotification failed", e)
            }
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context, CortexService::class.java).apply {
                    action = ACTION_STOP
                }
                context.startService(intent)
            } catch (e: IllegalStateException) {
                android.util.Log.w("CortexService", "stop skipped (background)", e)
            } catch (e: Exception) {
                android.util.Log.e("CortexService", "stop failed", e)
            }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 1) Promote to foreground IMMEDIATELY. Android gives ~5s after
        // startForegroundService() before ANR-killing the app. Everything
        // below (channel creation, session manager, socket bind) must never
        // run before this call.
        try {
            createNotificationChannel()
        } catch (e: Exception) {
            android.util.Log.e("CortexService", "createNotificationChannel failed", e)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // API 34
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    0
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        } catch (e: Exception) {
            android.util.Log.e("CortexService", "startForeground failed", e)
            // Last resort: still try without type so the 5s ANR window is satisfied.
            try { startForeground(NOTIFICATION_ID, buildNotification()) } catch (e2: Exception) {}
        }
        // 2) Heavy / blocking init AFTER startForeground so it can never
        // delay the foreground promotion and trigger the reported ANR.
        try {
            getOrCreateSessionManager(this)
        } catch (e: Exception) {
            android.util.Log.e("CortexService", "getOrCreateSessionManager failed", e)
        }
        try {
            org.cortex.terminal.runtime.UrlOpenerServer.start(this)
        } catch (e: Exception) {
            android.util.Log.e("CortexService", "UrlOpenerServer.start failed", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Defensive re-promotion: if the system restarted us (e.g. after ANR
        // kill or process death), make sure we are foreground again before
        // doing anything else.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // API 34
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    0
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        } catch (e: Exception) {
            android.util.Log.w("CortexService", "re-promote startForeground failed", e)
        }
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
                    // Defensive 4-hour max timeout to prevent battery drain if app is orphaned
                    it.acquire(4 * 60 * 60 * 1000L)
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
            .setSmallIcon(R.drawable.ic_notification)
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
        org.cortex.terminal.runtime.UrlOpenerServer.stop()
        releaseWakeLock()
        instance = null
        super.onDestroy()
    }
}
