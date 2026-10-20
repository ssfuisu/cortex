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

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.settings_title)

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
        }
    }
}
