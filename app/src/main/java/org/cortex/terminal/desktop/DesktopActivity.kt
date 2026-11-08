package org.cortex.terminal.desktop

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.preference.PreferenceManager
import org.cortex.terminal.R
import org.cortex.terminal.emulator.TerminalColor
import org.cortex.terminal.service.CortexService
import org.cortex.terminal.view.Chrome
import kotlin.concurrent.thread

/**
 * The desktop (X11) on the phone's screen: starts it with cortex-desktop when it is not running, then
 * shows what Ubuntu's Xvnc draws (RfbClient over the app's own Unix socket), with a bar of the keys a
 * phone keyboard lacks. Its sound plays while the screen is open; leaving the screen leaves the desktop
 * running, the Stop key ends it.
 */
class DesktopActivity : AppCompatActivity() {

    private lateinit var desktop: DesktopView
    private lateinit var status: TextView
    private lateinit var statusAction: TextView
    private lateinit var keyBar: View
    private val modifierKeys = LinkedHashMap<Int, TextView>()
    private val held = LinkedHashSet<Int>()

    @Volatile
    private var socket: LocalSocket? = null
    @Volatile
    private var client: RfbClient? = null
    @Volatile
    private var connecting: LocalSocket? = null
    private var audio: DesktopAudio? = null
    private var lastClipboardFromDesktop: String? = null
    private var started = false
    @Volatile
    private var generation = 0

