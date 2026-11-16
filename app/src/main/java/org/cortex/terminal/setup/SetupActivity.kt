package org.cortex.terminal.setup

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.cortex.terminal.MainActivity
import org.cortex.terminal.R
import org.cortex.terminal.desktop.DesktopActivity
import org.cortex.terminal.runtime.DesktopOption
import org.cortex.terminal.runtime.DistroImage
import org.cortex.terminal.runtime.DistroInstaller
import org.cortex.terminal.runtime.DistroOption
import org.cortex.terminal.runtime.DistroSetup
import org.cortex.terminal.runtime.UserAccount
import kotlin.concurrent.thread

/**
 * The setup of a Linux system, in four steps: the distribution, a desktop, the user and the
 * installation. The first start of the app opens it; Settings › Linux distribution opens it again
 * ([EXTRA_MANAGE]) to install another one or switch between the installed ones.
 */
class SetupActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MANAGE = "org.cortex.terminal.setup.MANAGE"
        private const val STEPS = 4

        private const val VIOLET = 0xFF7C3AED.toInt()
        private const val BLUE = 0xFF2563EB.toInt()
        private const val BG = 0xFF0C0D1B.toInt()
        private const val CARD = 0xFF16172B.toInt()
        private const val CARD_ON = 0xFF1D1B3C.toInt()
        private const val LINE = 0xFF292B48.toInt()
        private const val TEXT = 0xFFF2F2FA.toInt()
        private const val DIM = 0xFFA0A3C2.toInt()
        private const val OK = 0xFF34D399.toInt()
        private const val BAD = 0xFFF87171.toInt()
        private const val WARN = 0xFFFBBF24.toInt()
    }

    private val manage by lazy { intent.getBooleanExtra(EXTRA_MANAGE, false) }

    private var step = 0
    private var distro: DistroOption = DistroSetup.DISTROS.first()
    private var desktop: String? = "xfce"
    private var browser = true
    private var userName = UserAccount.DEFAULT_NAME
    private var password = ""
    private var repeat = ""

    private var catalog: List<DistroImage>? = null
    private var catalogFailed = false
    private var active: String? = null
    private var stored: List<String> = emptyList()

    private lateinit var header: LinearLayout
    private lateinit var stepLabel: TextView
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
    private lateinit var bottom: LinearLayout
    private lateinit var back: TextView
    private lateinit var next: TextView
    private var userError: TextView? = null

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun dp(v: Int) = dp(v.toFloat()).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = BG
        userName = UserAccount.name(this)
        active = DistroInstaller.active(this)?.id
        stored = DistroInstaller.stored(this)
        if (manage) active?.let { a -> DistroSetup.option(a)?.let { distro = it } }
        savedInstanceState?.let { b ->
            step = b.getInt("step", 0)
            b.getString("distro")?.let { id -> DistroSetup.option(id)?.let { distro = it } }
            desktop = if (b.containsKey("desktop")) b.getString("desktop") else desktop
            browser = b.getBoolean("browser", true)
            userName = b.getString("user", userName)
        }
        buildLayout()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goBack()
        })
        loadCatalog()
        if (SetupJob.state != null) step = 3
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("step", step)
        outState.putString("distro", distro.id)
        outState.putString("desktop", desktop)
        outState.putBoolean("browser", browser)
        outState.putString("user", userName)
    }

    override fun onDestroy() {
        SetupJob.listen(null)
        super.onDestroy()
    }

    // ---- layout ------------------------------------------------------------------------------

    private fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeWidth: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (strokeWidth > 0) setStroke(strokeWidth, stroke)
    }

    private fun gradient(radius: Float) = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(VIOLET, BLUE)).apply {
        cornerRadius = radius
    }

    private fun text(size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }

    private fun buildLayout() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }
        header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(VIOLET, BLUE)).apply {
                cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, dp(28f), dp(28f), dp(28f), dp(28f))
            }
            setPadding(dp(24), dp(20), dp(24), dp(26))
        }
        stepLabel = text(12f, 0xCCFFFFFF.toInt(), bold = true).apply { letterSpacing = 0.14f; setAllCaps(true) }
        title = text(26f, 0xFFFFFFFF.toInt(), bold = true).apply { setPadding(0, dp(8), 0, 0) }
        subtitle = text(14f, 0xDDFFFFFF.toInt()).apply { setPadding(0, dp(8), 0, 0); setLineSpacing(0f, 1.15f) }
        header.addView(stepLabel)
        header.addView(title)
        header.addView(subtitle)
        root.addView(header)

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(18))
        }
        scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(14))
        }
        back = text(15f, DIM, bold = true).apply {
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setOnClickListener { goBack() }
        }
        next = text(15f, 0xFFFFFFFF.toInt(), bold = true).apply {
            gravity = Gravity.CENTER
            background = gradient(dp(26f))
            setPadding(dp(28), dp(14), dp(28), dp(14))
            elevation = dp(3f)
        }
        bottom.addView(back)
        bottom.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        bottom.addView(next)
        root.addView(bottom)
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            header.setPadding(dp(24) + bars.left, dp(20) + bars.top, dp(24) + bars.right, dp(26))
            bottom.setPadding(dp(16) + bars.left, dp(10), dp(16) + bars.right, dp(14) + maxOf(bars.bottom, ime.bottom))
            insets
        }
    }

    private fun setHeader(label: String?, t: String, sub: String) {
        stepLabel.visibility = if (label == null) View.GONE else View.VISIBLE
        stepLabel.text = label ?: ""
        title.text = t
        subtitle.text = sub
    }

    private fun setButtons(backText: String?, nextText: String?, nextEnabled: Boolean = true, onNext: (() -> Unit)? = null) {
        back.visibility = if (backText == null) View.INVISIBLE else View.VISIBLE
        back.text = backText ?: ""
        next.visibility = if (nextText == null) View.GONE else View.VISIBLE
        next.text = nextText ?: ""
        next.alpha = if (nextEnabled) 1f else 0.4f
        next.isEnabled = nextEnabled
        next.setOnClickListener { onNext?.invoke() }
    }

    private fun render() {
        content.removeAllViews()
        userError = null
        when (step) {
            0 -> renderDistros()
            1 -> renderDesktops()
            2 -> renderUser()
            else -> renderInstall()
        }
        scroll.scrollTo(0, 0)
    }

    private fun stepText(n: Int) = getString(R.string.setup_step_of, n, STEPS)

    private fun goBack() {
        val s = SetupJob.state
        if (step == 3) {
            // a running installation goes on; a failed one can be left for another choice
            if (s != null && s.failure != null) {
                SetupJob.clear()
                step = 0
                render()
            } else if (s == null || s.finished) {
                finish()
            }
            return
        }
        if (step == 0) {
            if (manage) finish() else moveTaskToBack(true)
            return
        }
        step--
        render()
    }

    // ---- step 1: the distribution -------------------------------------------------------------

    private fun loadCatalog() {
        thread(name = "Cortex-Catalog") {
            val result = try { DistroInstaller.fetchCatalog() } catch (e: Exception) { null }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                catalog = result
                catalogFailed = result == null
                if (step == 0) render()
            }
        }
    }

    private fun options(): List<DistroOption> {
        val armhf = DistroInstaller.arch() == "armhf"
        return DistroSetup.DISTROS.filter { !armhf || it.isBundled || it.id == "ubuntu-26.04" || it.id == "debian-13" }
    }

    private fun image(o: DistroOption): DistroImage? = catalog?.firstOrNull { it.id == o.id }

    private fun available(o: DistroOption) = o.isBundled || o.id == active || o.id in stored || image(o) != null

    private fun renderDistros() {
        setHeader(stepText(1), getString(R.string.setup_distro_title), getString(R.string.setup_distro_subtitle))
        if (catalogFailed) content.addView(notice(getString(R.string.setup_catalog_failed)))
        if (!available(distro)) distro = DistroSetup.DISTROS.first()
        for (o in options()) content.addView(distroCard(o))
        val switching = manage && distro.id != active && distro.id in stored
        val nextText = getString(if (switching) R.string.setup_switch else R.string.setup_next)
        setButtons(if (manage) getString(R.string.setup_back) else null, nextText, nextEnabled = !(manage && distro.id == active)) {
            if (switching) confirmSwitch(distro) else { step = 1; render() }
        }
    }

    private fun label(o: DistroOption) = if (o.version == "rolling") o.name else "${o.name} ${o.version}"

    private fun notice(message: String) = text(13.5f, WARN).apply {
        text = message
        background = rounded(0x1AFBBF24, dp(14f), 0x55FBBF24, dp(1))
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
    }

    private fun card(selected: Boolean, enabled: Boolean = true) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = rounded(if (selected) CARD_ON else CARD, dp(20f), if (selected) VIOLET else LINE, if (selected) dp(2) else dp(1))
        setPadding(dp(16), dp(16), dp(16), dp(16))
        alpha = if (enabled) 1f else 0.45f
        isEnabled = enabled
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
    }

    private fun tile(icon: Int, color: Int?): View = FrameLayout(this).apply {
        background = if (color != null) rounded(color, dp(16f)) else gradient(dp(16f))
        addView(ImageView(this@SetupActivity).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
        }, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(dp(54), dp(54))
    }

    private fun chip(label: String, fg: Int, bg: GradientDrawable) = text(11.5f, fg, bold = true).apply {
        text = label
        background = bg
        setPadding(dp(10), dp(4), dp(10), dp(4))
    }

    private fun badges(views: List<View>): View = WrapLayout(this, dp(6)).apply {
        views.forEach { addView(it) }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
    }

    private fun distroCard(o: DistroOption): View {
        val enabled = available(o)
        val c = card(o.id == distro.id, enabled)
        c.addView(tile(o.icon, o.color))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0) }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(text(17f, TEXT, bold = true).apply { text = o.name })
        if (o.version.isNotEmpty()) {
            row.addView(chip(if (o.version == "rolling") "rolling" else "v. ${o.version}", DIM, rounded(0, dp(99f), LINE, dp(1))).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) }
            })
        }
        col.addView(row)
        col.addView(text(12f, DIM).apply { text = o.maintainer; setPadding(0, dp(2), 0, 0) })
        col.addView(text(14f, 0xFFD6D7EA.toInt()).apply { text = getString(o.description); setPadding(0, dp(8), 0, 0); setLineSpacing(0f, 1.12f) })
        val b = mutableListOf<View>()
        if (o.recommended) b += chip(getString(R.string.setup_badge_recommended), 0xFFFFFFFF.toInt(), gradient(dp(99f)))
        when {
            o.id == active -> b += chip(getString(R.string.setup_badge_in_use), OK, rounded(0x1A34D399, dp(99f), 0x6634D399, dp(1)))
            o.id in stored -> b += chip(getString(R.string.setup_badge_installed), OK, rounded(0x1A34D399, dp(99f), 0x6634D399, dp(1)))
            o.isBundled -> b += chip(getString(R.string.setup_badge_builtin), DIM, rounded(0, dp(99f), LINE, dp(1)))
            else -> {
                val size = image(o)?.size?.let { android.text.format.Formatter.formatShortFileSize(this, it) } ?: "…"
                b += chip(getString(R.string.setup_download_size, size), DIM, rounded(0, dp(99f), LINE, dp(1)))
            }
        }
        col.addView(badges(b))
        c.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (enabled) {
            c.setOnClickListener { distro = o; render() }
            if (manage && o.id in stored && o.id != active) c.setOnLongClickListener { confirmDelete(o); true }
        }
        return c
    }

    private fun confirmSwitch(o: DistroOption) {
        val current = active?.let { DistroSetup.option(it) }?.let { label(it) } ?: ""
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.setup_switch_title, label(o)))
            .setMessage(getString(R.string.setup_switch_message, current))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                thread(name = "Cortex-Switch") {
                    val ok = try {
                        SetupJob.endSessions(applicationContext)
                        DistroInstaller.makeActive(applicationContext, o.id)
                    } catch (e: Exception) {
                        android.util.Log.e("SetupActivity", "Switch to ${o.id} failed", e)
                        false
                    }
                    runOnUiThread {
                        if (ok) openTerminal(false) else android.widget.Toast.makeText(this, R.string.setup_failed, android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(o: DistroOption) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.setup_delete_title, label(o)))
            .setMessage(R.string.setup_delete_message)
            .setPositiveButton(R.string.setup_delete) { _, _ ->
                thread(name = "Cortex-Delete") {
                    DistroInstaller.delete(applicationContext, o.id)
                    runOnUiThread {
                        stored = DistroInstaller.stored(this)
                        render()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---- step 2: a desktop --------------------------------------------------------------------

    private fun renderDesktops() {
        setHeader(stepText(2), getString(R.string.setup_desktop_title), getString(R.string.setup_desktop_subtitle))
        for (d in DistroSetup.DESKTOPS) content.addView(desktopCard(d))
        val firefox = CheckBox(this).apply {
            text = getString(R.string.setup_browser)
            setTextColor(if (desktop != null) TEXT else DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            buttonTintList = ColorStateList.valueOf(VIOLET)
            isChecked = browser && desktop != null
            isEnabled = desktop != null
            setOnCheckedChangeListener { _, on -> browser = on }
            background = rounded(CARD, dp(20f), LINE, dp(1))
            setPadding(dp(10), dp(12), dp(16), dp(12))
        }
        content.addView(firefox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setButtons(getString(R.string.setup_back), getString(R.string.setup_next)) { step = 2; render() }
    }

    private fun desktopCard(d: DesktopOption): View {
        val c = card(d.kind == desktop)
        c.addView(tile(d.icon, null))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0) }
        col.addView(text(17f, TEXT, bold = true).apply { text = if (d.kind == null) getString(R.string.setup_terminal_only) else d.name })
        col.addView(text(14f, 0xFFD6D7EA.toInt()).apply { text = getString(d.description); setPadding(0, dp(6), 0, 0); setLineSpacing(0f, 1.12f) })
        val b = mutableListOf<View>()
        if (d.recommended) b += chip(getString(R.string.setup_badge_recommended), 0xFFFFFFFF.toInt(), gradient(dp(99f)))
        if (d.heavy) b += chip(getString(R.string.setup_desktop_heavy), WARN, rounded(0x1AFBBF24, dp(99f), 0x55FBBF24, dp(1)))
        if (b.isNotEmpty()) col.addView(badges(b))
        c.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        c.setOnClickListener { desktop = d.kind; render() }
        return c
    }

    // ---- step 3: the user ---------------------------------------------------------------------

    private fun field(label: String, value: String, secret: Boolean, hint: String? = null, onChange: (String) -> Unit): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(text(13f, DIM, bold = true).apply { text = label; setPadding(dp(4), 0, 0, dp(6)) })
        val edit = EditText(this).apply {
            setText(value)
            setTextColor(TEXT)
            setHintTextColor(0xFF5C5F82.toInt())
            this.hint = hint
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setSingleLine(true)
            background = rounded(CARD, dp(16f), LINE, dp(1))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    onChange(s?.toString() ?: "")
                    userError?.visibility = View.GONE
                }
            })
        }
        box.addView(edit)
        box.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(16) }
        return box
    }

    private fun renderUser() {
        setHeader(stepText(3), getString(R.string.setup_user_title), getString(R.string.setup_user_subtitle))
        content.addView(field(getString(R.string.setup_user_name), userName, secret = false) { userName = it.trim() })
        content.addView(field(getString(R.string.setup_password), password, secret = true, hint = getString(R.string.setup_password_hint)) { password = it })
        content.addView(field(getString(R.string.setup_password_repeat), repeat, secret = true) { repeat = it })
        val err = text(13.5f, BAD).apply { visibility = View.GONE; setPadding(dp(4), 0, dp(4), 0) }
        userError = err
        content.addView(err)
        setButtons(getString(R.string.setup_back), getString(R.string.setup_install)) {
            val problem = when {
                !UserAccount.isValidName(userName) -> getString(R.string.setup_user_name_invalid)
                password.length < UserAccount.MIN_PASSWORD -> getString(R.string.setup_password_hint)
                password != repeat -> getString(R.string.setup_password_mismatch)
                else -> null
            }
            if (problem != null) {
                err.text = problem
                err.visibility = View.VISIBLE
            } else {
                currentFocus?.let { v ->
                    (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
                }
                SetupJob.start(this, distro, image(distro), desktop, browser && desktop != null, userName, password)
                password = ""
                repeat = ""
                step = 3
                render()
            }
        }
    }

    // ---- step 4: the installation ---------------------------------------------------------------

    private var bar: ProgressBar? = null
    private var stepRows: List<TextView> = emptyList()
    private var stepIcons: List<FrameLayout> = emptyList()
    private var failureText: TextView? = null

    private fun renderInstall() {
        val s = SetupJob.state ?: run { step = 0; render(); return }
        val name = label(s.distro)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD, dp(20f), LINE, dp(1))
            setPadding(dp(18), dp(18), dp(18), dp(10))
        }
        val p = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(VIOLET)
            progressBackgroundTintList = ColorStateList.valueOf(LINE)
            indeterminateTintList = ColorStateList.valueOf(VIOLET)
        }
        bar = p
        box.addView(p, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)).apply { bottomMargin = dp(14) })
        val rows = mutableListOf<TextView>()
        val icons = mutableListOf<FrameLayout>()
        for (st in s.steps) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
            val icon = FrameLayout(this)
            row.addView(icon, LinearLayout.LayoutParams(dp(22), dp(22)))
            val t = text(15f, TEXT).apply { text = getString(st.title); setPadding(dp(14), 0, 0, 0) }
            row.addView(t)
            box.addView(row)
            rows += t
            icons += icon
        }
        stepRows = rows
        stepIcons = icons
        content.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val f = text(12.5f, BAD).apply {
            typeface = Typeface.MONOSPACE
            visibility = View.GONE
            background = rounded(0x14F87171, dp(14f), 0x44F87171, dp(1))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setTextIsSelectable(true)
        }
        failureText = f
        content.addView(f, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        setHeader(stepText(4), getString(R.string.setup_installing, name), getString(R.string.setup_keep_open))
        SetupJob.listen { show(it) }
    }

    private fun show(s: SetupJob.State) {
        if (step != 3) return
        val name = label(s.distro)
        bar?.let { b ->
            val done = s.stepStates.count { it == SetupJob.StepState.DONE }
            val running = s.stepStates.indexOfFirst { it == SetupJob.StepState.RUNNING }
            b.isIndeterminate = running >= 0 && s.percent < 0
            // the share of the steps done, and of the running one
            val share = (done * 100 + (if (running >= 0 && s.percent >= 0) s.percent else 0)) / s.steps.size
            b.progress = if (s.finished) 100 else share
        }
        s.stepStates.forEachIndexed { i, st ->
            val icon = stepIcons.getOrNull(i) ?: return@forEachIndexed
            val row = stepRows[i]
            icon.removeAllViews()
            val title = getString(s.steps[i].title)
            row.text = if (st == SetupJob.StepState.RUNNING && s.percent >= 0) "$title  ${s.percent}%" else title
            row.setTextColor(if (st == SetupJob.StepState.WAITING) DIM else TEXT)
            val v: View = when (st) {
                SetupJob.StepState.WAITING -> View(this).apply { background = rounded(0, dp(11f), LINE, dp(2)) }
                SetupJob.StepState.RUNNING -> ProgressBar(this).apply { indeterminateTintList = ColorStateList.valueOf(VIOLET) }
                SetupJob.StepState.DONE -> ImageView(this).apply { setImageResource(R.drawable.ic_setup_done); imageTintList = ColorStateList.valueOf(OK) }
                SetupJob.StepState.FAILED -> ImageView(this).apply { setImageResource(R.drawable.ic_setup_failed); imageTintList = ColorStateList.valueOf(BAD) }
            }
            icon.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        when {
            s.finished -> {
                failureText?.visibility = View.GONE
                setHeader(null, getString(R.string.setup_ready, name), getString(R.string.setup_ready_subtitle))
                if (s.desktop != null) {
                    setButtons(null, getString(R.string.setup_open_desktop)) { openTerminal(true) }
                    back.visibility = View.VISIBLE
                    back.text = getString(R.string.setup_open_terminal)
                    back.setOnClickListener { openTerminal(false) }
                } else {
                    setButtons(null, getString(R.string.setup_open_terminal)) { openTerminal(false) }
                }
            }
            s.failure != null -> {
                failureText?.apply { text = s.failure; visibility = View.VISIBLE }
                setHeader(stepText(4), getString(R.string.setup_failed), getString(R.string.setup_installing, name))
                setButtons(getString(R.string.setup_back), getString(R.string.setup_retry)) { SetupJob.retry(this) }
                back.setOnClickListener { goBack() }
            }
            else -> {
                failureText?.visibility = View.GONE
                setHeader(stepText(4), getString(R.string.setup_installing, name), getString(R.string.setup_keep_open))
                setButtons(null, null)
            }
        }
    }

    private fun openTerminal(desktop: Boolean) {
        SetupJob.clear()
        val main = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (desktop) startActivities(arrayOf(main, Intent(this, DesktopActivity::class.java))) else startActivity(main)
        finish()
    }
}

/** Lays its children out in rows, wrapping to a new row when one is full (the badges of a card) */
private class WrapLayout(context: android.content.Context, private val gap: Int) : ViewGroup(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        var x = 0
        var y = 0
        var rowHeight = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            measureChild(c, MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + c.measuredWidth > width) {
                x = 0
                y += rowHeight + gap
                rowHeight = 0
            }
            x += c.measuredWidth + gap
            rowHeight = maxOf(rowHeight, c.measuredHeight)
        }
        setMeasuredDimension(width, y + rowHeight)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        var x = 0
        var y = 0
        var rowHeight = 0
        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (x > 0 && x + c.measuredWidth > width) {
                x = 0
                y += rowHeight + gap
                rowHeight = 0
            }
            val left = if (rtl) width - x - c.measuredWidth else x
            c.layout(left, y, left + c.measuredWidth, y + c.measuredHeight)
            x += c.measuredWidth + gap
            rowHeight = maxOf(rowHeight, c.measuredHeight)
        }
    }
}
