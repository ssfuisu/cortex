package org.cortex.terminal

import android.app.Activity
import android.graphics.drawable.Animatable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import org.cortex.terminal.runtime.RootMode
import org.cortex.terminal.service.CortexService
import org.cortex.terminal.view.showInChrome
import kotlin.concurrent.thread

/**
 * The root mode switch in Settings. Turning it on first checks, with an animated dialog, that su
 * gives root and the chroot can be set up; turning it off closes the root sessions and gives the
 * files created as root back to the app. [onDone] gets the switch's new state.
 */
object RootModeUi {

    // Long enough to see the check animation even when su answers at once.
    private const val MIN_CHECK_MS = 1200L

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun Activity.isGone(): Boolean = isFinishing || isDestroyed

    fun enable(activity: Activity, onDone: (Boolean) -> Unit) {
        val app = activity.applicationContext
        val status = RootStatusDialog(activity)
        status.showWorking(activity.getString(R.string.root_checking))
        val startedAt = SystemClock.uptimeMillis()
        thread(name = "Cortex-RootCheck") {
            val result = RootMode.check(app)
            if (result == RootMode.Result.Ok) RootMode.setEnabled(app, true)
            val wait = (MIN_CHECK_MS - (SystemClock.uptimeMillis() - startedAt)).coerceAtLeast(0)
            mainHandler.postDelayed({
                if (activity.isGone()) return@postDelayed
                when (result) {
                    RootMode.Result.Ok ->
                        status.showSuccess(activity.getString(R.string.root_enabled)) { onDone(true) }
                    RootMode.Result.NoRoot ->
                        status.showFailure(
                            activity.getString(R.string.root_unavailable),
                            activity.getString(R.string.root_failed_no_su)
                        ) { onDone(false) }
                    is RootMode.Result.Failed ->
                        status.showFailure(
                            activity.getString(R.string.root_unavailable),
                            activity.getString(R.string.root_failed_setup, result.detail)
                        ) { onDone(false) }
                }
            }, wait)
        }
    }

    fun disable(activity: Activity, onDone: (Boolean) -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.root_exit_title)
            .setMessage(R.string.root_exit_message)
            .setPositiveButton(R.string.root_exit_confirm) { _, _ -> leave(activity, onDone) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> onDone(true) }
            .setOnCancelListener { onDone(true) }
            .showInChrome()
    }

    private fun leave(activity: Activity, onDone: (Boolean) -> Unit) {
        val app = activity.applicationContext
        val status = RootStatusDialog(activity)
        status.showWorking(activity.getString(R.string.root_exiting))

        // New sessions are normal ones from now on; the root sessions are closed.
        RootMode.setEnabled(app, false)
        val manager = CortexService.getOrCreateSessionManager(app)
        val rootSessions = synchronized(manager.sessions) { manager.sessions.filter { it.isRoot } }
        rootSessions.forEach { manager.removeSession(it) }

        val startedAt = SystemClock.uptimeMillis()
        thread(name = "Cortex-RootRestore") {
            // The closed shells exit on the hangup and write their history as root on the way out.
            if (rootSessions.isNotEmpty()) SystemClock.sleep(1500)
            val result = RootMode.restore(app)
            val wait = (MIN_CHECK_MS - (SystemClock.uptimeMillis() - startedAt)).coerceAtLeast(0)
            mainHandler.postDelayed({
                if (activity.isGone()) return@postDelayed
                if (result == RootMode.Result.Ok) {
                    status.showSuccess(activity.getString(R.string.root_disabled)) { onDone(false) }
                } else {
                    status.showFailure(
                        activity.getString(R.string.root_disabled),
                        activity.getString(R.string.root_exit_failed)
                    ) { onDone(false) }
                }
            }, wait)
        }
    }
}

/** Spinner with "…" counting while working, then an animated tick or cross with the result. */
private class RootStatusDialog(private val activity: Activity) {
    private val view: View = activity.layoutInflater.inflate(R.layout.dialog_root_check, null)
    private val progress: View = view.findViewById(R.id.rootCheckProgress)
    private val icon: ImageView = view.findViewById(R.id.rootCheckIcon)
    private val text: TextView = view.findViewById(R.id.rootCheckText)
    private val detail: TextView = view.findViewById(R.id.rootCheckDetail)
    private val close: Button = view.findViewById(R.id.rootCheckClose)
    private val dialog = AlertDialog.Builder(activity).setView(view).setCancelable(false).create()
    private val handler = Handler(Looper.getMainLooper())

    private var baseText = ""
    private var dots = 0
    private val dotTicker = object : Runnable {
        override fun run() {
            dots = (dots + 1) % 4
            text.text = baseText + ".".repeat(dots)
            handler.postDelayed(this, 400)
        }
    }

    fun showWorking(message: String) {
        baseText = message.trimEnd('…', '.', ' ')
        dots = 0
        text.text = baseText
        if (!dialog.isShowing) dialog.show()
        handler.removeCallbacks(dotTicker)
        handler.postDelayed(dotTicker, 400)
    }

    fun showSuccess(message: String, onDismissed: () -> Unit) {
        showResult(message, R.color.cortex_secondary, R.drawable.avd_root_success)
        handler.postDelayed({
            dismiss()
            onDismissed()
        }, 1500)
    }

    fun showFailure(message: String, detailText: String, onDismissed: () -> Unit) {
        showResult(message, R.color.cortex_accent, R.drawable.avd_root_failure)
        detail.text = detailText
        detail.visibility = View.VISIBLE
        close.visibility = View.VISIBLE
        close.setOnClickListener {
            dismiss()
            onDismissed()
        }
    }

    private fun showResult(message: String, colorRes: Int, iconRes: Int) {
        handler.removeCallbacks(dotTicker)
        if (!dialog.isShowing) dialog.show()
        text.text = message
        text.setTextColor(ContextCompat.getColor(activity, colorRes))
        progress.animate().alpha(0f).setDuration(150).withEndAction { progress.visibility = View.INVISIBLE }.start()

        val drawable = AppCompatResources.getDrawable(activity, iconRes)
        icon.setImageDrawable(drawable)
        icon.alpha = 0f
        icon.scaleX = 0.6f
        icon.scaleY = 0.6f
        icon.visibility = View.VISIBLE
        icon.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setInterpolator(OvershootInterpolator(2f))
            .setDuration(350)
            .start()
        (drawable as? Animatable)?.start()
    }

    private fun dismiss() {
        handler.removeCallbacksAndMessages(null)
        if (dialog.isShowing && !activity.isFinishing && !activity.isDestroyed) dialog.dismiss()
    }
}
