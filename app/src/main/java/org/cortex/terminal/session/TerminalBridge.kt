package org.cortex.terminal.session

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import org.cortex.terminal.LocaleHelper
import org.cortex.terminal.R

/**
 * Terminal-to-Android escape sequence bridge (OSC 52 clipboard access, always enabled).
 */
object TerminalBridge {
    private const val TAG = "TerminalBridge"

    private val mainHandler = Handler(Looper.getMainLooper())

    /** OSC 52. Runs on the PTY reader thread, so the clipboard is touched on the main thread. */
    fun onClipboardRequest(context: Context, text: String) {
        val app = context.applicationContext
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
}
