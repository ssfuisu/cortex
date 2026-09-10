package org.cortex.terminal

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.cortex.terminal.runtime.BootstrapManager
import org.cortex.terminal.runtime.Environment
import org.cortex.terminal.service.CortexService
import org.cortex.terminal.session.SessionAdapter
import org.cortex.terminal.session.SessionManager
import org.cortex.terminal.session.TerminalSession
import org.cortex.terminal.update.UpdateManager
import org.cortex.terminal.view.ExtraKeysView
import org.cortex.terminal.view.TerminalView

class MainActivity : AppCompatActivity() {

    companion object {
        var instance: MainActivity? = null
            private set
    }

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var terminalView: TerminalView
    private lateinit var extraKeysView: ExtraKeysView
    private lateinit var drawerTitle: TextView
    private lateinit var btnDrawerSettings: ImageView
    private lateinit var layoutUpdateBadge: android.widget.FrameLayout
    private lateinit var updateRedDot: android.view.View

    private lateinit var sessionRecyclerView: RecyclerView
    private lateinit var sessionAdapter: SessionAdapter
    private lateinit var btnDrawerKeyboard: TextView
    private lateinit var btnDrawerNewSession: TextView

    private lateinit var sessionManager: SessionManager
    private var isBootstrapping = false
    private var bootstrapDialog: android.app.Dialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        drawerLayout = findViewById(R.id.drawerLayout)
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
        try {
            val mLeftDraggerField = DrawerLayout::class.java.getDeclaredField("mLeftDragger")
            mLeftDraggerField.isAccessible = true
            val leftDragger = mLeftDraggerField.get(drawerLayout)
            val edgeSizeField = leftDragger.javaClass.getDeclaredField("mEdgeSize")
            edgeSizeField.isAccessible = true
            val density = resources.displayMetrics.density
            edgeSizeField.setInt(leftDragger, (40 * density).toInt())
        } catch (e: Exception) {}

        terminalView = findViewById(R.id.terminalView)
        extraKeysView = findViewById(R.id.extraKeysView)
        drawerTitle = findViewById(R.id.drawerTitle)
        btnDrawerSettings = findViewById(R.id.btnDrawerSettings)
        layoutUpdateBadge = findViewById(R.id.layoutUpdateBadge)
        updateRedDot = findViewById(R.id.updateRedDot)

        layoutUpdateBadge.setOnClickListener {
            val cached = UpdateManager.cachedReleaseInfo
            if (UpdateManager.isUpdateAvailable && cached != null) {
                UpdateManager.showUpdateDialog(this, cached)
            } else {
                val checkingToast = Toast.makeText(this, "Checking for updates...", Toast.LENGTH_SHORT)
                checkingToast.show()
                UpdateManager.checkForUpdate(this) { isAvailable, info, error ->
                    if (isFinishing || isDestroyed) return@checkForUpdate
                    checkingToast.cancel()
                    if (isAvailable && info != null) {
                        updateRedDot.visibility = android.view.View.VISIBLE
                        UpdateManager.showUpdateDialog(this, info)
                    } else if (error != null) {
                        Toast.makeText(this, "Update check failed: $error", Toast.LENGTH_LONG).show()
                    } else {
                        updateRedDot.visibility = android.view.View.GONE
                        UpdateManager.showUpToDateDialog(this, info?.tagName)
                    }
                }
            }
        }

        UpdateManager.cleanUpdates(this)
        UpdateManager.checkForUpdate(this) { isAvailable, _, _ ->
            if (!isFinishing && !isDestroyed) {
                updateRedDot.visibility = if (isAvailable) android.view.View.VISIBLE else android.view.View.GONE
            }
        }

        sessionRecyclerView = findViewById(R.id.sessionRecyclerView)
        btnDrawerKeyboard = findViewById(R.id.btnDrawerKeyboard)
        btnDrawerNewSession = findViewById(R.id.btnDrawerNewSession)

