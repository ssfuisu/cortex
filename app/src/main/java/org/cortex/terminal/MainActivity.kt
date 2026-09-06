package org.cortex.terminal

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import org.cortex.terminal.session.SessionManager
import org.cortex.terminal.view.ExtraKeysView
import org.cortex.terminal.view.TerminalView

class MainActivity : AppCompatActivity() {

    private lateinit var terminalView: TerminalView
    private lateinit var extraKeysView: ExtraKeysView
    private lateinit var appTitle: TextView
    private lateinit var btnNewTab: Button
    private lateinit var btnSettings: Button

    private lateinit var sessionManager: SessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        terminalView = findViewById(R.id.terminalView)
        extraKeysView = findViewById(R.id.extraKeysView)
        appTitle = findViewById(R.id.appTitle)
        btnNewTab = findViewById(R.id.btnNewTab)
        btnSettings = findViewById(R.id.btnSettings)

        extraKeysView.terminalView = terminalView

        sessionManager = SessionManager(this)
        sessionManager.onSessionChanged = { session ->
            runOnUiThread {
                if (session == null) {
                    finish()
                } else {
                    terminalView.session = session
                    val tabNum = sessionManager.currentSessionIndex + 1
                    val totalTabs = sessionManager.sessions.size
                    appTitle.text = "Cortex [$tabNum/$totalTabs]"
                    terminalView.invalidate()
                }
            }
        }

        btnNewTab.setOnClickListener {
            createNewSession()
        }

        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        applyPreferences()
        createNewSession()
    }

    override fun onResume() {
        super.onResume()
        applyPreferences()
    }

    private fun applyPreferences() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val fontSizeStr = prefs.getString("terminal_font_size", "14") ?: "14"
        val fontSize = fontSizeStr.toFloatOrNull() ?: 14f
        terminalView.setTerminalTextSize(fontSize)

        val keepScreenOn = prefs.getBoolean("keep_screen_on", false)
        if (keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun createNewSession() {
        val session = sessionManager.newSession(
            rows = terminalView.rows,
            cols = terminalView.cols,
            widthPx = terminalView.width,
            heightPx = terminalView.height
        ) {
            runOnUiThread {
                terminalView.invalidate()
            }
        }
        terminalView.session = session
        val tabNum = sessionManager.currentSessionIndex + 1
        val totalTabs = sessionManager.sessions.size
        appTitle.text = "Cortex [$tabNum/$totalTabs]"
    }

    override fun onBackPressed() {
        if (sessionManager.sessions.size > 1) {
            val current = sessionManager.currentSession
            if (current != null) {
                sessionManager.removeSession(current)
                return
            }
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.exit_confirm_title)
            .setMessage(R.string.exit_confirm_message)
            .setPositiveButton(R.string.yes) { _, _ ->
                sessionManager.destroyAll()
                super.onBackPressed()
            }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    override fun onDestroy() {
        sessionManager.destroyAll()
        super.onDestroy()
    }
}
