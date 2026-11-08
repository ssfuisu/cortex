package org.cortex.terminal.desktop

import android.app.Activity
import android.content.res.ColorStateList
import android.util.TypedValue
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import org.cortex.terminal.MainActivity
import org.cortex.terminal.R
import org.cortex.terminal.view.Chrome
import org.cortex.terminal.view.showInChrome

/**
 * The dialogs of the desktop add-on: the choice of desktop to install (and Firefox), and its removal.
 * Both run cortex-desktop in a new session of the terminal, where apt's progress can be read; [onStarted]
 * closes the screen they were opened from, so that the terminal shows it.
 */
object DesktopUi {

    fun showInstallDialog(activity: Activity, onStarted: () -> Unit) {
        val p = Chrome.palette
        val density = activity.resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val tint = ColorStateList.valueOf(p.accent)

        fun option(textRes: Int, id: Int) = RadioButton(activity).apply {
            this.id = id
            text = activity.getString(textRes)
            setTextColor(p.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            buttonTintList = tint
            setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
        }
        val group = RadioGroup(activity).apply {
            addView(option(R.string.desktop_install_light, ID_LIGHT))
            addView(option(R.string.desktop_install_xfce, ID_XFCE))
            check(if (DesktopAddon.installedKind(activity) == DesktopAddon.KIND_XFCE) ID_XFCE else ID_LIGHT)
        }
        val firefox = CheckBox(activity).apply {
            text = activity.getString(R.string.desktop_install_firefox)
            setTextColor(p.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            buttonTintList = tint
        }
        val note = TextView(activity).apply {
            text = activity.getString(R.string.desktop_install_note)
            setTextColor(p.textDim)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (8 * density).toInt(), pad, 0)
            addView(group)
            addView(firefox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (8 * density).toInt()
            })
            addView(note, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (12 * density).toInt()
            })
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.desktop_install_title)
            .setView(content)
            .setPositiveButton(R.string.desktop_install_button) { _, _ ->
                val kind = if (group.checkedRadioButtonId == ID_XFCE) DesktopAddon.KIND_XFCE else DesktopAddon.KIND_LIGHT
                MainActivity.pendingSessionCommand = DesktopAddon.installCommand(kind, firefox.isChecked)
                onStarted()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showInChrome()
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

    private const val ID_LIGHT = 0x7d01
    private const val ID_XFCE = 0x7d02
}