        terminalView.onTerminalTouchWhenDrawerOpen = {
            if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.closeDrawer(GravityCompat.START)
                true
            } else {
                false
            }
        }

        extraKeysView.terminalView = terminalView
        extraKeysView.onMenuClick = {
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

        instance = this
        CortexService.start(this)
        sessionManager = CortexService.getOrCreateSessionManager(this)

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
                        CortexService.updateNotification(this)
                    } else {
                        AlertDialog.Builder(this)
                            .setTitle(R.string.exit_confirm_title)
                            .setMessage(R.string.exit_confirm_message)
                            .setPositiveButton(R.string.yes) { _, _ ->
                                CortexService.instance?.exitAll() ?: run {
                                    sessionManager.destroyAll()
                                    finish()
                                }
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
                    if (!isBootstrapping && !isFinishing && !isDestroyed) {
                        CortexService.stop(this)
                        finish()
                    }
                } else {
                    CortexService.updateNotification(this)
                    terminalView.session = session
                    val tabNum = sessionManager.currentSessionIndex + 1
                    val totalTabs = sessionManager.sessions.size
                    drawerTitle.text = "Sessions [$tabNum/$totalTabs]"
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
        org.cortex.terminal.runtime.UrlOpenerServer.start(this)
        val root = Environment.getCortexRoot(this)
        BootstrapManager.updateDnsConfiguration(this, root)
        BootstrapManager.updateTimezone(this, root)
        BootstrapManager.ensureCaCertificates(root, this)
        BootstrapManager.ensureEssentialBinaries(root, Environment.getHomeDir(this))
        BootstrapManager.initializeFileSystem(this)
        if (!BootstrapManager.isBootstrapInstalled(this)) {
            isBootstrapping = true
            val progress = android.app.ProgressDialog(this).apply {
                setMessage("Setting up Cortex Glibc environment...\nExtracting base system...")
                setCancelable(false)
                setCanceledOnTouchOutside(false)
            }
            bootstrapDialog = progress
            try {
                progress.show()
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Failed to show progress dialog", e)
            }
            kotlin.concurrent.thread {
                val success = BootstrapManager.installBootstrapFromAssets(this)
                if (success) {
                    BootstrapManager.updateTimezone(this, root)
                    BootstrapManager.ensureEssentialBinaries(root, Environment.getHomeDir(this))
                    BootstrapManager.initializeFileSystem(this)
                }

                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        try {
                            if (progress.isShowing) {
                                progress.dismiss()
                            }
                        } catch (e: Exception) {}
                    }
                    bootstrapDialog = null
                    isBootstrapping = false
                    if (success) {
                        createNewSession()
                        terminalView.post {
                            terminalView.showKeyboard()
                        }
                        checkAndRequestStoragePermission()
                    } else {
                        android.widget.Toast.makeText(
                            this,
                            "Failed to initialize environment. Please restart the app.",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        } else {
            checkAndRequestStoragePermission()
            if (sessionManager.sessions.isEmpty()) {
                createNewSession()
            } else {
                val current = sessionManager.currentSession ?: sessionManager.sessions[0]
                terminalView.session = current
                val tabNum = sessionManager.currentSessionIndex + 1
                val totalTabs = sessionManager.sessions.size
                drawerTitle.text = "Sessions [$tabNum/$totalTabs]"
                sessionAdapter.notifyDataSetChanged()
                terminalView.invalidate()
            }
            terminalView.post {
                terminalView.showKeyboard()
            }

            kotlin.concurrent.thread(name = "Cortex-BackgroundMaintenance") {
                try {
                    BootstrapManager.updateDnsConfiguration(this@MainActivity, root)
                    BootstrapManager.fixAbsoluteSymlinks(root)
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "Background maintenance error", e)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applyPreferences()
        UpdateManager.cleanUpdates(this)
        if (UpdateManager.isUpdateAvailable) {
            updateRedDot.visibility = android.view.View.VISIBLE
        }
        val root = Environment.getCortexRoot(this)
        kotlin.concurrent.thread(name = "Cortex-DnsUpdate") {
            BootstrapManager.updateDnsConfiguration(this@MainActivity, root)
        }
        val current = sessionManager.currentSession
        if (current != null) {
            terminalView.session = current
        } else if (BootstrapManager.isBootstrapInstalled(this) && sessionManager.sessions.isEmpty()) {
            createNewSession()
        }
        terminalView.requestLayout()
        terminalView.postInvalidate()
    }

    override fun onPause() {
        super.onPause()
        terminalView.clearSelection()
    }

    private fun applyPreferences() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val fontSizeStr = prefs.getString("terminal_font_size", "11") ?: "11"
        val fontSize = fontSizeStr.toFloatOrNull() ?: 11f
        terminalView.setTerminalTextSize(fontSize)

        val keepScreenOn = prefs.getBoolean("keep_screen_on", false)
        if (keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun createNewSession(): TerminalSession {
        val session = sessionManager.newSession(
            rows = terminalView.rows,
            cols = terminalView.cols,
            widthPx = terminalView.width,
            heightPx = terminalView.height
        )
        terminalView.session = session
        val tabNum = sessionManager.currentSessionIndex + 1
        val totalTabs = sessionManager.sessions.size
        drawerTitle.text = "Sessions [$tabNum/$totalTabs]"
        sessionAdapter.notifyDataSetChanged()
        return session
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
                CortexService.instance?.exitAll() ?: run {
                    sessionManager.destroyAll()
                    super.onBackPressed()
                }
            }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    private fun checkAndRequestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                AlertDialog.Builder(this)
                    .setTitle("Manage All Files Permission")
                    .setMessage("Cortex Terminal requires 'All files access' permission to manage device storage (/sdcard) and run CLI development tools with full privileges.")
                    .setCancelable(false)
                    .setPositiveButton("Grant Permission") { _, _ ->
                        try {
                            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                data = Uri.parse("package:$packageName")
                            }
                            startActivity(intent)
                        } catch (e: Exception) {
                            try {
                                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                            } catch (e2: Exception) {
                                Toast.makeText(this, "Unable to open permission settings", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    .setNegativeButton("Later", null)
                    .show()
            }
        } else {
            val permissions = arrayOf(
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            val missing = permissions.filter {
                ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            if (missing.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1001)
            }
        }
    }

    override fun onDestroy() {
        try {
            if (bootstrapDialog?.isShowing == true) {
                bootstrapDialog?.dismiss()
            }
        } catch (e: Exception) {}
        bootstrapDialog = null
        sessionManager.onSessionChanged = null
        terminalView.session = null
        if (instance == this) instance = null
        if (sessionManager.sessions.isEmpty()) {
            CortexService.stop(this)
        }
        super.onDestroy()
    }
}
