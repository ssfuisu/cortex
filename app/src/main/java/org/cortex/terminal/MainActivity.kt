package org.cortex.terminal

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.cortex.terminal.databinding.ActivityMainBinding
import org.cortex.terminal.runtime.BootstrapManager
import org.cortex.terminal.runtime.Environment
import org.cortex.terminal.service.CortexService
import org.cortex.terminal.session.SessionAdapter
import org.cortex.terminal.session.SessionManager
import org.cortex.terminal.session.TerminalSession
import org.cortex.terminal.update.UpdateManager
import org.cortex.terminal.view.ExtraKeysView
import org.cortex.terminal.view.TerminalSearchBar
import org.cortex.terminal.view.TerminalView

class MainActivity : AppCompatActivity() {

    companion object {
        var instance: MainActivity? = null
            private set
    }

    private lateinit var binding: ActivityMainBinding

    private val drawerLayout: DrawerLayout get() = binding.drawerLayout
    private val terminalView: TerminalView get() = binding.terminalView
    private val extraKeysView: ExtraKeysView get() = binding.extraKeysView
    private val searchBar: TerminalSearchBar get() = binding.searchBar
    private val drawerTitle: TextView get() = binding.drawerTitle
    private val btnDrawerSettings: ImageView get() = binding.btnDrawerSettings
    private val layoutUpdateBadge: android.widget.FrameLayout get() = binding.layoutUpdateBadge
    private val updateRedDot: android.view.View get() = binding.updateRedDot

    private val sessionRecyclerView: RecyclerView get() = binding.sessionRecyclerView
    private lateinit var sessionAdapter: SessionAdapter
    private val btnDrawerKeyboard: TextView get() = binding.btnDrawerKeyboard
    private val btnDrawerNewSession: TextView get() = binding.btnDrawerNewSession

    private lateinit var sessionManager: SessionManager
    private var isBootstrapping = false
    private var bootstrapDialog: android.app.Dialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupBackPressedDispatcher()
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

