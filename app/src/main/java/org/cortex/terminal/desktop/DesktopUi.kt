package org.cortex.terminal.desktop

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import org.cortex.terminal.MainActivity
import org.cortex.terminal.R
import org.cortex.terminal.runtime.DesktopOption
import org.cortex.terminal.runtime.DistroSetup
import org.cortex.terminal.view.Chrome
import org.cortex.terminal.view.showInChrome

/**
 * The dialogs of the desktop add-on: interactive card-based choice of desktop environment to install
 * (with Firefox toggle), and its removal confirmation. Both run cortex-desktop in a new terminal session.
 */
object DesktopUi {

    fun showInstallDialog(activity: Activity, onStarted: () -> Unit) {
        val p = Chrome.palette
        val density = activity.resources.displayMetrics.density
        val hairline = density.toInt().coerceAtLeast(1)
        fun dp(v: Int) = (v * density).toInt()

        val desktops = DistroSetup.DESKTOPS.filter { it.kind != null }
        val installed = DesktopAddon.installedKind(activity)
        val initialIdx = desktops.indexOfFirst { it.kind == installed }.takeIf { it >= 0 }
            ?: desktops.indexOfFirst { it.recommended }.coerceAtLeast(0)
        var selectedKind = desktops.getOrNull(initialIdx)?.kind ?: DesktopAddon.KIND_XFCE
        var installFirefox = false

        val cards = mutableListOf<LinearLayout>()
        val checks = mutableListOf<FrameLayout>()

        fun updateSelection() {
            desktops.forEachIndexed { idx, d ->
                val on = d.kind == selectedKind
                cards[idx].background = Chrome.ripple(
                    Chrome.rounded(
                        if (on) p.accentSoft else p.card,
                        14 * density,
                        if (on) p.accent else p.outline,
                        if (on) (1.5f * density).toInt().coerceAtLeast(1) else hairline
                    ),
                    Chrome.withAlpha(p.accent, 60),
                    14 * density
                )
                val check = checks[idx]
                check.removeAllViews()
                if (on) {
                    check.background = Chrome.rounded(p.accent, 99 * density)
                    check.addView(ImageView(activity).apply {
                        setImageResource(R.drawable.ic_check)
                        setColorFilter(p.onAccent)
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }, FrameLayout.LayoutParams(dp(13), dp(13), Gravity.CENTER))
                } else {
                    check.background = Chrome.rounded(0, 99 * density, p.outline, hairline)
                }
            }
        }

        val listContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(4))
        }

        desktops.forEach { d ->
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(11), dp(12), dp(11))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(8)
                }
            }
            val iconTile = FrameLayout(activity).apply {
                background = Chrome.rounded(p.raised, 11 * density, p.outline, hairline)
                addView(ImageView(activity).apply {
                    setImageResource(d.icon)
                    setColorFilter(p.accent)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            }
            card.addView(iconTile, LinearLayout.LayoutParams(dp(40), dp(40)))

            val col = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(8), 0)
            }
            col.addView(TextView(activity).apply {
                text = d.name
                setTextColor(p.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            })
            col.addView(TextView(activity).apply {
                text = activity.getString(d.description)
                setTextColor(p.textDim)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setPadding(0, dp(3), 0, 0)
            })
            if (d.recommended || d.heavy) {
                val badgeText = activity.getString(if (d.heavy) R.string.setup_desktop_heavy else R.string.setup_badge_recommended)
                val badgeFg = if (d.heavy) p.warn else p.accent
                val badgeBg = Chrome.withAlpha(badgeFg, 28)
                col.addView(TextView(activity).apply {
                    text = badgeText
                    setTextColor(badgeFg)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    background = Chrome.rounded(badgeBg, 99 * density, Chrome.withAlpha(badgeFg, 110), hairline)
                    setPadding(dp(8), dp(2), dp(8), dp(2))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(6)
                    }
                })
            }
            card.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val check = FrameLayout(activity)
            card.addView(check, LinearLayout.LayoutParams(dp(20), dp(20)))
            card.setOnClickListener {
                selectedKind = d.kind ?: DesktopAddon.KIND_XFCE
                updateSelection()
            }
            cards += card
            checks += check
            listContainer.addView(card)
        }
        updateSelection()

        // Firefox switch row
        val firefoxSwitch = SwitchCompat(activity).apply {
            isChecked = installFirefox
            val checkedState = intArrayOf(android.R.attr.state_checked)
            thumbTintList = ColorStateList(arrayOf(checkedState, intArrayOf()), intArrayOf(p.onAccent, p.textDim))
            trackTintList = ColorStateList(arrayOf(checkedState, intArrayOf()), intArrayOf(p.accent, p.raisedHigh))
            setOnCheckedChangeListener { _, isChecked -> installFirefox = isChecked }
        }
        val firefoxRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Chrome.ripple(Chrome.rounded(p.card, 14 * density, p.outline, hairline), Chrome.withAlpha(p.accent, 50), 14 * density)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
            }
            addView(TextView(activity).apply {
                text = activity.getString(R.string.desktop_install_firefox)
                setTextColor(p.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(10) })
            addView(firefoxSwitch)
            setOnClickListener { firefoxSwitch.toggle() }
        }
        listContainer.addView(firefoxRow)

        listContainer.addView(TextView(activity).apply {
            text = activity.getString(R.string.desktop_install_note)
            setTextColor(p.textDim)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setLineSpacing(0f, 1.2f)
            background = Chrome.rounded(Chrome.withAlpha(p.raised, 180), 12 * density, p.outline, hairline)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            }
        })

        AlertDialog.Builder(activity)
            .setTitle(R.string.desktop_install_title)
            .setView(ScrollView(activity).apply { addView(listContainer) })
            .setPositiveButton(R.string.desktop_install_button) { _, _ ->
                MainActivity.pendingSessionCommand = DesktopAddon.installCommand(selectedKind, installFirefox)
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
}
