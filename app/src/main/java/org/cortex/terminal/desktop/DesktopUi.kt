package org.cortex.terminal.desktop

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import org.cortex.terminal.MainActivity
import org.cortex.terminal.R
import org.cortex.terminal.view.showInChrome

/**
 * Actions of the desktop add-on: immediate XFCE + Firefox install without prompting, and removal confirmation.
 */
object DesktopUi {

    fun startInstall(onStarted: () -> Unit) {
        MainActivity.pendingSessionCommand = DesktopAddon.installCommand()
        onStarted()
    }

    fun showRemoveDialog(activity: Activity, onStarted: () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.pref_desktop_remove_title)
            .setMessage(R.string.desktop_remove_confirm)
            .setPositiveButton(R.string.desktop_remove_button) { _, _ ->
                MainActivity.pendingSessionCommand = "cortex-desktop remove"
                onStarted()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showInChrome()
    }
}