        layoutUpdateBadge.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
                Toast.makeText(
                    this,
                    "Güncelleme yüklemek için bilinmeyen uygulamaları yükleme iznini açın",
                    Toast.LENGTH_LONG
                ).show()
                UpdateManager.openUnknownAppSourcesSettings(this)
                return@setOnClickListener
            }
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

        // ---- Terminal search (FIND key / swipe right-to-left) ----
        extraKeysView.onSearchToggle = { showTerminalSearch(animated = true) }
        searchBar.onSwipeRight = { hideTerminalSearch(animated = true) }
        searchBar.onRequestClose = { hideTerminalSearch(animated = true) }
        searchBar.onQueryChanged = { query -> terminalView.startSearch(query) }
        searchBar.onNext = { terminalView.nextSearchHit() }
        searchBar.onPrev = { terminalView.prevSearchHit() }
        searchBar.onClose = { terminalView.clearSearch() }
        terminalView.onSearchChanged = { _, index, total -> searchBar.updateCount(index, total) }

        // ---- Clickable URLs -> Copy to clipboard ----
        terminalView.onUrlTapped = { url ->
            try {
                val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                if (clipboard != null) {
                    val clip = android.content.ClipData.newPlainText("URL", url)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(this, "Copied URL: $url", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this, url, Toast.LENGTH_SHORT).show()
            }
        }

        btnDrawerSettings.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        instance = this
        // Start foreground service FIRST so the 5s startForeground() ANR
        // window starts ticking while the UI keeps initializing.
        try {
            CortexService.start(this)
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "CortexService.start failed", e)
        }
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
        val root = Environment.getCortexRoot(this)
        val homeDir = Environment.getHomeDir(this)

        if (BootstrapManager.isBootstrapInstalled(this)) {
            // Fast synchronous prep before creating session:
            // 1. Ensure hook library is up-to-date and not corrupted (<2ms)
            // 2. Ensure repository GPG keyrings are present and verified (<1ms)
            // 3. Clear stale socket/lock files from tmp (<1ms)
            try {
                BootstrapManager.ensureHookLibrary(this, root)
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Startup ensureHookLibrary failed", e)
            }
            try {
                BootstrapManager.ensureKeyrings(root, this)
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Startup ensureKeyrings failed", e)
            }
            try {
                BootstrapManager.ensureAptSandbox(root)
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Startup ensureAptSandbox failed", e)
            }
            try {
                BootstrapManager.ensureUbuntuSources(root)
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Startup ensureUbuntuSources failed", e)
            }
            try {
                BootstrapManager.cleanupStaleSocketsAndLocks(root, homeDir)
                BootstrapManager.cleanupAptArtifacts(root)
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Startup cleanup failed", e)
            }
        }

        // Heavy file I/O (DNS, timezone, certs, essential binaries, xdg-open,
        // root tools) runs on a background thread. Doing it on the main thread
        // stalls onCreate, delays the first frame, and on slow devices can
        // push the CortexService foreground promotion past the 5s ANR window —
        // exactly the blank-screen + ANR kill reported in issue #1.
        kotlin.concurrent.thread(name = "Cortex-StartupMaintenance") {
            try {
                BootstrapManager.updateDnsConfiguration(this@MainActivity, root)
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Startup DNS update failed", e)
            }
            try {
                BootstrapManager.updateTimezone(this@MainActivity, root)
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Startup timezone update failed", e)
            }
            try {
                BootstrapManager.ensureCaCertificates(root, this@MainActivity)
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Startup CA certs failed", e)
            }
            try {
                BootstrapManager.ensureKeyrings(root, this@MainActivity)
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Startup keyrings failed", e)
            }
            try {
                BootstrapManager.ensureEssentialBinaries(root, homeDir, this@MainActivity)
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Startup essential binaries failed", e)
            }
            try {
                BootstrapManager.initializeFileSystem(this@MainActivity)
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Startup filesystem init failed", e)
            }
        }
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
                    BootstrapManager.ensureEssentialBinaries(root, Environment.getHomeDir(this), this)
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
        }
    }

    override fun onResume() {
        super.onResume()
        applyPreferences()
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
        } else if (!isBootstrapping && BootstrapManager.isBootstrapInstalled(this) && sessionManager.sessions.isEmpty()) {
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

        val themeKey = prefs.getString("terminal_theme", "catppuccin") ?: "catppuccin"
        org.cortex.terminal.emulator.TerminalColor.applyTheme(themeKey)
        terminalView.applyTheme()

        val fontKey = prefs.getString("terminal_font", "jetbrains_mono") ?: "jetbrains_mono"
        terminalView.setTerminalFont(fontKey)

        val keepScreenOn = prefs.getBoolean("keep_screen_on", false)
        if (keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        val secureScreen = prefs.getBoolean("secure_screen", false)
        if (secureScreen) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
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

    private var isSearchAnimating = false

    private fun showTerminalSearch(animated: Boolean = true) {
        if (searchBar.visibility == View.VISIBLE && !isSearchAnimating) return
        if (isSearchAnimating) return

        val width = extraKeysView.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val widthF = width.toFloat()

        if (!animated) {
            extraKeysView.visibility = View.GONE
            extraKeysView.translationX = 0f
            searchBar.visibility = View.VISIBLE
            searchBar.translationX = 0f
            searchBar.alpha = 1f
            searchBar.focusInput()
            return
        }

        isSearchAnimating = true

        searchBar.visibility = View.VISIBLE
        searchBar.translationX = widthF
        searchBar.alpha = 0f

        extraKeysView.animate()
            .translationX(-widthF)
            .alpha(0f)
            .setDuration(220L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                extraKeysView.visibility = View.GONE
                extraKeysView.translationX = 0f
                extraKeysView.alpha = 1f
            }
            .start()

        searchBar.animate()
            .translationX(0f)
            .alpha(1f)
            .setDuration(220L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                isSearchAnimating = false
                searchBar.focusInput()
            }
            .start()
    }

    private fun hideTerminalSearch(animated: Boolean = true) {
        if (searchBar.visibility != View.VISIBLE && !isSearchAnimating) return
        if (isSearchAnimating) return

        val width = searchBar.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val widthF = width.toFloat()

        searchBar.clearInputAndKeyboard()

        if (!animated) {
            searchBar.visibility = View.GONE
            searchBar.translationX = 0f
            terminalView.clearSearch()
            extraKeysView.visibility = View.VISIBLE
            extraKeysView.translationX = 0f
            extraKeysView.alpha = 1f
            return
        }

        isSearchAnimating = true

        extraKeysView.visibility = View.VISIBLE
        extraKeysView.translationX = -widthF
        extraKeysView.alpha = 0f

        searchBar.animate()
            .translationX(widthF)
            .alpha(0f)
            .setDuration(220L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                searchBar.visibility = View.GONE
                searchBar.translationX = 0f
                searchBar.alpha = 1f
                terminalView.clearSearch()
            }
            .start()

        extraKeysView.animate()
            .translationX(0f)
            .alpha(1f)
            .setDuration(220L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                isSearchAnimating = false
            }
            .start()
    }

    private fun toggleTerminalSearch() {
        if (searchBar.visibility == View.VISIBLE) {
            hideTerminalSearch(animated = true)
        } else {
            showTerminalSearch(animated = true)
        }
    }

    private fun setupBackPressedDispatcher() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (searchBar.visibility == View.VISIBLE) {
                    hideTerminalSearch(animated = true)
                    return
                }
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

                AlertDialog.Builder(this@MainActivity)
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
        })
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
        try { if (searchBar.isShowing()) searchBar.hide() } catch (_: Exception) {}
        terminalView.onUrlTapped = null
        terminalView.onSearchChanged = null
        terminalView.session = null
        if (instance == this) instance = null
        if (sessionManager.sessions.isEmpty()) {
            CortexService.stop(this)
        }
        super.onDestroy()
    }
}