    private val clipboard by lazy { getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { sendClipboard() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val themeKey = PreferenceManager.getDefaultSharedPreferences(this).getString("terminal_theme", "catppuccin") ?: "catppuccin"
        TerminalColor.applyTheme(themeKey)
        Chrome.apply(TerminalColor.SCHEMES.getValue(TerminalColor.currentThemeId))
        val p = Chrome.palette
        val density = resources.displayMetrics.density

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Settings > Secure screen, as for the terminal: no screenshots, a blank preview in recent apps
        if (PreferenceManager.getDefaultSharedPreferences(this).getBoolean("secure_screen", false)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = p.bar
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        desktop = DesktopView(this)
        desktop.touchpad = PreferenceManager.getDefaultSharedPreferences(this)
            .getString(DesktopAddon.PREF_MOUSE_MODE, "touch") == "touchpad"
        desktop.heldModifiers = { held.toIntArray() }
        desktop.onModifiersUsed = { releaseModifiers() }
        desktop.onViewSizeChanged = { w, h, widthChanged -> if (widthChanged) requestDesktopSize(w, h) }

        status = TextView(this).apply {
            setTextColor(p.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            setPadding((24 * density).toInt(), 0, (24 * density).toInt(), 0)
        }
        statusAction = TextView(this).apply {
            setTextColor(p.onAccent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding((22 * density).toInt(), (12 * density).toInt(), (22 * density).toInt(), (12 * density).toInt())
            background = Chrome.buttonBackground(p, 18 * density, 0, filled = true)
            visibility = View.GONE
        }
        val statusBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(status)
            addView(statusAction, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (18 * density).toInt()
            })
        }

        keyBar = buildKeyBar(p, density)
        val surface = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(desktop, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(statusBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.bar)
            addView(surface, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(keyBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (46 * density).toInt()))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, 0, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }
        setContentView(root)
        CortexService.start(this) // keeps the app, and with it the desktop's processes, alive
    }

    private fun buildKeyBar(p: Chrome.Palette, density: Float): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((4 * density).toInt(), 0, (4 * density).toInt(), 0)
        }
        fun key(label: String, onClick: (TextView) -> Unit): TextView = TextView(this).apply {
            text = label
            setTextColor(p.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minWidth = (44 * density).toInt()
            setPadding((10 * density).toInt(), 0, (10 * density).toInt(), 0)
            background = Chrome.keyBackground(p, 8 * density, 0)
            setOnClickListener { onClick(this) }
            row.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (36 * density).toInt()).apply {
                marginStart = (3 * density).toInt()
                marginEnd = (3 * density).toInt()
            })
        }
        key(getString(R.string.desktop_key_keyboard)) { toggleKeyboard() }
        key("Esc") { desktop.sendKeysym(XKeySyms.ESCAPE) }
        key("Tab") { desktop.sendKeysym(XKeySyms.TAB) }
        for ((label, sym) in listOf("Ctrl" to XKeySyms.CONTROL_L, "Alt" to XKeySyms.ALT_L, "Super" to XKeySyms.SUPER_L)) {
            modifierKeys[sym] = key(label) { toggleModifier(sym) }
        }
        for ((label, sym) in listOf("←" to XKeySyms.LEFT, "↑" to XKeySyms.UP, "↓" to XKeySyms.DOWN, "→" to XKeySyms.RIGHT)) {
            key(label) { desktop.sendKeysym(sym) }
        }
        key(mouseLabel()) { view ->
            desktop.touchpad = !desktop.touchpad
            PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putString(DesktopAddon.PREF_MOUSE_MODE, if (desktop.touchpad) "touchpad" else "touch").apply()
            view.text = mouseLabel()
        }
        key("1:1") { desktop.resetZoom() }
        key(getString(R.string.desktop_stop)) { stopDesktop() }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(p.bar)
            addView(row, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    private fun mouseLabel() = getString(if (desktop.touchpad) R.string.desktop_mouse_touchpad else R.string.desktop_mouse_touch)

    private fun toggleModifier(sym: Int) {
        if (!held.remove(sym)) held.add(sym)
        refreshModifiers()
    }

    private fun releaseModifiers() {
        if (held.isEmpty()) return
        held.clear()
        refreshModifiers()
    }

    private fun refreshModifiers() {
        val p = Chrome.palette
        val density = resources.displayMetrics.density
        for ((sym, view) in modifierKeys) {
            val on = sym in held
            view.background = if (on) Chrome.activeKeyBackground(p, 8 * density, 0) else Chrome.keyBackground(p, 8 * density, 0)
            view.setTextColor(if (on) p.onAccent else p.text)
        }
    }

    private fun toggleKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        desktop.requestFocus()
        val visible = ViewCompat.getRootWindowInsets(desktop)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        if (visible) imm.hideSoftInputFromWindow(desktop.windowToken, 0)
        else imm.showSoftInput(desktop, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun onStart() {
        super.onStart()
        connectOrStart()
        clipboard.addPrimaryClipChangedListener(clipListener)
    }

    override fun onStop() {
        super.onStop()
        clipboard.removePrimaryClipChangedListener(clipListener)
        desktop.releaseEverything()
        disconnect()
    }

    private fun showStatus(text: String?, action: String? = null, onAction: (() -> Unit)? = null) {
        status.text = text ?: ""
        status.visibility = if (text == null) View.GONE else View.VISIBLE
        if (action != null && onAction != null) {
            statusAction.text = action
            statusAction.setOnClickListener { onAction() }
            statusAction.visibility = View.VISIBLE
        } else {
            statusAction.visibility = View.GONE
        }
    }

    /** Connects to the running desktop, or starts it first (off the main thread). */
    private fun connectOrStart() {
        if (DesktopAddon.installedKind(this) == null) {
            showStatus(getString(R.string.desktop_not_installed), getString(R.string.desktop_install_button)) {
                DesktopUi.showInstallDialog(this) { finish() }
            }
            return
        }
        val gen = ++generation
        showStatus(getString(R.string.desktop_starting))
        val app = applicationContext
        val (w, h) = viewDesktopSize()
        thread(name = "Cortex-DesktopStart") {
            var error: String? = null
            if (!DesktopAddon.isRunning(app)) {
                val result = DesktopAddon.run(app, "cortex-desktop start --size ${w}x$h")
                if (result.exitCode != 0 || !DesktopAddon.isRunning(app)) {
                    error = result.output.lines().takeLast(8).joinToString("\n").ifBlank { "exit ${result.exitCode}" }
                }
            }
            val failure = error ?: connect(gen)
            runOnUiThread {
                if (gen != generation || isFinishing) return@runOnUiThread
                if (failure != null) {
                    showStatus(getString(R.string.desktop_failed, failure), getString(R.string.desktop_retry)) { connectOrStart() }
                } else {
                    showStatus(null)
                    started = true
                    desktop.requestFocus()
                    sendClipboard()
                    audio = DesktopAudio(DesktopAddon.audioPipe(app)).also { it.start() }
                }
            }
        }
    }

    /** Opens the socket and makes the handshake; null, or what went wrong. */
    private fun connect(gen: Int): String? {
        val path = DesktopAddon.socket(this).absolutePath
        var lastError: Exception? = null
        repeat(20) {
            val s = LocalSocket()
            try {
                s.connect(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM))
                connecting = s // leaving the screen closes it, which ends a handshake that hangs
                // a server that accepts and then says nothing does not keep this thread forever
                s.soTimeout = HANDSHAKE_TIMEOUT_MS
                val c = RfbClient(s.inputStream, s.outputStream, listener(gen))
                c.handshake()
                s.soTimeout = 0
                connecting = null
                if (gen != generation) {
                    // the screen was left while it connected
                    try { s.close() } catch (e: Exception) { }
                    return "stopped"
                }
                // only now the view gets this client (an older attempt never replaces a newer one),
                // with the whole screen drawn from it
                desktop.client = c
                desktop.onResize(c.width, c.height)
                socket = s
                client = c
                thread(name = "Cortex-DesktopReader") {
                    try {
                        c.run()
                    } catch (e: Exception) {
                        Log.i(TAG, "desktop connection ended: ${e.message}")
                    }
                    runOnUiThread { if (gen == generation && !isFinishing) onDisconnected() }
                }
                // the size of this screen, if the server came up with another one
                val (w, h) = viewDesktopSize()
                if (c.width != w || c.height != h) c.requestSize(w, h)
                return null
            } catch (e: Exception) {
                lastError = e
                connecting = null
                try { s.close() } catch (ce: Exception) { }
                if (gen != generation) return "stopped"
                Thread.sleep(250)
            }
        }
        return lastError?.message ?: "no connection"
    }

    private fun listener(gen: Int) = object : RfbClient.Listener {
        override fun onResize(width: Int, height: Int) = desktop.onResize(width, height)
        override fun onUpdate(x: Int, y: Int, width: Int, height: Int) = desktop.onUpdate(x, y, width, height)
        override fun onUpdateEnd() = desktop.onUpdateEnd()
        override fun onCursor(width: Int, height: Int, hotX: Int, hotY: Int, pixels: IntArray?) =
            desktop.onCursor(width, height, hotX, hotY, pixels)

        override fun onClipboard(text: String) {
            runOnUiThread {
                if (gen != generation) return@runOnUiThread
                lastClipboardFromDesktop = text
                try {
                    clipboard.setPrimaryClip(ClipData.newPlainText("Cortex desktop", text))
                } catch (e: Exception) {
                    Log.w(TAG, "clipboard: ${e.message}")
                }
            }
        }
    }

    private fun onDisconnected() {
        disconnect()
        audio?.stop()
        audio = null
        showStatus(getString(R.string.desktop_disconnected), getString(R.string.desktop_retry)) { connectOrStart() }
    }

    private fun disconnect() {
        generation++
        connecting?.let { try { it.close() } catch (e: Exception) { } }
        connecting = null
        audio?.stop()
        audio = null
        val s = socket
        socket = null
        client = null
        desktop.client = null
        try {
            s?.shutdownInput()
        } catch (e: Exception) { }
        try {
            s?.close()
        } catch (e: Exception) { }
    }

    /** Android's clipboard to the desktop (unless it came from there). */
    private fun sendClipboard() {
        val c = client ?: return
        val text = try {
            clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        } catch (e: Exception) {
            null
        } ?: return
        if (text == lastClipboardFromDesktop) return
        thread(name = "Cortex-DesktopClipboard") {
            try {
                c.setClipboard(text)
            } catch (e: Exception) { }
        }
    }

    /** The size of the X screen for this view, as the settings scale it. */
    private fun viewDesktopSize(): Pair<Int, Int> {
        val metrics = resources.displayMetrics
        val w = if (desktop.width > 0) desktop.width else metrics.widthPixels
        val h = if (desktop.height > 0) desktop.height else metrics.heightPixels
        val scale = PreferenceManager.getDefaultSharedPreferences(this)
            .getString(DesktopAddon.PREF_SCALE, "100")?.toIntOrNull() ?: 100
        return DesktopAddon.desktopSize(w, h, metrics.density, scale)
    }

    private fun requestDesktopSize(w: Int, h: Int) {
        val c = client ?: return
        if (w <= 0 || h <= 0) return
        val (dw, dh) = viewDesktopSize()
        thread(name = "Cortex-DesktopResize") {
            try {
                c.requestSize(dw, dh)
            } catch (e: Exception) { }
        }
    }

    private fun stopDesktop() {
        disconnect()
        showStatus(getString(R.string.desktop_stopping))
        val app = applicationContext
        thread(name = "Cortex-DesktopStop") {
            DesktopAddon.run(app, "cortex-desktop stop", 30_000)
            runOnUiThread { finish() }
        }
    }

    companion object {
        private const val TAG = "DesktopActivity"
        private const val HANDSHAKE_TIMEOUT_MS = 10_000
    }
}
