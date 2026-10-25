package org.cortex.terminal.view

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.ListPreference
import org.cortex.terminal.BootstrapDialog
import org.cortex.terminal.R
import org.cortex.terminal.ThemePicker
import org.cortex.terminal.databinding.ActivityMainBinding
import org.cortex.terminal.databinding.ItemSessionBinding
import org.cortex.terminal.emulator.TerminalColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * The views and layouts around the terminal, made for real (on the JVM, with Robolectric): that they
 * inflate, hold what they are given and answer taps. What they look like is seen on the emulator.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChromeViewsTest {

    private lateinit var activity: AppCompatActivity

    @Before
    fun setUp() {
        TerminalColor.applyTheme("catppuccin")
        Chrome.apply(TerminalColor.SCHEMES.getValue("catppuccin"))
        activity = Robolectric.buildActivity(AppCompatActivity::class.java).create().get()
        activity.setTheme(R.style.Theme_Cortex)
    }

    private fun texts(view: View, into: MutableList<String> = mutableListOf()): List<String> {
        if (view is TextView) into.add(view.text.toString())
        if (view is ViewGroup) for (i in 0 until view.childCount) texts(view.getChildAt(i), into)
        return into
    }

    private fun tabRows(tabs: SessionTabsView): ViewGroup {
        val scroll = tabs.getChildAt(0) as ViewGroup
        return scroll.getChildAt(0) as ViewGroup
    }

    @Test
    fun theTabsShowOneTabForEachSessionAndMarkRootOnes() {
        val tabs = SessionTabsView(activity)
        tabs.update(listOf(SessionTabsView.Tab("~", false), SessionTabsView.Tab("src", true)), 1)
        assertEquals(2, tabRows(tabs).childCount)
        val shown = texts(tabs)
        assertTrue(shown.toString(), shown.containsAll(listOf("1", "~", "2", "src", "ROOT")))
    }

    @Test
    fun onlyTheTabOnScreenHasACloseButton() {
        val tabs = SessionTabsView(activity)
        tabs.update(listOf(SessionTabsView.Tab("a", false), SessionTabsView.Tab("b", false)), 0)
        val closes = texts(tabs).count { it == "✕" }
        assertEquals(1, closes)
    }

    @Test
    fun tappingATabSelectsItAndThePlusStartsASession() {
        val tabs = SessionTabsView(activity)
        var selected = -1
        var started = 0
        tabs.onSelect = { selected = it }
        tabs.onNew = { started++ }
        tabs.update(listOf(SessionTabsView.Tab("a", false), SessionTabsView.Tab("b", false)), 0)
        tabRows(tabs).getChildAt(1).performClick()
        assertEquals(1, selected)
        tabs.getChildAt(1).performClick() // the button after the strip
        assertEquals(1, started)
    }

    @Test
    fun theCloseButtonClosesTheTabOnScreen() {
        val tabs = SessionTabsView(activity)
        var closed = -1
        tabs.onClose = { closed = it }
        tabs.update(listOf(SessionTabsView.Tab("a", false), SessionTabsView.Tab("b", false)), 1)
        val row = tabRows(tabs).getChildAt(1) as ViewGroup
        val close = (0 until row.childCount).map { row.getChildAt(it) }.filterIsInstance<TextView>().first { it.text == "✕" }
        close.performClick()
        assertEquals(1, closed)
    }

    @Test
    fun theTabsFollowTheSchemeWhenItChanges() {
        val tabs = SessionTabsView(activity)
        tabs.update(listOf(SessionTabsView.Tab("a", false)), 0)
        Chrome.apply(TerminalColor.SCHEMES.getValue("nord"))
        tabs.applyPalette()
        assertEquals(1, tabRows(tabs).childCount)
        Chrome.apply(TerminalColor.SCHEMES.getValue("catppuccin"))
    }

    @Test
    fun theKeysBarHasItsRowsAndFollowsTheScheme() {
        val keys = ExtraKeysView(activity)
        assertEquals(2, keys.rowCount)
        Chrome.apply(TerminalColor.SCHEMES.getValue("monokai"))
        keys.applyPalette()
        assertEquals(2, keys.rowCount)
        Chrome.apply(TerminalColor.SCHEMES.getValue("catppuccin"))
    }

    @Test
    fun theSearchBarFollowsTheScheme() {
        val bar = TerminalSearchBar(activity)
        bar.applyPalette()
        assertNotNull(bar.background)
    }

    @Test
    fun aSessionCardInflates() {
        val binding = ItemSessionBinding.inflate(LayoutInflater.from(activity))
        assertNotNull(binding.sessionIndexBadge)
        assertNotNull(binding.sessionPathText)
        assertNotNull(binding.btnSessionClose)
    }

    @Test
    fun theMainScreenInflatesWithItsTabsKeysAndDrawer() {
        val binding = ActivityMainBinding.inflate(LayoutInflater.from(activity))
        assertNotNull(binding.sessionTabs)
        assertNotNull(binding.terminalView)
        assertNotNull(binding.extraKeysView)
        assertNotNull(binding.btnDrawerNewSession)
        assertNotNull(binding.btnDrawerSettings)
    }

    @Test
    fun theFirstStartDialogShowsTheProgress() {
        val dialog = BootstrapDialog(activity)
        dialog.show()
        assertTrue(dialog.isShowing)
        dialog.isIndeterminate = false
        dialog.progress = 42
        assertEquals(42, dialog.progress)
        dialog.dismiss()
    }

    @Test
    fun theSchemePickerListsEverySchemeAndChoosesOne() {
        val pref = ListPreference(activity).apply {
            title = "Color scheme"
            entries = TerminalColor.SCHEMES.values.map { it.displayName }.toTypedArray()
            entryValues = TerminalColor.SCHEMES.keys.toTypedArray()
            value = "catppuccin"
        }
        ThemePicker.show(activity as Activity, pref)
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertEquals(TerminalColor.SCHEMES.size, dialog.listView.adapter.count)
        assertNotNull(dialog.listView.adapter.getView(3, null, dialog.listView))
    }
}
