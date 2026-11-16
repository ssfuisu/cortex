package org.cortex.terminal.setup

import android.animation.ObjectAnimator
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.text.method.PasswordTransformationMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
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
import androidx.appcompat.widget.SwitchCompat
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
import org.cortex.terminal.view.Chrome
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

        private const val BG = 0xFF090B10.toInt()
        private const val HEADER_TOP = 0xFF14182B.toInt()
        private const val HEADER_BOT = 0xFF0B0E17.toInt()
        private const val DOCK_BG = 0xFF0D1018.toInt()
        private const val CARD = 0xFF121622.toInt()
        private const val CARD_ON = 0xFF181E34.toInt()
        private const val INPUT_BG = 0xFF0E121C.toInt()
        private const val LINE = 0xFF21273A.toInt()
        private const val ACCENT = 0xFF7C5CFF.toInt()
        private const val BLUE = 0xFF3B82F6.toInt()
        private const val TEXT = 0xFFF8FAFC.toInt()
        private const val TEXT_SEC = 0xFFCBD5E1.toInt()
        private const val DIM = 0xFF94A3B8.toInt()
        private const val FAINT = 0xFF64748B.toInt()
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
    private lateinit var headerDivider: View
    private lateinit var stepLabel: TextView
    private lateinit var stepperRow: LinearLayout
    private var stepSegments: List<View> = emptyList()
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
    private lateinit var bottom: LinearLayout
    private lateinit var bottomDivider: View
    private lateinit var back: TextView
    private lateinit var next: TextView
    private var userError: TextView? = null

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun dp(v: Int) = dp(v.toFloat()).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = DOCK_BG
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false

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
        render(animate = false)
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

    // ---- layout & styling helpers -------------------------------------------------------------

    private fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeWidth: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (strokeWidth > 0) setStroke(strokeWidth, stroke)
    }

    private fun rippleRounded(fill: Int, radius: Float, stroke: Int = 0, strokeWidth: Int = 0, rippleColor: Int = 0x337C5CFF) =
        RippleDrawable(
            ColorStateList.valueOf(rippleColor),
            rounded(fill, radius, stroke, strokeWidth),
            rounded(Chrome.WHITE, radius)
        )

    private fun gradient(radius: Float) = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(ACCENT, BLUE)).apply {
        cornerRadius = radius
    }

    private fun text(size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }

    private fun monoText(size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        typeface = if (bold) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else Typeface.MONOSPACE
    }

    private fun buildLayout() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }

        header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(HEADER_TOP, HEADER_BOT))
            setPadding(dp(20), dp(16), dp(20), dp(18))
        }

        // Top brand + step pill row
        val brandRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val emblem = FrameLayout(this).apply {
            background = gradient(dp(10f))
            addView(ImageView(this@SetupActivity).apply {
                setImageResource(R.drawable.ic_terminal)
                imageTintList = ColorStateList.valueOf(Chrome.WHITE)
            }, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
        }
        brandRow.addView(emblem, LinearLayout.LayoutParams(dp(30), dp(30)))
        brandRow.addView(monoText(12f, TEXT_SEC, bold = true).apply {
            text = getString(R.string.app_name).uppercase()
            letterSpacing = 0.16f
            setPadding(dp(10), 0, 0, 0)
        })
        brandRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        stepLabel = monoText(11f, 0xFFC4B5FD.toInt(), bold = true).apply {
            setAllCaps(true)
            letterSpacing = 0.08f
            background = rounded(0x1F7C5CFF, dp(99f), 0x557C5CFF, dp(1))
            setPadding(dp(10), dp(5), dp(10), dp(5))
        }
        brandRow.addView(stepLabel)
        header.addView(brandRow)

        // 4-step segmented stepper track
        stepperRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val segs = mutableListOf<View>()
        for (i in 0 until STEPS) {
            val seg = View(this)
            stepperRow.addView(seg, LinearLayout.LayoutParams(0, dp(4), 1f).apply {
                if (i < STEPS - 1) marginEnd = dp(6)
            })
            segs += seg
        }
        stepSegments = segs
        header.addView(stepperRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(16)
        })

        title = text(25f, TEXT, bold = true).apply {
            setPadding(0, dp(16), 0, 0)
        }
        subtitle = text(14f, DIM).apply {
            setPadding(0, dp(6), 0, 0)
            setLineSpacing(0f, 1.25f)
        }
        header.addView(title)
        header.addView(subtitle)
        root.addView(header)

        headerDivider = View(this).apply { setBackgroundColor(LINE) }
        root.addView(headerDivider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1).coerceAtLeast(1)))

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(20))
        }
        scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(content)
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        bottomDivider = View(this).apply { setBackgroundColor(LINE) }
        root.addView(bottomDivider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1).coerceAtLeast(1)))

        bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(DOCK_BG)
            setPadding(dp(16), dp(12), dp(16), dp(14))
        }
        back = text(14.5f, TEXT_SEC, bold = true).apply {
            gravity = Gravity.CENTER
            background = rippleRounded(CARD, dp(14f), LINE, dp(1), 0x22FFFFFF)
            minHeight = dp(48)
            setPadding(dp(20), dp(12), dp(20), dp(12))
            setOnClickListener { goBack() }
        }
        next = text(15f, Chrome.WHITE, bold = true).apply {
            gravity = Gravity.CENTER
            background = RippleDrawable(ColorStateList.valueOf(0x44FFFFFF), gradient(dp(14f)), rounded(Chrome.WHITE, dp(14f)))
            minHeight = dp(48)
            setPadding(dp(28), dp(12), dp(28), dp(12))
            elevation = dp(4f)
        }
        bottom.addView(back)
        bottom.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        bottom.addView(next)
        root.addView(bottom)
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            header.setPadding(dp(20) + bars.left, dp(16) + bars.top, dp(20) + bars.right, dp(18))
            bottom.setPadding(dp(16) + bars.left, dp(12), dp(16) + bars.right, dp(14) + maxOf(bars.bottom, ime.bottom))
            insets
        }
    }

    private fun updateStepper(activeStep: Int, completedAll: Boolean = false) {
        stepSegments.forEachIndexed { i, seg ->
            val lp = seg.layoutParams as LinearLayout.LayoutParams
            lp.weight = if (i == activeStep && !completedAll) 1.7f else 1f
            seg.layoutParams = lp
            seg.background = when {
                completedAll -> rounded(OK, dp(99f))
                i < activeStep -> rounded(ACCENT, dp(99f))
                i == activeStep -> gradient(dp(99f))
                else -> rounded(LINE, dp(99f))
            }
        }
    }

    private fun setHeader(label: String?, t: String, sub: String, stepIndex: Int = step, completedAll: Boolean = false) {
        stepLabel.visibility = if (label == null) View.GONE else View.VISIBLE
        stepLabel.text = label ?: ""
        updateStepper(stepIndex, completedAll)
        title.text = t
        subtitle.text = sub
    }

    private fun setButtons(backText: String?, nextText: String?, nextEnabled: Boolean = true, onNext: (() -> Unit)? = null) {
        back.visibility = if (backText == null) View.GONE else View.VISIBLE
        back.text = backText ?: ""
        back.setOnClickListener { goBack() }
        next.visibility = if (nextText == null) View.GONE else View.VISIBLE
        next.text = nextText ?: ""
        next.alpha = if (nextEnabled) 1f else 0.38f
        next.isEnabled = nextEnabled
        next.setOnClickListener { onNext?.invoke() }
        bottom.visibility = if (backText == null && nextText == null) View.GONE else View.VISIBLE
        bottomDivider.visibility = bottom.visibility
    }

    private fun render(animate: Boolean = false) {
        if (animate) {
            content.alpha = 0f
            content.translationY = dp(10f)
        }
        content.removeAllViews()
        userError = null
        bar = null
        percentHero = null
        stepRows = emptyList()
        stepIcons = emptyList()
        stepContainers = emptyList()
        failureText = null
        when (step) {
            0 -> renderDistros()
            1 -> renderDesktops()
            2 -> renderUser()
            else -> renderInstall()
        }
        scroll.scrollTo(0, 0)
        if (animate) {
            content.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(220L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun stepText(n: Int) = getString(R.string.setup_step_of, n, STEPS)

    private fun goBack() {
        val s = SetupJob.state
        if (step == 3) {
            // a running installation goes on; a failed one can be left for another choice
            if (s != null && s.failure != null) {
                SetupJob.clear()
                step = 0
                render(animate = true)
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
        render(animate = true)
    }

    // ---- step 1: the distribution -------------------------------------------------------------

    private fun loadCatalog() {
        thread(name = "Cortex-Catalog") {
            val result = try { DistroInstaller.fetchCatalog() } catch (e: Exception) { null }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                catalog = result
                catalogFailed = result == null
                if (step == 0) render(animate = false)
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
            if (switching) confirmSwitch(distro) else { step = 1; render(animate = true) }
        }
    }

    private fun label(o: DistroOption) = if (o.version == "rolling") o.name else "${o.name} ${o.version}"

    private fun notice(message: String) = text(13f, WARN).apply {
        text = message
        background = rounded(0x14FBBF24, dp(14f), 0x4DFBBF24, dp(1))
        setPadding(dp(14), dp(12), dp(14), dp(12))
        setLineSpacing(0f, 1.2f)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
    }

    private fun card(selected: Boolean, enabled: Boolean = true) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = rippleRounded(
            if (selected) CARD_ON else CARD,
            dp(18f),
            if (selected) ACCENT else LINE,
            if (selected) dp(1.5f).coerceAtLeast(2) else dp(1),
            0x297C5CFF
        )
        setPadding(dp(14), dp(14), dp(14), dp(14))
        alpha = if (enabled) 1f else 0.42f
        isEnabled = enabled
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
    }

    private fun tintedTile(icon: Int, tintColor: Int): View = FrameLayout(this).apply {
        val fill = Chrome.withAlpha(tintColor, 34)
        val stroke = Chrome.withAlpha(tintColor, 115)
        val iconColor = Chrome.mix(tintColor, Chrome.WHITE, 0.35f)
        background = rounded(fill, dp(15f), stroke, dp(1))
        addView(ImageView(this@SetupActivity).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(iconColor)
        }, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(dp(50), dp(50)).apply { gravity = Gravity.TOP }
    }

    private fun selectionRing(selected: Boolean): View = FrameLayout(this).apply {
        val size = dp(22)
        layoutParams = LinearLayout.LayoutParams(size, size)
        if (selected) {
            background = gradient(dp(99f))
            addView(ImageView(this@SetupActivity).apply {
                setImageResource(R.drawable.ic_check)
                imageTintList = ColorStateList.valueOf(Chrome.WHITE)
            }, FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER))
        } else {
            background = rounded(0, dp(99f), 0xFF333B57.toInt(), dp(1.5f).coerceAtLeast(2))
        }
    }

    private fun chip(label: String, fg: Int, bg: GradientDrawable, mono: Boolean = false) =
        (if (mono) monoText(11f, fg, bold = true) else text(11f, fg, bold = true)).apply {
            text = label
            background = bg
            setPadding(dp(8), dp(3), dp(8), dp(3))
        }

    private fun badges(views: List<View>): View = WrapLayout(this, dp(6)).apply {
        views.forEach { addView(it) }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
    }

    private fun distroCard(o: DistroOption): View {
        val enabled = available(o)
        val selected = o.id == distro.id
        val c = card(selected, enabled)
        c.addView(tintedTile(o.icon, o.color))

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(13), 0, dp(10), 0) }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(text(16f, TEXT, bold = true).apply { text = o.name })
        if (o.version.isNotEmpty()) {
            val verLabel = if (o.version == "rolling") "rolling" else o.version
            row.addView(chip(verLabel, TEXT_SEC, rounded(0x14FFFFFF, dp(7f), LINE, dp(1)), mono = true).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(7) }
            })
        }
        col.addView(row)
        col.addView(monoText(11.5f, FAINT).apply {
            text = "${o.maintainer} · ${o.pm.id}"
            setPadding(0, dp(3), 0, 0)
        })
        col.addView(text(13.5f, TEXT_SEC).apply {
            text = getString(o.description)
            setPadding(0, dp(7), 0, 0)
            setLineSpacing(0f, 1.18f)
        })

        val b = mutableListOf<View>()
        if (o.recommended) b += chip(getString(R.string.setup_badge_recommended), Chrome.WHITE, gradient(dp(99f)))
        when {
            o.id == active -> b += chip(getString(R.string.setup_badge_in_use), OK, rounded(0x1A34D399, dp(99f), 0x6634D399, dp(1)))
            o.id in stored -> b += chip(getString(R.string.setup_badge_installed), OK, rounded(0x1A34D399, dp(99f), 0x6634D399, dp(1)))
            o.isBundled -> b += chip(getString(R.string.setup_badge_builtin), DIM, rounded(0x0DFFFFFF, dp(99f), LINE, dp(1)))
            else -> {
                val size = image(o)?.size?.let { android.text.format.Formatter.formatShortFileSize(this, it) } ?: "…"
                b += chip(getString(R.string.setup_download_size, size), DIM, rounded(0x0DFFFFFF, dp(99f), LINE, dp(1)))
            }
        }
        col.addView(badges(b))
        c.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val trailing = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
        }
        trailing.addView(selectionRing(selected))
        if (enabled && manage && o.id in stored && o.id != active) {
            val del = ImageView(this).apply {
                setImageResource(R.drawable.ic_delete)
                imageTintList = ColorStateList.valueOf(BAD)
                contentDescription = getString(R.string.setup_delete)
                background = rippleRounded(0x1AF87171, dp(10f), 0x44F87171, dp(1), 0x44F87171)
                setPadding(dp(7), dp(7), dp(7), dp(7))
                setOnClickListener { confirmDelete(o) }
            }
            trailing.addView(del, LinearLayout.LayoutParams(dp(32), dp(32)).apply { topMargin = dp(10) })
        }
        c.addView(trailing, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))

        if (enabled) {
            c.setOnClickListener { distro = o; render() }
            if (manage && o.id in stored && o.id != active) c.setOnLongClickListener { confirmDelete(o); true }
        }
        return c
    }

    private fun styleSetupDialog(dialog: AlertDialog, positiveColor: Int = ACCENT) {
        val hairline = dp(1).coerceAtLeast(1)
        dialog.window?.setBackgroundDrawable(
            android.graphics.drawable.InsetDrawable(rounded(CARD, dp(22f), LINE, hairline), dp(16))
        )
        dialog.setOnShowListener {
            dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.apply {
                setTextColor(TEXT)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            }
            dialog.findViewById<TextView>(android.R.id.message)?.apply {
                setTextColor(DIM)
                setLineSpacing(0f, 1.2f)
            }
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)?.apply {
                setTextColor(positiveColor)
                isAllCaps = false
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            }
            dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE)?.apply {
                setTextColor(DIM)
                isAllCaps = false
            }
        }
        dialog.show()
    }

    private fun confirmSwitch(o: DistroOption) {
        val current = active?.let { DistroSetup.option(it) }?.let { label(it) } ?: ""
        val dialog = AlertDialog.Builder(this)
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
            .create()
        styleSetupDialog(dialog)
    }

    private fun confirmDelete(o: DistroOption) {
        val dialog = AlertDialog.Builder(this)
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
            .create()
        styleSetupDialog(dialog, BAD)
    }

    // ---- step 2: a desktop --------------------------------------------------------------------

    private fun desktopAccent(kind: String?): Int = when (kind) {
        "xfce" -> 0xFF38BDF8.toInt()
        "lxqt" -> 0xFF60A5FA.toInt()
        "mate" -> 0xFF34D399.toInt()
        "kde" -> 0xFF818CF8.toInt()
        "light" -> 0xFFFBBF24.toInt()
        else -> 0xFF94A3B8.toInt()
    }

    private fun renderDesktops() {
        setHeader(stepText(2), getString(R.string.setup_desktop_title), getString(R.string.setup_desktop_subtitle))
        for (d in DistroSetup.DESKTOPS) content.addView(desktopCard(d))

        val enabled = desktop != null
        val switchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            alpha = if (enabled) 1f else 0.45f
            isEnabled = enabled
            background = rippleRounded(CARD, dp(18f), LINE, dp(1), 0x227C5CFF)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) }
        }
        val switchView = SwitchCompat(this).apply {
            isChecked = browser && enabled
            isEnabled = enabled
            val checked = intArrayOf(android.R.attr.state_checked)
            thumbTintList = ColorStateList(arrayOf(checked, intArrayOf()), intArrayOf(Chrome.WHITE, DIM))
            trackTintList = ColorStateList(arrayOf(checked, intArrayOf()), intArrayOf(ACCENT, LINE))
            setOnCheckedChangeListener { _, on -> browser = on }
        }
        switchRow.addView(text(15f, if (enabled) TEXT else DIM, bold = true).apply {
            text = getString(R.string.setup_browser)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(12) })
        switchRow.addView(switchView)
        switchRow.setOnClickListener { if (enabled) switchView.toggle() }
        content.addView(switchRow)

        setButtons(getString(R.string.setup_back), getString(R.string.setup_next)) { step = 2; render(animate = true) }
    }

    private fun desktopCard(d: DesktopOption): View {
        val selected = d.kind == desktop
        val c = card(selected)
        c.addView(tintedTile(d.icon, desktopAccent(d.kind)))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(13), 0, dp(10), 0) }
        col.addView(text(16f, TEXT, bold = true).apply {
            text = if (d.kind == null) getString(R.string.setup_terminal_only) else d.name
        })
        col.addView(text(13.5f, TEXT_SEC).apply {
            text = getString(d.description)
            setPadding(0, dp(5), 0, 0)
            setLineSpacing(0f, 1.18f)
        })
        val b = mutableListOf<View>()
        if (d.recommended) b += chip(getString(R.string.setup_badge_recommended), Chrome.WHITE, gradient(dp(99f)))
        if (d.heavy) b += chip(getString(R.string.setup_desktop_heavy), WARN, rounded(0x1AFBBF24, dp(99f), 0x55FBBF24, dp(1)))
        if (b.isNotEmpty()) col.addView(badges(b))
        c.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        c.addView(selectionRing(selected))
        c.setOnClickListener { desktop = d.kind; render() }
        return c
    }

    // ---- step 3: the user ---------------------------------------------------------------------

    private fun field(
        label: String,
        value: String,
        secret: Boolean,
        hint: String? = null,
        onChange: (String) -> Unit
    ): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(text(13f, TEXT_SEC, bold = true).apply {
            text = label
            setPadding(dp(4), 0, 0, dp(7))
        })

        var focused = false
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(INPUT_BG, dp(15f), LINE, dp(1))
            setPadding(dp(14), dp(2), dp(8), dp(2))
        }

        val edit = EditText(this).apply {
            setText(value)
            setTextColor(TEXT)
            setHintTextColor(FAINT)
            this.hint = hint
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
            typeface = if (secret) Typeface.DEFAULT else Typeface.MONOSPACE
            inputType = if (secret) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            }
            setSingleLine(true)
            background = null
            setPadding(0, dp(12), dp(8), dp(12))
            setOnFocusChangeListener { _, hasFocus ->
                focused = hasFocus
                container.background = rounded(
                    INPUT_BG,
                    dp(15f),
                    if (focused) ACCENT else LINE,
                    if (focused) dp(1.5f).coerceAtLeast(2) else dp(1)
                )
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    onChange(s?.toString() ?: "")
                    userError?.visibility = View.GONE
                }
            })
        }
        container.addView(edit, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        if (secret) {
            var revealed = false
            val toggle = ImageView(this).apply {
                setImageResource(R.drawable.ic_visibility)
                imageTintList = ColorStateList.valueOf(DIM)
                contentDescription = label
                background = rippleRounded(0, dp(20f), rippleColor = 0x22FFFFFF)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                setOnClickListener {
                    revealed = !revealed
                    val start = edit.selectionStart
                    val end = edit.selectionEnd
                    edit.transformationMethod = if (revealed) null else PasswordTransformationMethod.getInstance()
                    setImageResource(if (revealed) R.drawable.ic_visibility_off else R.drawable.ic_visibility)
                    imageTintList = ColorStateList.valueOf(if (revealed) ACCENT else DIM)
                    if (start >= 0 && end >= 0) edit.setSelection(start, end)
                }
            }
            container.addView(toggle, LinearLayout.LayoutParams(dp(42), dp(42)))
        }

        box.addView(container)
        box.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) }
        return box
    }

    private fun renderUser() {
        setHeader(stepText(3), getString(R.string.setup_user_title), getString(R.string.setup_user_subtitle))

        // Live shell identity card
        val previewCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(CARD, dp(16f), LINE, dp(1))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(18) }
        }
        val avatar = FrameLayout(this).apply {
            background = rounded(0x1F7C5CFF, dp(12f), 0x557C5CFF, dp(1))
            addView(ImageView(this@SetupActivity).apply {
                setImageResource(R.drawable.ic_user)
                imageTintList = ColorStateList.valueOf(0xFFC4B5FD.toInt())
            }, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
        }
        previewCard.addView(avatar, LinearLayout.LayoutParams(dp(36), dp(36)))

        val promptPreview = monoText(13.5f, TEXT, bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(12), 0, dp(8), 0)
        }
        val statusDot = View(this)
        previewCard.addView(promptPreview, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        previewCard.addView(statusDot, LinearLayout.LayoutParams(dp(8), dp(8)))
        content.addView(previewCard)

        // Live rule hints
        var ruleNameText: TextView? = null
        var ruleLenText: TextView? = null
        var ruleMatchText: TextView? = null

        fun refreshValidation() {
            val validUser = UserAccount.isValidName(userName)
            val validLen = password.length >= UserAccount.MIN_PASSWORD
            val validMatch = validLen && password == repeat
            val displayUser = userName.ifEmpty { "…" }
            promptPreview.text = "$displayUser@cortex:~$"
            promptPreview.setTextColor(if (validUser) OK else BAD)
            statusDot.background = rounded(if (validUser && validMatch) OK else WARN, dp(99f))
            ruleNameText?.setTextColor(if (validUser) OK else DIM)
            ruleLenText?.setTextColor(if (validLen) OK else DIM)
            ruleMatchText?.setTextColor(if (validMatch) OK else DIM)
        }

        content.addView(field(getString(R.string.setup_user_name), userName, secret = false) {
            userName = it.trim()
            refreshValidation()
        })
        content.addView(field(getString(R.string.setup_password), password, secret = true, hint = getString(R.string.setup_password_hint)) {
            password = it
            refreshValidation()
        })
        content.addView(field(getString(R.string.setup_password_repeat), repeat, secret = true) {
            repeat = it
            refreshValidation()
        })

        // Live compact requirement indicators reusing existing localized strings
        val rulesBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0x0DFFFFFF, dp(14f), LINE, dp(1))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
        }
        fun ruleRow(msgRes: Int): TextView = text(12f, DIM).apply {
            text = "• ${getString(msgRes)}"
            setPadding(0, dp(3), 0, dp(3))
            rulesBox.addView(this)
        }
        ruleNameText = ruleRow(R.string.setup_user_name_invalid)
        ruleLenText = ruleRow(R.string.setup_password_hint)
        ruleMatchText = ruleRow(R.string.setup_password_mismatch)
        content.addView(rulesBox)

        val err = text(13f, BAD, bold = true).apply {
            visibility = View.GONE
            background = rounded(0x1AF87171, dp(14f), 0x55F87171, dp(1))
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        userError = err
        content.addView(err, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        refreshValidation()

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
                    (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
                }
                SetupJob.start(this, distro, image(distro), desktop, browser && desktop != null, userName, password)
                password = ""
                repeat = ""
                step = 3
                render(animate = true)
            }
        }
    }

    // ---- step 4: the installation ---------------------------------------------------------------

    private var bar: ProgressBar? = null
    private var percentHero: TextView? = null
    private var stepRows: List<TextView> = emptyList()
    private var stepIcons: List<FrameLayout> = emptyList()
    private var stepContainers: List<LinearLayout> = emptyList()
    private var failureText: TextView? = null

    private fun renderInstall() {
        val s = SetupJob.state ?: run { step = 0; render(); return }
        val name = label(s.distro)

        // Summary badge card for chosen setup
        val summaryCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(CARD, dp(16f), LINE, dp(1))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
        }
        summaryCard.addView(tintedTile(s.distro.icon, s.distro.color))
        val metaCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        metaCol.addView(text(15.5f, TEXT, bold = true).apply { text = name })
        val deName = s.desktop?.let { k -> DistroSetup.DESKTOPS.firstOrNull { it.kind == k }?.name ?: k }
            ?: getString(R.string.setup_terminal_only)
        metaCol.addView(monoText(12f, DIM).apply {
            text = "${UserAccount.name(this@SetupActivity)} · $deName"
            setPadding(0, dp(3), 0, 0)
        })
        summaryCard.addView(metaCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val pct = monoText(22f, TEXT, bold = true).apply {
            text = "0%"
        }
        percentHero = pct
        summaryCard.addView(pct)
        content.addView(summaryCard)

        // Progress + timeline card
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD, dp(18f), LINE, dp(1))
            setPadding(dp(16), dp(16), dp(16), dp(12))
        }
        val trackDrawable = LayerDrawable(arrayOf(
            rounded(LINE, dp(99f)),
            ClipDrawable(gradient(dp(99f)), Gravity.START, ClipDrawable.HORIZONTAL)
        )).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
        }
        val p = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressDrawable = trackDrawable
            indeterminateTintList = ColorStateList.valueOf(ACCENT)
        }
        bar = p
        box.addView(p, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)).apply { bottomMargin = dp(14) })

        val rows = mutableListOf<TextView>()
        val icons = mutableListOf<FrameLayout>()
        val containers = mutableListOf<LinearLayout>()
        for (st in s.steps) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(10), dp(10), dp(10))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) }
            }
            val icon = FrameLayout(this)
            row.addView(icon, LinearLayout.LayoutParams(dp(22), dp(22)))
            val t = text(14.5f, TEXT).apply {
                text = getString(st.title)
                setPadding(dp(12), 0, 0, 0)
            }
            row.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            box.addView(row)
            rows += t
            icons += icon
            containers += row
        }
        stepRows = rows
        stepIcons = icons
        stepContainers = containers
        content.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val f = text(12.5f, BAD).apply {
            typeface = Typeface.MONOSPACE
            visibility = View.GONE
            background = rounded(0x14F87171, dp(14f), 0x4DF87171, dp(1))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setTextIsSelectable(true)
        }
        failureText = f
        content.addView(f, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        setHeader(stepText(4), getString(R.string.setup_installing, name), getString(R.string.setup_keep_open), stepIndex = 3)
        SetupJob.listen { show(it) }
    }

    private fun show(s: SetupJob.State) {
        if (step != 3) return
        val name = label(s.distro)
        val done = s.stepStates.count { it == SetupJob.StepState.DONE }
        val running = s.stepStates.indexOfFirst { it == SetupJob.StepState.RUNNING }
        val share = if (s.finished) 100 else (done * 100 + (if (running >= 0 && s.percent >= 0) s.percent else 0)) / s.steps.size
        bar?.let { b ->
            val indet = running >= 0 && s.percent < 0 && !s.finished
            b.isIndeterminate = indet
            if (!indet) {
                ObjectAnimator.ofInt(b, "progress", b.progress, share).apply {
                    duration = 260L
                    interpolator = DecelerateInterpolator()
                    start()
                }
            }
        }
        percentHero?.apply {
            text = "$share%"
            setTextColor(when {
                s.finished -> OK
                s.failure != null -> BAD
                else -> TEXT
            })
        }
        s.stepStates.forEachIndexed { i, st ->
            val icon = stepIcons.getOrNull(i) ?: return@forEachIndexed
            val row = stepRows[i]
            val container = stepContainers.getOrNull(i)
            icon.removeAllViews()
            val stepTitle = getString(s.steps[i].title)
            row.text = if (st == SetupJob.StepState.RUNNING && s.percent >= 0) "$stepTitle  ${s.percent}%" else stepTitle
            row.setTextColor(when (st) {
                SetupJob.StepState.WAITING -> FAINT
                SetupJob.StepState.RUNNING -> TEXT
                SetupJob.StepState.DONE -> TEXT_SEC
                SetupJob.StepState.FAILED -> BAD
            })
            row.typeface = Typeface.create("sans-serif-medium", if (st == SetupJob.StepState.RUNNING) Typeface.BOLD else Typeface.NORMAL)
            container?.background = when (st) {
                SetupJob.StepState.RUNNING -> rounded(CARD_ON, dp(12f), 0x667C5CFF, dp(1))
                SetupJob.StepState.FAILED -> rounded(0x14F87171, dp(12f), 0x44F87171, dp(1))
                else -> null
            }
            val v: View = when (st) {
                SetupJob.StepState.WAITING -> View(this).apply { background = rounded(0, dp(99f), LINE, dp(2)) }
                SetupJob.StepState.RUNNING -> ProgressBar(this).apply { indeterminateTintList = ColorStateList.valueOf(ACCENT) }
                SetupJob.StepState.DONE -> ImageView(this).apply { setImageResource(R.drawable.ic_setup_done); imageTintList = ColorStateList.valueOf(OK) }
                SetupJob.StepState.FAILED -> ImageView(this).apply { setImageResource(R.drawable.ic_setup_failed); imageTintList = ColorStateList.valueOf(BAD) }
            }
            icon.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        when {
            s.finished -> {
                failureText?.visibility = View.GONE
                setHeader(null, getString(R.string.setup_ready, name), getString(R.string.setup_ready_subtitle), stepIndex = 3, completedAll = true)
                if (s.desktop != null) {
                    setButtons(getString(R.string.setup_open_terminal), getString(R.string.setup_open_desktop)) { openTerminal(true) }
                    back.setOnClickListener { openTerminal(false) }
                } else {
                    setButtons(null, getString(R.string.setup_open_terminal)) { openTerminal(false) }
                }
            }
            s.failure != null -> {
                failureText?.apply { text = s.failure; visibility = View.VISIBLE }
                setHeader(stepText(4), getString(R.string.setup_failed), getString(R.string.setup_installing, name), stepIndex = 3)
                setButtons(getString(R.string.setup_back), getString(R.string.setup_retry)) { SetupJob.retry(this) }
                back.setOnClickListener { goBack() }
            }
            else -> {
                failureText?.visibility = View.GONE
                setHeader(stepText(4), getString(R.string.setup_installing, name), getString(R.string.setup_keep_open), stepIndex = 3)
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
