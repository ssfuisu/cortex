package org.cortex.terminal

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.cortex.terminal.runtime.BootstrapManager
import org.cortex.terminal.runtime.Environment
import org.cortex.terminal.session.SessionAdapter
import org.cortex.terminal.session.SessionManager
import org.cortex.terminal.view.ExtraKeysView
import org.cortex.terminal.view.TerminalView

class MainActivity : AppCompatActivity() {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var terminalView: TerminalView
    private lateinit var extraKeysView: ExtraKeysView
    private lateinit var appTitle: TextView
    private lateinit var btnMenu: TextView
    private lateinit var btnDrawerSettings: ImageView

    private lateinit var sessionRecyclerView: RecyclerView
    private lateinit var sessionAdapter: SessionAdapter
    private lateinit var btnDrawerKeyboard: TextView
    private lateinit var btnDrawerNewSession: TextView

    private lateinit var sessionManager: SessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        drawerLayout = findViewById(R.id.drawerLayout)
        terminalView = findViewById(R.id.terminalView)
        extraKeysView = findViewById(R.id.extraKeysView)
        appTitle = findViewById(R.id.appTitle)
        btnMenu = findViewById(R.id.btnMenu)
        btnDrawerSettings = findViewById(R.id.btnDrawerSettings)

        sessionRecyclerView = findViewById(R.id.sessionRecyclerView)
        btnDrawerKeyboard = findViewById(R.id.btnDrawerKeyboard)
        btnDrawerNewSession = findViewById(R.id.btnDrawerNewSession)

        extraKeysView.terminalView = terminalView
        extraKeysView.onMenuClick = {
            if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.closeDrawer(GravityCompat.START)
            } else {
                drawerLayout.openDrawer(GravityCompat.START)
            }
        }

        btnMenu.setOnClickListener {
            if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.closeDrawer(GravityCompat.START)
            } else {
                drawerLayout.openDrawer(GravityCompat.START)
            }
        }

        btnDrawerSettings.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        sessionManager = SessionManager(this)

        sessionAdapter = SessionAdapter(
            sessionManager = sessionManager,
            onSelect = { index ->
                sessionManager.switchTo(index)
                drawerLayout.closeDrawer(GravityCompat.START)
            },
            onClose = { index ->
                val session = sessionManager.sessions.getOrNull(index)
                if (session != null) {
                    if (sessionManager.sessions.size > 1) {
                        sessionManager.removeSession(session)
                        sessionAdapter.notifyDataSetChanged()
                    } else {
                        AlertDialog.Builder(this)
                            .setTitle(R.string.exit_confirm_title)
                            .setMessage(R.string.exit_confirm_message)
                            .setPositiveButton(R.string.yes) { _, _ ->
                                sessionManager.destroyAll()
                                finish()
                            }
                            .setNegativeButton(R.string.no, null)
                            .show()
                    }
                }
            }
        )

        sessionRecyclerView.layoutManager = LinearLayoutManager(this)
        sessionRecyclerView.adapter = sessionAdapter

        sessionManager.onSessionChanged = { session ->
            runOnUiThread {
                if (session == null) {
                    if (!isFinishing && !isDestroyed) {
                        finish()
                    }
                } else {
                    terminalView.session = session
                    val tabNum = sessionManager.currentSessionIndex + 1
                    val totalTabs = sessionManager.sessions.size
                    appTitle.text = "Cortex [$tabNum/$totalTabs]"
                    sessionAdapter.notifyDataSetChanged()
                    terminalView.invalidate()
                }
            }
        }

        btnDrawerKeyboard.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            terminalView.showKeyboard()
        }

        btnDrawerNewSession.setOnClickListener {
            createNewSession()
            drawerLayout.closeDrawer(GravityCompat.START)
        }

        applyPreferences()
        val root = Environment.getCortexRoot(this)
        BootstrapManager.updateDnsConfiguration(this, root)
        if (!BootstrapManager.isBootstrapInstalled(this)) {
            val progress = android.app.ProgressDialog(this).apply {
                setMessage("Setting up Cortex Glibc environment...")
                setCancelable(false)
                show()
            }
            kotlin.concurrent.thread {
                BootstrapManager.installBootstrapFromAssets(this)
                runOnUiThread {
                    try {
                        progress.dismiss()
                    } catch (e: Exception) {
                    }
                    createNewSession()
                    terminalView.post {
                        terminalView.showKeyboard()
                    }
                }
            }
        } else {
            createNewSession()
            terminalView.post {
                terminalView.showKeyboard()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applyPreferences()
        val root = Environment.getCortexRoot(this)
        BootstrapManager.updateDnsConfiguration(this, root)
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
        val root = Environment.getCortexRoot(this)
        BootstrapManager.updateDnsConfiguration(this, root)
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
        sessionAdapter.notifyDataSetChanged()
    }

    override fun onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
            return
        }

        if (sessionManager.sessions.size > 1) {
            val current = sessionManager.currentSession
            if (current != null) {
                sessionManager.removeSession(current)
                sessionAdapter.notifyDataSetChanged()
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
