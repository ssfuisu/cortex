package org.cortex.terminal

import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import org.cortex.terminal.documents.CortexDocumentsProvider
import org.cortex.terminal.runtime.RootMode

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        // On some systems the window reaches under the navigation bar (seen on Android 14), which then
        // covered the last settings. The list keeps clear of it; elsewhere the inset is 0.
        val container = findViewById<android.view.View>(R.id.settings_container)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(container) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
            view.setPadding(bars.left, 0, bars.right, bars.bottom)
            insets
        }

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.settings_title)

        // In the colors of the terminal's scheme, like the screens of the terminal itself (this
        // screen can be the first of a new process, so the scheme is read here too)
        val themeKey = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
            .getString("terminal_theme", "catppuccin") ?: "catppuccin"
        org.cortex.terminal.emulator.TerminalColor.applyTheme(themeKey)
        org.cortex.terminal.view.Chrome.apply(
            org.cortex.terminal.emulator.TerminalColor.SCHEMES.getValue(org.cortex.terminal.emulator.TerminalColor.currentThemeId)
        )
        val p = org.cortex.terminal.view.Chrome.palette
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(p.bg))
        findViewById<android.view.View>(R.id.settings_root).setBackgroundColor(p.bg)
        supportActionBar?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(p.bar))
        supportActionBar?.title = android.text.SpannableString(getString(R.string.settings_title)).apply {
            setSpan(android.text.style.ForegroundColorSpan(p.text), 0, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        window.statusBarColor = p.bar
        window.navigationBarColor = p.bar
        val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = !p.dark
        controller.isAppearanceLightNavigationBars = !p.dark

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    override fun onResume() {
        super.onResume()
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        if (prefs.getBoolean("secure_screen", false)) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            // The scheme's colors, like the rest of the app
            SettingsStyle(this).install()
        }

        override fun onDisplayPreferenceDialog(preference: Preference) {
            if (preference is ListPreference) {
                // The color schemes are shown as they look
                if (preference.key == "terminal_theme") ThemePicker.show(requireContext(), preference)
                else showChoice(preference)
                return
            }
            super.onDisplayPreferenceDialog(preference)
        }

        /** The choices of a list preference in a dialog of the interface's colors (the library's is a gray one). */
        private fun showChoice(pref: ListPreference) {
            val context = requireContext()
            val entries = pref.entries ?: return
            val values = pref.entryValues ?: return
            val p = org.cortex.terminal.view.Chrome.palette
            val checked = values.indexOfFirst { it.toString() == pref.value }
            val adapter = object : android.widget.ArrayAdapter<CharSequence>(
                context, android.R.layout.simple_list_item_single_choice, entries
            ) {
                override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                    val row = super.getView(position, convertView, parent) as android.widget.CheckedTextView
                    row.setTextColor(p.text)
                    row.checkMarkTintList = android.content.res.ColorStateList.valueOf(p.accent)
                    return row
                }
            }
            val dialog = androidx.appcompat.app.AlertDialog.Builder(context)
                .setTitle(pref.dialogTitle ?: pref.title)
                .setSingleChoiceItems(adapter, checked) { d, which ->
                    val value = values[which].toString()
                    if (pref.callChangeListener(value)) pref.value = value
                    d.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
            org.cortex.terminal.view.ChromeDialog.style(dialog).show()
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey)

            findPreference<ListPreference>(LocaleHelper.PREF_KEY)?.let { pref ->
                // Show what is really active (on Android 13+ the language can also be changed in system settings).
                val available = resources.getStringArray(R.array.language_values).toList()
                pref.value = LocaleHelper.currentSelection(available)
                pref.setOnPreferenceChangeListener { _, newValue ->
                    LocaleHelper.setLanguage(newValue as String)
                    true
                }
            }

            // A size set with two fingers on the terminal is not one of the list's
            findPreference<ListPreference>("terminal_font_size")?.summaryProvider =
                Preference.SummaryProvider<ListPreference> { pref -> pref.entry ?: pref.value?.let { "$it sp" } }

            findPreference<androidx.preference.SwitchPreferenceCompat>(CortexDocumentsProvider.PREF_DOCUMENTS_ENABLED)
                ?.setOnPreferenceChangeListener { _, newValue ->
                    CortexDocumentsProvider.setEnabled(requireContext(), newValue as Boolean)
                    true
                }

            // The switch is set by RootModeUi once root is checked (on) or the files are given back (off).
            findPreference<SwitchPreferenceCompat>(RootMode.PREF_KEY)?.let { pref ->
                pref.setOnPreferenceChangeListener { _, newValue ->
                    val activity = requireActivity()
                    val update: (Boolean) -> Unit = { on -> if (isAdded) pref.isChecked = on }
                    if (newValue as Boolean) RootModeUi.enable(activity, update) else RootModeUi.disable(activity, update)
                    false
                }
            }

            findPreference<Preference>("telegram_contact_pref")?.setOnPreferenceClickListener {
                try {
                    val intent = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://t.me/ratzgn")
                    ).apply {
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                } catch (e: Exception) {}
                true
            }

            // The SSH server, started in a new session where its output (the first time: the
            // installation of openssh-server) and how to connect can be read
            findPreference<Preference>("ssh_server")?.setOnPreferenceClickListener {
                org.cortex.terminal.MainActivity.pendingSessionCommand = "cortex-sshd start"
                activity?.finish()
                true
            }

            findPreference<Preference>("bug_report")?.setOnPreferenceClickListener { pref ->
                // Reading the log takes a moment; the item stays disabled until the report is shared.
                pref.isEnabled = false
                val app = requireContext().applicationContext
                Thread({
                    val result = runCatching { BugReport.write(app) }
                    activity?.runOnUiThread {
                        pref.isEnabled = true
                        val activity = activity ?: return@runOnUiThread
                        result.mapCatching { BugReport.share(activity, it) }.onFailure { e ->
                            android.widget.Toast.makeText(
                                activity,
                                activity.getString(R.string.bug_report_failed, e.message ?: e.javaClass.simpleName),
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }, "Cortex-BugReport").start()
                true
            }
        }
    }
}
