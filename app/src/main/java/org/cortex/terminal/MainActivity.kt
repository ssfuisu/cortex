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

        // Base-system extraction is process-wide: if the activity is re-created while it runs (a
        // permission dialog, a configuration change, the user re-opening the app) the new instance
        // must wait for it instead of starting a second extraction over the first one.
        private val bootstrapLock = Any()
        private var bootstrapInProgress = false

        // Once per process: what a previous run left in /tmp is removed before the first session,
        // and the system's configuration is checked in the background.
        @Volatile
        private var startupCleanupDone = false
        private val maintenanceStarted = java.util.concurrent.atomic.AtomicBoolean(false)
    }

    private lateinit var binding: ActivityMainBinding

    private val drawerLayout: DrawerLayout get() = binding.drawerLayout
    private val terminalView: TerminalView get() = binding.terminalView
    private val extraKeysView: ExtraKeysView get() = binding.extraKeysView
    private val searchBar: TerminalSearchBar get() = binding.searchBar
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
                    getString(R.string.update_allow_unknown_sources),
                    Toast.LENGTH_LONG
                ).show()
                UpdateManager.openUnknownAppSourcesSettings(this)
                return@setOnClickListener
            }
            val cached = UpdateManager.cachedReleaseInfo
            if (UpdateManager.isUpdateAvailable && cached != null) {
                UpdateManager.showUpdateDialog(this, cached)
            } else {
                val checkingToast = Toast.makeText(this, R.string.update_checking, Toast.LENGTH_SHORT)
                checkingToast.show()
                UpdateManager.checkForUpdate(this) { isAvailable, info, error ->
                    if (isFinishing || isDestroyed) return@checkForUpdate
                    checkingToast.cancel()
                    if (isAvailable && info != null) {
                        updateRedDot.visibility = android.view.View.VISIBLE
                        UpdateManager.showUpdateDialog(this, info)
                    } else if (error != null) {
                        Toast.makeText(this, getString(R.string.update_check_failed, error), Toast.LENGTH_LONG).show()
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
                    Toast.makeText(this, getString(R.string.copied_url, url), Toast.LENGTH_SHORT).show()
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

        val bootstrapInstalledAtStart = BootstrapManager.isBootstrapInstalled(this)
        // A system set up by an older version of the app is updated first, in the background like
        // the extraction (only without its dialog: it takes a second or two).
        val systemReady = bootstrapInstalledAtStart && !BootstrapManager.needsUpdate(this)
        if (systemReady) {
            // Before the first session: the hook library every program loads (rewritten only when
            // this app brings a different one), and the sockets and locks a previous run left in
            // /tmp. Those only when no session of this process runs yet: an activity created again
            // (rotation, a theme or language change) must not delete the running programs' files.
            try {
                BootstrapManager.ensureHookLibrary(this, root)
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Startup ensureHookLibrary failed", e)
            }
            if (!startupCleanupDone && sessionManager.sessions.isEmpty()) {
                startupCleanupDone = true
                try {
                    BootstrapManager.cleanupStaleSocketsAndLocks(root, homeDir)
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "Startup cleanup failed", e)
                }
            }
            startMaintenance(root)
        }

        if (!systemReady) {
            isBootstrapping = true
            val progress = if (bootstrapInstalledAtStart) null else android.app.ProgressDialog(this).apply {
                setMessage(getString(R.string.bootstrap_progress))
                // A bar with the share of the archive extracted so far; until the first figure
                // arrives (and for an archive of unknown size) it just moves.
                setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL)
                setProgressNumberFormat(null)
                max = 100
                isIndeterminate = true
                setCancelable(false)
                setCanceledOnTouchOutside(false)
            }
            bootstrapDialog = progress
            try {
                progress?.show()
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Failed to show progress dialog", e)
            }
            kotlin.concurrent.thread(name = "Cortex-Bootstrap") {
                // Whatever happens in here, the modal progress dialog below must be dismissed;
                // an uncaught error used to leave "Extracting base system..." on screen forever.
                val success = try {
                    runBootstrapOnce(root) { percent ->
                        runOnUiThread {
                            if (progress != null) {
                                progress.isIndeterminate = false
                                progress.progress = percent
                            }
                        }
                    }
                } catch (t: Throwable) {
                    android.util.Log.e("MainActivity", "Bootstrap failed", t)
                    false
                }

                runOnUiThread {
                    try {
                        if (progress != null && progress.isShowing) {
                            progress.dismiss()
                        }
                    } catch (e: Exception) {}
                    bootstrapDialog = null
                    isBootstrapping = false
                    if (isFinishing || isDestroyed) {
                        // A re-created activity runs its own wait/finish sequence.
                        return@runOnUiThread
                    }
                    if (success) {
                        createNewSession()
                        terminalView.post {
                            terminalView.showKeyboard()
                        }
                        checkAndRequestStoragePermission()
                    } else {
                        android.widget.Toast.makeText(
                            this,
                            getString(R.string.bootstrap_failed),
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

    /**
     * Checks the Ubuntu system's configuration (DNS, time zone, certificates, apt, users, Cortex's
     * tools...) off the main thread, once per process. A file is only rewritten when it differs, and
     * then in one step, so the sessions starting meanwhile are not disturbed.
     */
    private fun startMaintenance(root: java.io.File) {
        if (!maintenanceStarted.compareAndSet(false, true)) return
        val app = applicationContext
        kotlin.concurrent.thread(name = "Cortex-StartupMaintenance") {
            val started = android.os.SystemClock.uptimeMillis()
            try {
                BootstrapManager.initializeFileSystem(app)
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Startup filesystem init failed", e)
            }
            try {
                BootstrapManager.ensureUbuntuSources(root)
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Startup ensureUbuntuSources failed", e)
            }
            // libc6-dev and its dependencies, for older installations that never had them
            try {
                BootstrapManager.ensureDevPackages(app, root)
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Startup C development packages failed", e)
            }
            // The fastest Ubuntu mirror, chosen once (it needs the network: again on a later start
            // when none answered)
            try {
                org.cortex.terminal.runtime.MirrorSelector.selectOnce(root)
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Choosing a mirror failed", e)
            }
            android.util.Log.i(
                "MainActivity",
                "Start-up maintenance took ${android.os.SystemClock.uptimeMillis() - started} ms"
            )
        }
    }

    /**
     * Extracts the Ubuntu base system, at most once at a time per process. A caller that arrives
     * while another extraction is running waits for it and reports whether it succeeded.
     */
    private fun runBootstrapOnce(root: java.io.File, onProgress: ((Int) -> Unit)? = null): Boolean {
        val isOwner = synchronized(bootstrapLock) {
            if (bootstrapInProgress) {
                false
            } else {
                bootstrapInProgress = true
                true
            }
        }
        if (!isOwner) {
            while (synchronized(bootstrapLock) { bootstrapInProgress }) {
                Thread.sleep(300)
            }
            return BootstrapManager.isBootstrapInstalled(this)
        }
        try {
            if (BootstrapManager.isBootstrapInstalled(this)) {
                // Installed by an older version of the app: no session runs yet in this process.
                if (!startupCleanupDone && sessionManager.sessions.isEmpty()) {
                    startupCleanupDone = true
                    BootstrapManager.cleanupStaleSocketsAndLocks(root, Environment.getHomeDir(this))
                }
                BootstrapManager.updateInstalledSystem(this)
                startMaintenance(root)
                return true
            }
            val installed = BootstrapManager.installBootstrapFromAssets(this, onProgress)
            if (installed) {
                // The base system is in place and configured; a hiccup in the C development
                // packages must not turn a working install into "Failed to initialize environment".
                startupCleanupDone = true
                maintenanceStarted.set(true)
                try {
                    BootstrapManager.ensureDevPackages(this, root)
                } catch (t: Throwable) {
                    android.util.Log.e("MainActivity", "Post-bootstrap setup failed", t)
                }
                // Measuring the mirrors takes up to 15 s: not before the first session
                kotlin.concurrent.thread(name = "Cortex-Mirror") {
                    try {
                        org.cortex.terminal.runtime.MirrorSelector.selectOnce(root)
                    } catch (e: Exception) {
                        android.util.Log.e("MainActivity", "Choosing a mirror failed", e)
                    }
                }
            }
            return installed
        } finally {
            synchronized(bootstrapLock) { bootstrapInProgress = false }
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
                    .setTitle(R.string.storage_permission_title)
                    .setMessage(R.string.storage_permission_message)
                    .setCancelable(false)
                    .setPositiveButton(R.string.storage_permission_grant) { _, _ ->
                        try {
                            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                data = Uri.parse("package:$packageName")
                            }
                            startActivity(intent)
                        } catch (e: Exception) {
                            try {
                                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                            } catch (e2: Exception) {
                                Toast.makeText(this, R.string.storage_permission_open_failed, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    .setNegativeButton(R.string.later, null)
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
