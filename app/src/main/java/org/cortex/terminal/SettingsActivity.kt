package org.cortex.terminal

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import org.cortex.terminal.runtime.CortexRuntime
import org.cortex.terminal.runtime.Environment

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey)

            findPreference<Preference>("cortex_info_pref")?.setOnPreferenceClickListener {
                showRuntimeInfo()
                true
            }
        }

        private fun showRuntimeInfo() {
            val ctx = requireContext()
            val arch = CortexRuntime.architecture
            val is64 = if (CortexRuntime.is64Bit) "64-bit" else "32-bit"
            val root = Environment.getCortexRoot(ctx).absolutePath
            val home = Environment.getHomeDir(ctx).absolutePath

            val message = """
                Architecture: $arch ($is64)
                Cortex Root: $root
                Home Directory: $home
                Zero-Overhead Hook: libcortex-hook.so
                Execution: Native Linux Kernel (No ptrace/No PRoot)
            """.trimIndent()

            AlertDialog.Builder(requireContext())
                .setTitle("Cortex System Info")
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show()
        }
    }
}
