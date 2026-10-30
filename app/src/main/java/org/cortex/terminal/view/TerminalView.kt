package org.cortex.terminal.view

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import java.io.File
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Rect
import android.graphics.Typeface
import android.text.InputType
import android.os.Build
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.SurroundingText
import androidx.annotation.RequiresApi
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import android.widget.LinearLayout
import android.widget.OverScroller
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import android.system.Os
import org.cortex.terminal.R
import org.cortex.terminal.emulator.KeyMapper
import org.cortex.terminal.emulator.TerminalBuffer
import org.cortex.terminal.emulator.TerminalColor
import org.cortex.terminal.emulator.TerminalEmulator
import org.cortex.terminal.emulator.TerminalRow
import org.cortex.terminal.emulator.TerminalSearch
import org.cortex.terminal.session.TerminalSession
import kotlin.concurrent.withLock
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var session: TerminalSession? = null
        set(value) {
            val previous = field
            field?.onRedraw = null
            field?.emulator?.onScreenUpdate = null
            field = value
            value?.onRedraw = { postInvalidate() }
            value?.emulator?.onScreenUpdate = onEmulatorScreenUpdate
            if (previous !== value) {
                // The selection is the old session's: delete over it would act on the new one's line
                clearSelection()
                previous?.setFocused(false)
                value?.setFocused(hasWindowFocus())
                cursorBlinkOn = true
            }
            isCtrlPressed = false
            isAltPressed = false
            searchHits = emptyList()
            searchIndex = -1
            searchQuery = ""
            urlHits = emptyList()
            urlRefreshCounter = 0
            updateTerminalDimensions()
            onSearchChanged?.invoke("", -1, 0)
            postInvalidate()
        }

    // New output (on the PTY reader thread): redraw, and now and then re-scan for links and search
    // hits, which keeps them fresh without paying the regex cost on every chunk.
    private val onEmulatorScreenUpdate: () -> Unit = {
        urlRefreshCounter++
        if (urlRefreshCounter % 90 == 0) {
            try { refreshUrlLinks() } catch (_: Exception) {}
            if (searchQuery.isNotEmpty()) {
                try { startSearch(searchQuery) } catch (_: Exception) {}
            }
        }
        postScreenUpdate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        session?.onRedraw = { postInvalidate() }
        session?.emulator?.onScreenUpdate = onEmulatorScreenUpdate
        updateTerminalDimensions()
        postInvalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        scroller.abortAnimation()
        removeCallbacks(flingRunnable)
        selectionAutoScrollDirection = 0
        removeCallbacks(selectionAutoScrollRunnable)
        removeCallbacks(syncUpdateTimeoutRunnable)
        stopCursorBlink()
        hideActionPopup()
        isSelecting = false
        session?.setFocused(false)
        session?.onRedraw = null
        session?.emulator?.onScreenUpdate = null
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        session?.setFocused(hasWindowFocus)
        if (hasWindowFocus) invalidate() else stopCursorBlink()
    }

    // Redraws for new output. While the program draws a synchronized frame (mode 2026) the previous
    // frame stays on screen until the new one is complete or times out. Runs on the PTY reader thread.
    private fun postScreenUpdate() {
        val wait = session?.emulator?.synchronizedUpdateRemainingMs() ?: 0L
        removeCallbacks(syncUpdateTimeoutRunnable)
        if (wait > 0L) {
            postDelayed(syncUpdateTimeoutRunnable, wait)
        } else {
            requestRedraw()
        }
    }

    private val syncUpdateTimeoutRunnable = Runnable { postScreenUpdate() }

    // At most one redraw request on its way to the UI thread. Output arrives in chunks of up to 4 KiB,
    // and a request per chunk woke the UI thread thousands of times a second during fast output,
    // although it draws once per frame anyway (and then draws what has arrived by then).
    private val redrawPending = java.util.concurrent.atomic.AtomicBoolean(false)
    private val redrawRunnable = Runnable {
        redrawPending.set(false)
        invalidate()
    }

    private fun requestRedraw() {
        if (redrawPending.compareAndSet(false, true) && !post(redrawRunnable)) {
            redrawPending.set(false)
        }
    }

    private companion object {
        const val CURSOR_BLINK_MS = 500L
    }

    // Blink phase of a blinking cursor (DECSCUSR 1, 3, 5). Main thread only.
    private var cursorBlinkOn = true
    private var isCursorBlinkScheduled = false

    private val cursorBlinkRunnable = object : Runnable {
        override fun run() {
            if (session?.emulator?.isCursorBlinking != true || !hasWindowFocus()) {
                stopCursorBlink()
                return
            }
            cursorBlinkOn = !cursorBlinkOn
            invalidate()
            postDelayed(this, CURSOR_BLINK_MS)
        }
    }

    private fun stopCursorBlink() {
        removeCallbacks(cursorBlinkRunnable)
        isCursorBlinkScheduled = false
        if (!cursorBlinkOn) {
            cursorBlinkOn = true
            invalidate()
        }
    }

    // Keeps a blinking cursor visible while typing: every key starts the blink again from "on".
    private fun restartCursorBlink() {
        if (!isCursorBlinkScheduled) return
        removeCallbacks(cursorBlinkRunnable)
        cursorBlinkOn = true
        postDelayed(cursorBlinkRunnable, CURSOR_BLINK_MS)
        invalidate()
    }

    private val cursorThickness = 2f * resources.displayMetrics.density

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = 28f
        // Runs of characters are drawn in one call (drawRowText): no ligatures or kerning, so every
        // character stays in its cell exactly as when they were drawn one by one.
        fontFeatureSettings = "'liga' 0, 'clig' 0, 'calt' 0, 'kern' 0"
    }

    private val bgPaint = Paint()
    private val cursorPaint = Paint().apply {
        color = TerminalColor.CURSOR_COLOR
    }

    // Text Selection Properties
    var isSelecting = false
        private set
    var selectStartRow = 0
    var selectStartCol = 0
    var selectEndRow = 0
    var selectEndCol = 0

    private enum class ActiveHandle {
        NONE, START, END
    }
    private var activeHandle = ActiveHandle.NONE

    private var selectionAutoScrollDirection = 0 // 1 for UP into history, -1 for DOWN towards newest
    private var lastSelectionTouchX = 0f
    private var lastSelectionTouchY = 0f

    private val selectionAutoScrollRunnable = object : Runnable {
        override fun run() {
            if (activeHandle == ActiveHandle.NONE || !isSelecting || selectionAutoScrollDirection == 0) {
                return
            }

            // slightly faster than the medium speed: ~25 lines per second with a 40ms tick
            val linesToScroll = if (selectionAutoScrollDirection > 0) {
                if (lastSelectionTouchY <= 0f) 2 else 1
            } else {
                if (lastSelectionTouchY >= height) 2 else 1
            }

            val oldOffset = scrollOffset
            val newOffset = if (selectionAutoScrollDirection > 0) {
                (scrollOffset + linesToScroll).coerceIn(0, historySize)
            } else {
                (scrollOffset - linesToScroll).coerceIn(0, historySize)
            }

            if (newOffset != oldOffset) {
                scrollOffset = newOffset
                if (activeHandle == ActiveHandle.START) {
                    selectStartRow = screenYToBufferRow(lastSelectionTouchY)
                    selectStartCol = screenXToCol(lastSelectionTouchX)
                } else if (activeHandle == ActiveHandle.END) {
                    selectEndRow = screenYToBufferRow(lastSelectionTouchY)
                    selectEndCol = screenXToCol(lastSelectionTouchX)
                }
                invalidate()
                postDelayed(this, 40L)
            } else {
                selectionAutoScrollDirection = 0
            }
        }
    }

    private val selectionBgPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val searchMatchPaint = Paint().apply {
        color = Color.parseColor("#45475a")
        style = Paint.Style.FILL
    }
    private val searchCurrentPaint = Paint().apply {
        color = Color.parseColor("#f9e2af")
        style = Paint.Style.FILL
    }
    private val linkUnderlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#89b4fa")
        strokeWidth = 2f
    }
    private val selectionHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#89b4fa")
        style = Paint.Style.FILL
    }
    private val selectionHandleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    // While the screen is scrolled back into the history: where it is in it, and a way back to the end
    private val scrollThumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val jumpPillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val jumpArrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val jumpPillRect = RectF()

    private var actionPopup: PopupWindow? = null
    private val pinHandlePath = Path()

    /** The widths of the wide characters drawn so far (the key is the character's chars), for [drawWide]. */
    private val wideWidths = HashMap<Int, Float>()

    /** The parts of the sextants, which are rectangles (no smoothing: they must meet without a seam). */
    private val blockPaint = Paint().apply { style = Paint.Style.FILL }
    private val astralChars = CharArray(2)

    var charWidth = 0f
        private set
    var charHeight = 0f
        private set
    private var charBaseline = 0f
    // Whether every printable ASCII character of the font is one cell wide, so that a run of them can
    // be drawn in one call (drawRowText). A proportional custom font is drawn character by character.
    private var isFixedPitch = true

    var rows = 24
        private set
    var cols = 80
        private set

    var scrollOffset = 0
        private set

    val historySize: Int
        get() = session?.emulator?.buffer?.history?.size ?: 0

    var onModifiersChanged: (() -> Unit)? = null

    // ---- Terminal text search (Ctrl+F style) ----
    var searchHits: List<TerminalBuffer.SearchHit> = emptyList()
        private set
    var searchIndex: Int = -1
        private set
    var searchQuery: String = ""
        private set
    var onSearchChanged: ((query: String, index: Int, total: Int) -> Unit)? = null

    // ---- Clickable URL links ----
    var urlHits: List<TerminalSearch.UrlHit> = emptyList()
        private set
    var onUrlTapped: ((url: String) -> Unit)? = null
    private var urlRefreshCounter: Int = 0

    var isCtrlPressed = false
        set(value) {
            if (field != value) {
                field = value
                onModifiersChanged?.invoke()
            }
        }

    var isAltPressed = false
        set(value) {
            if (field != value) {
                field = value
                onModifiersChanged?.invoke()
            }
        }

    private val textBounds = Rect()
    private val scroller = OverScroller(context)
    private var scrollRemainder = 0f
    private var lastFlingY = 0

    private val flingRunnable = object : Runnable {
        override fun run() {
            if (scroller.computeScrollOffset()) {
                if (session?.emulator?.buffer?.isAlternate == true) {
                    scroller.abortAnimation()
                    return
                }
                val currentY = scroller.currY
                val historySize = session?.emulator?.buffer?.history?.size ?: 0
                val deltaY = currentY - lastFlingY
                lastFlingY = currentY

                val totalDelta = deltaY + scrollRemainder
                val lines = (totalDelta / charHeight).toInt()
                scrollRemainder = totalDelta - (lines * charHeight)

                if (lines != 0) {
                    val newOffset = (scrollOffset + lines).coerceIn(0, historySize)
                    if (newOffset != scrollOffset) {
                        scrollOffset = newOffset
                        invalidate()
                    } else {
                        scroller.abortAnimation()
                        return
                    }
                }

                if (!scroller.isFinished) {
                    postOnAnimation(this)
                }
            }
        }
    }

    private fun dispatchScrollOrFling(lines: Int, touchX: Float, touchY: Float): Boolean {
        val session = this.session ?: return false
        val emulator = session.emulator
        val isMouseActive = emulator.isMouseTrackingActive
        val isAlternate = emulator.buffer.isAlternate

        if (isMouseActive) {
            val cols = emulator.buffer.cols
            val rows = emulator.buffer.rows
            val col = ((touchX / charWidth) + 1).toInt().coerceIn(1, cols)
            val row = ((touchY / charHeight) + 1).toInt().coerceIn(1, rows)
            val isUp = lines > 0
            val count = kotlin.math.abs(lines)
            val sb = StringBuilder()
            for (i in 0 until count) {
                sb.append(emulator.getMouseScrollSequence(isUp, col, row))
            }
            session.write(sb.toString())
            return true
        } else if (isAlternate) {
            val isAppCursor = emulator.isApplicationCursorKeys
            val upSeq = if (isAppCursor) "\u001bOA" else "\u001b[A"
            val downSeq = if (isAppCursor) "\u001bOB" else "\u001b[B"
            val seq = if (lines > 0) upSeq else downSeq
            val count = kotlin.math.abs(lines)
            val sb = StringBuilder()
            for (i in 0 until count) {
                sb.append(seq)
            }
            session.write(sb.toString())
            return true
        }
        return false
    }

    // Two fingers change the text size in whole sp, applied at once (the rows and columns follow);
    // the size from the start of the pinch is the base, so small movements do not add up
    private var pinchStartSp = 0f
    private var pinchScale = 1f
    private var touchHadTwoFingers = false

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            pinchStartSp = terminalTextSizeSp
            pinchScale = 1f
            scroller.abortAnimation()
            removeCallbacks(flingRunnable)
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            pinchScale *= detector.scaleFactor
            val sp = pinchTextSize(pinchStartSp, pinchScale)
            if (sp != terminalTextSizeSp) setTerminalTextSize(sp)
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            if (terminalTextSizeSp != pinchStartSp) onTextSizeChangedByPinch?.invoke(terminalTextSizeSp)
        }
    }).apply {
        // Double tap and drag would scale with one finger, which scrolls
        isQuickScaleEnabled = false
    }

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.abortAnimation()
            removeCallbacks(flingRunnable)
            return true
        }

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            if (isSelecting) return false
            if (isEdgeDrag) return false
            scroller.abortAnimation()
            removeCallbacks(flingRunnable)

            val emulator = session?.emulator
            val isSpecialScroll = emulator != null && (emulator.isMouseTrackingActive || emulator.buffer.isAlternate)
            if (isSpecialScroll) {
                val totalDelta = -distanceY + scrollRemainder
                val lines = (totalDelta / charHeight).toInt()
                scrollRemainder = totalDelta - (lines * charHeight)

                if (lines != 0) {
                    return dispatchScrollOrFling(lines, e2.x, e2.y)
                }
                return false
            }

            val historySize = session?.emulator?.buffer?.history?.size ?: 0
            if (historySize == 0 && scrollOffset == 0) return false

            // distanceY > 0 when dragging UP (scroll down towards newest lines)
            // distanceY < 0 when dragging DOWN (scroll up into earlier history)
            // Inverting distanceY makes dragging down scroll UP into history
            val totalDelta = -distanceY + scrollRemainder
            val lines = (totalDelta / charHeight).toInt()
            scrollRemainder = totalDelta - (lines * charHeight)

            if (lines != 0) {
                val newOffset = (scrollOffset + lines).coerceIn(0, historySize)
                if (newOffset != scrollOffset) {
                    scrollOffset = newOffset
                    invalidate()
                    return true
                }
            }
            return false
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            if (isSelecting) return false
            val emulator = session?.emulator
            val isSpecialScroll = emulator != null && (emulator.isMouseTrackingActive || emulator.buffer.isAlternate)
            if (isSpecialScroll) {
                val lines = (velocityY / (charHeight * 8)).toInt().coerceIn(-10, 10)
                if (lines != 0) {
                    return dispatchScrollOrFling(lines, e2.x, e2.y)
                }
                return false
            }

            val historySize = session?.emulator?.buffer?.history?.size ?: 0
            if (historySize == 0 && scrollOffset == 0) return false

            scroller.abortAnimation()
            removeCallbacks(flingRunnable)

            // velocityY > 0: flinging down (scroll up into history, positive delta)
            // velocityY < 0: flinging up (scroll down towards bottom, negative delta)
            val scaledVelocity = (velocityY * 0.55f).toInt()
            lastFlingY = 0
            scrollRemainder = 0f

            scroller.fling(
                0, 0,
                0, scaledVelocity,
                0, 0,
                -Int.MAX_VALUE, Int.MAX_VALUE
            )
            postOnAnimation(flingRunnable)
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (scrollOffset > 0 && !jumpPillRect.isEmpty && jumpPillRect.contains(e.x, e.y)) {
                scrollOffset = 0
                invalidate()
                return true
            }
            if (isSelecting) {
                clearSelection()
            }
            if (handleUrlTap(e.x, e.y)) {
                return true
            }
            requestFocus()
            showKeyboard()

            val emulator = session?.emulator
            if (emulator != null && emulator.isMouseTrackingActive) {
                val cols = emulator.buffer.cols
                val rows = emulator.buffer.rows
                val col = ((e.x / charWidth) + 1).toInt().coerceIn(1, cols)
                val row = ((e.y / charHeight) + 1).toInt().coerceIn(1, rows)
                session?.write(emulator.getMouseClickSequence(button = 0, col = col, row = row))
            }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            startSelectionAt(e.x, e.y)
        }
    })


    init {
        isFocusable = true
        isFocusableInTouchMode = true
        measureCharDimensions()
    }

    /** The text size in sp, as set or changed with two fingers. */
    var terminalTextSizeSp = 11f
        private set

    /** Called with the new size in sp when two fingers changed it, so that it is kept. */
    var onTextSizeChangedByPinch: ((Float) -> Unit)? = null

    fun setTerminalTextSize(sp: Float) {
        terminalTextSizeSp = sp
        val px = sp * resources.displayMetrics.density * resources.configuration.fontScale
        textPaint.textSize = px
        measureCharDimensions()
        updateTerminalDimensions()
        invalidate()
    }

    private var currentFontKey: String = "jetbrains_mono"
    private var cachedTypeface: Typeface? = null

    fun setTerminalFont(fontKey: String) {
        if (fontKey == currentFontKey && cachedTypeface != null) {
            textPaint.typeface = cachedTypeface
            measureCharDimensions()
            updateTerminalDimensions()
            invalidate()
            return
        }
        val tf = when (fontKey) {
            "jetbrains_mono" -> {
                try {
                    Typeface.createFromAsset(context.assets, "fonts/JetBrainsMono.ttf")
                } catch (e: Exception) {
                    Typeface.MONOSPACE
                }
            }
            "fira_code" -> {
                try {
                    Typeface.createFromAsset(context.assets, "fonts/FiraCode.ttf")
                } catch (e: Exception) {
                    Typeface.MONOSPACE
                }
            }
            "custom" -> {
                val customFile = java.io.File(org.cortex.terminal.runtime.Environment.getHomeDir(context), ".cortex/font.ttf")
                if (customFile.exists() && customFile.canRead()) {
                    try {
                        Typeface.createFromFile(customFile)
                    } catch (e: Exception) {
                        Typeface.MONOSPACE
                    }
                } else {
                    Typeface.MONOSPACE
                }
            }
            else -> Typeface.MONOSPACE
        }
        currentFontKey = fontKey
        cachedTypeface = tf
        textPaint.typeface = tf
        measureCharDimensions()
        updateTerminalDimensions()
        invalidate()
    }

    fun applyTheme() {
        cursorPaint.color = TerminalColor.CURSOR_COLOR
        // Links, selection handles and search hits are in the accent of the scheme
        selectionHandlePaint.color = Chrome.palette.accent
        selectionBgPaint.color = selectionBackground(Chrome.palette.dark, TerminalColor.DEFAULT_BG, Chrome.palette.accent)
        linkUnderlinePaint.color = Chrome.palette.accent
        searchMatchPaint.color = Chrome.mix(TerminalColor.DEFAULT_BG, TerminalColor.DEFAULT_FG, 0.25f)
        searchCurrentPaint.color = TerminalColor.ANSI_COLORS[3]
        invalidate()
    }

    private fun measureCharDimensions() {
        textPaint.getTextBounds("X", 0, 1, textBounds)
        wideWidths.clear()
        charWidth = textPaint.measureText("M")
        isFixedPitch = kotlin.math.abs(textPaint.measureText("i") - charWidth) < 0.01f &&
            kotlin.math.abs(textPaint.measureText("W") - charWidth) < 0.01f &&
            kotlin.math.abs(textPaint.measureText(".") - charWidth) < 0.01f
        val fontMetrics = textPaint.fontMetrics
        charHeight = fontMetrics.bottom - fontMetrics.top
        charBaseline = -fontMetrics.top
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        scrollOffset = 0
        updateTerminalDimensions()
    }

    private fun updateTerminalDimensions() {
        if (width > 0 && height > 0 && charWidth > 0 && charHeight > 0) {
            val newCols = max(10, (width / charWidth).toInt())
            val newRows = max(4, (height / charHeight).toInt())
            if (newCols != cols || newRows != rows) {
                cols = newCols
                rows = newRows
                scrollOffset = 0
                session?.updateDimensions(rows, cols, width, height)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw overall background
        canvas.drawColor(TerminalColor.DEFAULT_BG)

        val currentSession = session ?: return
        val emulator = currentSession.emulator
        val buffer = emulator.buffer

        if (!emulator.lock.tryLock()) {
            postInvalidateDelayed(16)
            return
        }
        try {
            val sel = if (isSelecting) getNormalizedSelection() else null
            for (r in 0 until rows) {
                val row = buffer.getVisibleRow(r, scrollOffset)
                val bufferRow = r - scrollOffset
                val y = r * charHeight
                val n = min(cols, row.cols)
                drawRowBackground(canvas, row, bufferRow, n, y, sel)
                drawRowText(canvas, row, bufferRow, n, y, sel)
            }

            // Draw selection pin handles
            if (isSelecting) {
                val norm = getNormalizedSelection()
                val minR = norm[0]
                val minC = norm[1]
                val maxR = norm[2]
                val maxC = norm[3]

                val startScreenR = minR + scrollOffset
                val endScreenR = maxR + scrollOffset
                val radius = 13f * resources.displayMetrics.density

                if (startScreenR in 0 until rows) {
                    val startX = minC * charWidth
                    val startY = (startScreenR + 1) * charHeight
                    drawPinHandle(canvas, startX, startY, isStart = true, radius)
                }

                if (endScreenR in 0 until rows) {
                    val endX = (maxC + 1) * charWidth
                    val endY = (endScreenR + 1) * charHeight
                    drawPinHandle(canvas, endX, endY, isStart = false, radius)
                }
            }

            val blinking = emulator.isCursorBlinking
            if (blinking && !isCursorBlinkScheduled && hasWindowFocus()) {
                isCursorBlinkScheduled = true
                postDelayed(cursorBlinkRunnable, CURSOR_BLINK_MS)
            }

            // Draw cursor if at bottom of scrollback and not selecting
            if (!isSelecting && scrollOffset == 0 && buffer.isCursorVisible && (cursorBlinkOn || !blinking)) {
                val cRow = buffer.cursorRow
                val cCol = buffer.cursorCol
                if (cRow in 0 until rows && cCol in 0 until cols && cRow in 0 until buffer.screen.size) {
                    val row = buffer.screen[cRow]
                    // On the second half of a wide character the cursor is on the character; over a wide
                    // character it covers both cells
                    val col = if (row.hasWide && row.isTail(cCol)) cCol - 1 else cCol
                    val wide = row.hasWide && row.isWideHead(col)
                    val cursorX = col * charWidth
                    val cursorY = cRow * charHeight
                    val cursorWidth = if (wide) 2 * charWidth else charWidth
                    when (emulator.cursorShape) {
                        TerminalEmulator.CursorShape.BLOCK -> {
                            canvas.drawRect(cursorX, cursorY, cursorX + cursorWidth, cursorY + charHeight, cursorPaint)

                            // Draw inverse char on cursor
                            val char = if (col in 0 until row.chars.size) row.chars[col] else ' '
                            if (char != ' ' && char != TerminalRow.WIDE_TAIL) {
                                // Not bold or underlined as the last cell drawn was
                                setTextPaint(TerminalColor.DEFAULT_BG, bold = false, underline = false)
                                if (char == TerminalRow.ASTRAL) {
                                    drawAstral(canvas, row.astralAt(col), cursorX, cursorY, cursorY + charBaseline, TerminalColor.DEFAULT_BG)
                                } else if (wide) {
                                    drawWide(canvas, row.chars, col, cursorY + charBaseline)
                                } else if (!Character.isSurrogate(char)) {
                                    canvas.drawText(row.chars, col, 1, cursorX, cursorY + charBaseline, textPaint)
                                }
                            }
                        }
                        TerminalEmulator.CursorShape.UNDERLINE -> canvas.drawRect(
                            cursorX, cursorY + charHeight - cursorThickness, cursorX + cursorWidth, cursorY + charHeight, cursorPaint
                        )
                        TerminalEmulator.CursorShape.BAR -> canvas.drawRect(
                            cursorX, cursorY, cursorX + cursorThickness, cursorY + charHeight, cursorPaint
                        )
                    }
                }
            }
            drawScrollOverlay(canvas, buffer.history.size)
        } finally {
            emulator.lock.unlock()
        }
    }

    /**
     * A thin thumb at the right edge that shows where the screen is in the history, and a round button
     * at the bottom that takes it back to the newest line (a tap, see onSingleTapUp). Nothing is drawn
     * at the end of the history or while text is selected.
     */
    private fun drawScrollOverlay(canvas: Canvas, historyLines: Int) {
        if (scrollOffset <= 0 || historyLines <= 0 || isSelecting) {
            jumpPillRect.setEmpty()
            return
        }
        val d = resources.displayMetrics.density
        val p = Chrome.palette

        val trackTop = 6 * d
        val trackHeight = height - 12 * d
        val thumbHeight = max(28 * d, trackHeight * rows / (historyLines + rows).toFloat())
        val progress = 1f - scrollOffset.coerceAtMost(historyLines).toFloat() / historyLines
        val thumbTop = trackTop + (trackHeight - thumbHeight) * progress
        scrollThumbPaint.color = Chrome.withAlpha(p.accent, 150)
        canvas.drawRoundRect(width - 5 * d, thumbTop, width - 2 * d, thumbTop + thumbHeight, 1.5f * d, 1.5f * d, scrollThumbPaint)

        val pillWidth = 46 * d
        val pillHeight = 36 * d
        jumpPillRect.set(width - pillWidth - 14 * d, height - pillHeight - 14 * d, width - 14 * d, height - 14 * d)
        jumpPillPaint.color = Chrome.withAlpha(p.accent, 235)
        canvas.drawRoundRect(jumpPillRect, pillHeight / 2, pillHeight / 2, jumpPillPaint)
        jumpArrowPaint.color = p.onAccent
        jumpArrowPaint.textSize = 18 * d
        canvas.drawText("↓", jumpPillRect.centerX(), jumpPillRect.centerY() + 6.5f * d, jumpArrowPaint)
    }

    // ---- Drawing rows ----
    // Cells are drawn in runs: one rectangle for neighbouring cells of one background colour and one
    // drawText() for neighbouring characters of one colour and style, instead of a call per cell.
    // Runs hold printable ASCII only: other characters can come from a fallback font whose glyphs
    // are not exactly one cell wide, so they are still drawn one by one at their cell.

    private fun isSelected(sel: IntArray?, bufferRow: Int, col: Int): Boolean {
        if (sel == null) return false
        val minR = sel[0]
        val minC = sel[1]
        val maxR = sel[2]
        val maxC = sel[3]
        if (bufferRow < minR || bufferRow > maxR) return false
        if (bufferRow == minR && bufferRow == maxR) return col in minC..maxC
        if (bufferRow == minR) return col >= minC
        if (bufferRow == maxR) return col <= maxC
        return true
    }

    private fun isLinkCell(row: TerminalRow, c: Int): Boolean =
        (c < row.isUrlLink.size && row.isUrlLink[c]) || row.hasLinkAt(c)

    /** The colour behind a cell (selection, search hit, or its own; inverse video swaps). */
    private fun cellBackground(row: TerminalRow, c: Int, selected: Boolean): Int {
        if (selected) return selectionBgPaint.color
        if (c < row.isSearchMatch.size && row.isSearchMatch[c]) {
            return if (c < row.isSearchCurrent.size && row.isSearchCurrent[c]) searchCurrentPaint.color else searchMatchPaint.color
        }
        return if ((row.styles[c].toInt() and 4) != 0) row.fgColors[c] else row.bgColors[c]
    }

    /** The colour of a cell's character: black on the selection and on the current search hit. */
    private fun cellForeground(row: TerminalRow, c: Int, selected: Boolean): Int {
        if (selected || (c < row.isSearchCurrent.size && row.isSearchCurrent[c])) return Color.BLACK
        return if ((row.styles[c].toInt() and 4) != 0) row.bgColors[c] else row.fgColors[c]
    }

    private fun cellUnderlined(row: TerminalRow, c: Int, selected: Boolean): Boolean =
        (row.styles[c].toInt() and 2) != 0 || (!selected && isLinkCell(row, c))

    private fun drawRowBackground(canvas: Canvas, row: TerminalRow, bufferRow: Int, n: Int, y: Float, sel: IntArray?) {
        var c = 0
        while (c < n) {
            val color = cellBackground(row, c, isSelected(sel, bufferRow, c))
            var end = c + 1
            while (end < n && cellBackground(row, end, isSelected(sel, bufferRow, end)) == color) end++
            if (color != TerminalColor.DEFAULT_BG) {
                bgPaint.color = color
                canvas.drawRect(c * charWidth, y, end * charWidth, y + charHeight, bgPaint)
            }
            c = end
        }
    }

    private fun setTextPaint(color: Int, bold: Boolean, underline: Boolean) {
        textPaint.color = color
        textPaint.isFakeBoldText = bold
        textPaint.isUnderlineText = underline
    }

    private fun drawRowText(canvas: Canvas, row: TerminalRow, bufferRow: Int, n: Int, y: Float, sel: IntArray?) {
        val chars = row.chars
        val baseline = y + charBaseline
        var c = 0
        while (c < n) {
            val ch = chars[c]
            val selected = isSelected(sel, bufferRow, c)
            // A space shows nothing unless it is underlined (then it is drawn below, on its own, with
            // its underline) or, outside the selection, part of a link.
            if (ch == ' ' && (row.styles[c].toInt() and 2) == 0) {
                if (!selected && isLinkCell(row, c)) {
                    canvas.drawLine(c * charWidth, y + charHeight - 3f, (c + 1) * charWidth, y + charHeight - 3f, linkUnderlinePaint)
                }
                c++
                continue
            }
            if (row.hasWide &&
                (ch == TerminalRow.WIDE_TAIL || Character.isLowSurrogate(ch) ||
                    (Character.isHighSurrogate(ch) && !row.isWideHead(c)))
            ) {
                // The second cell of a wide character is drawn with the first; half of an emoji alone is not drawn
                c++
                continue
            }
            val color = cellForeground(row, c, selected)
            val bold = (row.styles[c].toInt() and 1) != 0
            val underline = cellUnderlined(row, c, selected)
            setTextPaint(color, bold, underline)
            if (ch == TerminalRow.ASTRAL) {
                drawAstral(canvas, row.astralAt(c), c * charWidth, y, baseline, color)
                c++
                continue
            }
            if (row.hasWide && row.isWideHead(c)) {
                drawWide(canvas, chars, c, baseline)
                c += 2
                continue
            }
            if (!isFixedPitch || ch.code !in 0x21..0x7e) {
                canvas.drawText(chars, c, 1, c * charWidth, baseline, textPaint)
                c++
                continue
            }
            // Extend the run over characters drawn the same way. Spaces draw nothing, so a run that
            // is not underlined takes them along; trailing ones are left out.
            var end = c + 1
            var last = c
            while (end < n) {
                val next = chars[end]
                val nextSelected = isSelected(sel, bufferRow, end)
                if (next == ' ') {
                    if (underline || (row.styles[end].toInt() and 2) != 0 || (!nextSelected && isLinkCell(row, end))) break
                } else {
                    if (next.code !in 0x21..0x7e) break
                    if (cellForeground(row, end, nextSelected) != color) break
                    if (((row.styles[end].toInt() and 1) != 0) != bold) break
                    if (cellUnderlined(row, end, nextSelected) != underline) break
                    last = end
                }
                end++
            }
            canvas.drawText(chars, c, last - c + 1, c * charWidth, baseline, textPaint)
            c = last + 1
        }
    }

    /**
     * A character of the supplementary planes that takes one cell, at [x] of the row that starts at [y]:
     * a sextant is drawn from rectangles, any other from the fonts (squeezed into the cell when its glyph
     * is wider, as an icon of some fonts is).
     */
    private fun drawAstral(canvas: Canvas, cp: Int, x: Float, y: Float, baseline: Float, color: Int) {
        if (Sextants.isSextant(cp)) {
            val mask = Sextants.mask(cp)
            blockPaint.color = color
            val x1 = Math.round(x + charWidth / 2f).toFloat()
            val x2 = x + charWidth
            val y1 = Math.round(y + charHeight / 3f).toFloat()
            val y2 = Math.round(y + charHeight * 2f / 3f).toFloat()
            val y3 = y + charHeight
            for (bit in 0..5) {
                if ((mask and (1 shl bit)) == 0) continue
                val left = if (bit % 2 == 0) x else x1
                val right = if (bit % 2 == 0) x1 else x2
                val top = when (bit / 2) { 0 -> y; 1 -> y1; else -> y2 }
                val bottom = when (bit / 2) { 0 -> y1; 1 -> y2; else -> y3 }
                canvas.drawRect(left, top, right, bottom, blockPaint)
            }
            return
        }
        textPaint.color = color
        val count = Character.toChars(cp, astralChars, 0)
        val width = wideWidths[cp] ?: textPaint.measureText(astralChars, 0, count).also {
            if (wideWidths.size >= 4096) wideWidths.clear()
            wideWidths[cp] = it
        }
        if (width > charWidth + 0.5f) {
            canvas.save()
            canvas.scale(charWidth / width, 1f, x, baseline)
            canvas.drawText(astralChars, 0, count, x, baseline, textPaint)
            canvas.restore()
        } else {
            canvas.drawText(astralChars, 0, count, x, baseline, textPaint)
        }
    }

    /**
     * A wide character, or an emoji, from the cell [c]: over two cells, from the left of the first. A
     * glyph that is wider than the two cells (an emoji of some fonts) is squeezed into them instead of
     * covering the next character.
     */
    private fun drawWide(canvas: Canvas, chars: CharArray, c: Int, baseline: Float) {
        val count = if (Character.isHighSurrogate(chars[c])) 2 else 1
        val key = if (count == 2) (chars[c].code shl 16) or chars[c + 1].code else chars[c].code
        val room = 2 * charWidth
        val width = wideWidths[key] ?: textPaint.measureText(chars, c, count).also {
            if (wideWidths.size >= 4096) wideWidths.clear()
            wideWidths[key] = it
        }
        val x = c * charWidth
        if (width > room + 0.5f) {
            canvas.save()
            canvas.scale(room / width, 1f, x, baseline)
            canvas.drawText(chars, c, count, x, baseline, textPaint)
            canvas.restore()
        } else {
            canvas.drawText(chars, c, count, x, baseline, textPaint)
        }
    }

    private fun drawPinHandle(canvas: Canvas, tipX: Float, tipY: Float, isStart: Boolean, radius: Float) {
        val centerX = if (isStart) tipX - radius * 0.7f else tipX + radius * 0.7f
        val centerY = tipY + radius

        pinHandlePath.reset()
        pinHandlePath.moveTo(tipX, tipY)
        pinHandlePath.lineTo(centerX, centerY - radius * 0.5f)
        pinHandlePath.lineTo(if (isStart) tipX else tipX, centerY)
        pinHandlePath.close()

        canvas.drawPath(pinHandlePath, selectionHandlePaint)
        canvas.drawCircle(centerX, centerY, radius, selectionHandlePaint)
        canvas.drawCircle(centerX, centerY, radius, selectionHandleStroke)
    }

    private var downX = 0f
    private var downY = 0f
    private var isEdgeDrag = false

    var onTerminalTouchWhenDrawerOpen: (() -> Boolean)? = null

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isSelecting) {
            handleSelectionTouch(event)
            return true
        }

        if (onTerminalTouchWhenDrawerOpen?.invoke() == true) {
            return true
        }

        scaleDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN && !touchHadTwoFingers) {
            // A pinch: no scrolling, long press or keyboard for the rest of this touch
            touchHadTwoFingers = true
            val cancel = MotionEvent.obtain(event)
            cancel.action = MotionEvent.ACTION_CANCEL
            gestureDetector.onTouchEvent(cancel)
            cancel.recycle()
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        if (touchHadTwoFingers) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                touchHadTwoFingers = false
                isEdgeDrag = false
            }
            return true
        }

        val density = resources.displayMetrics.density
        val edgeSlop = 36f * density

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                scroller.abortAnimation()
                removeCallbacks(flingRunnable)
                downX = event.x
                downY = event.y
                isEdgeDrag = event.x <= edgeSlop
                if (isEdgeDrag) {
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (isEdgeDrag) {
                    val dx = event.x - downX
                    val dy = kotlin.math.abs(event.y - downY)
                    if (dx > 12 * density && dx > dy) {
                        parent?.requestDisallowInterceptTouchEvent(false)
                        return false
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasEdgeDrag = isEdgeDrag
                isEdgeDrag = false
                val dx = kotlin.math.abs(event.x - downX)
                val dy = kotlin.math.abs(event.y - downY)
                val slop = 24 * density
                if (!wasEdgeDrag && dx < slop && dy < slop) {
                    requestFocus()
                    showKeyboard()
                }
            }
        }

        gestureDetector.onTouchEvent(event)
        return true
    }

    private fun handleSelectionTouch(event: MotionEvent) {
        val density = resources.displayMetrics.density
        val touchTolerance = 40f * density
        val radius = 13f * density

        val norm = getNormalizedSelection()
        val minR = norm[0]
        val minC = norm[1]
        val maxR = norm[2]
        val maxC = norm[3]

        val startScreenR = minR + scrollOffset
        val endScreenR = maxR + scrollOffset

        val startHandleX = minC * charWidth - radius * 0.7f
        val startHandleY = (startScreenR + 1) * charHeight + radius

        val endHandleX = (maxC + 1) * charWidth + radius * 0.7f
        val endHandleY = (endScreenR + 1) * charHeight + radius

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val distStart = hypot(event.x - startHandleX, event.y - startHandleY)
                val distEnd = hypot(event.x - endHandleX, event.y - endHandleY)

                activeHandle = if (distStart < touchTolerance) {
                    ActiveHandle.START
                } else if (distEnd < touchTolerance) {
                    ActiveHandle.END
                } else {
                    ActiveHandle.NONE
                }

                selectionAutoScrollDirection = 0
                removeCallbacks(selectionAutoScrollRunnable)
                lastSelectionTouchX = event.x
                lastSelectionTouchY = event.y

                if (activeHandle == ActiveHandle.NONE) {
                    // Tap outside selection handles cancels selection
                    clearSelection()
                    requestFocus()
                    showKeyboard()
                } else {
                    hideActionPopup()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                lastSelectionTouchX = event.x
                lastSelectionTouchY = event.y
                if (activeHandle == ActiveHandle.START) {
                    selectStartRow = screenYToBufferRow(event.y)
                    selectStartCol = screenXToCol(event.x)
                    invalidate()
                } else if (activeHandle == ActiveHandle.END) {
                    selectEndRow = screenYToBufferRow(event.y)
                    selectEndCol = screenXToCol(event.x)
                    invalidate()
                }

                if (activeHandle != ActiveHandle.NONE) {
                    val edgeZone = charHeight * 2.5f
                    if (event.y <= edgeZone && scrollOffset < historySize) {
                        if (selectionAutoScrollDirection != 1) {
                            selectionAutoScrollDirection = 1
                            removeCallbacks(selectionAutoScrollRunnable)
                            post(selectionAutoScrollRunnable)
                        }
                    } else if (event.y >= height - edgeZone && scrollOffset > 0) {
                        if (selectionAutoScrollDirection != -1) {
                            selectionAutoScrollDirection = -1
                            removeCallbacks(selectionAutoScrollRunnable)
                            post(selectionAutoScrollRunnable)
                        }
                    } else {
                        if (selectionAutoScrollDirection != 0) {
                            selectionAutoScrollDirection = 0
                            removeCallbacks(selectionAutoScrollRunnable)
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                selectionAutoScrollDirection = 0
                removeCallbacks(selectionAutoScrollRunnable)
                activeHandle = ActiveHandle.NONE
                if (isSelecting) {
                    showActionPopup()
                }
            }
        }
    }

    private fun screenYToBufferRow(y: Float): Int {
        val screenRow = (y / charHeight).toInt().coerceIn(0, rows - 1)
        return screenRow - scrollOffset
    }

    private fun screenXToCol(x: Float): Int {
        return (x / charWidth).toInt().coerceIn(0, cols - 1)
    }

    private fun getNormalizedSelection(): IntArray {
        return if (selectStartRow < selectEndRow || (selectStartRow == selectEndRow && selectStartCol <= selectEndCol)) {
            intArrayOf(selectStartRow, selectStartCol, selectEndRow, selectEndCol)
        } else {
            intArrayOf(selectEndRow, selectEndCol, selectStartRow, selectStartCol)
        }
    }

    private fun startSelectionAt(x: Float, y: Float) {
        val emulator = session?.emulator ?: return
        val buffer = emulator.buffer
        val bufferRow = screenYToBufferRow(y)
        val col = screenXToCol(x)

        var startC = col
        var endC = col

        emulator.lock.withLock {
            val row = buffer.rowForIndex(bufferRow)

            if (row != null && col in 0 until row.cols) {
                val isWordChar = { c: Char ->
                    c.isLetterOrDigit() || c == '_' || c == '-' || c == '/' || c == '.' ||
                        c == TerminalRow.WIDE_TAIL || Character.isSurrogate(c)
                }
                val clickedChar = row.chars[col]

                if (isWordChar(clickedChar)) {
                    while (startC > 0 && isWordChar(row.chars[startC - 1])) {
                        startC--
                    }
                    while (endC < row.cols - 1 && isWordChar(row.chars[endC + 1])) {
                        endC++
                    }
                } else if (clickedChar != ' ') {
                    while (startC > 0 && row.chars[startC - 1] != ' ') {
                        startC--
                    }
                    while (endC < row.cols - 1 && row.chars[endC + 1] != ' ') {
                        endC++
                    }
                }
                // Both cells of a wide character, whichever of them was pressed
                if (row.hasWide) {
                    if (row.isTail(startC)) startC--
                    if (row.isWideHead(endC)) endC++
                }
            }
        }

        selectStartRow = bufferRow
        selectStartCol = startC
        selectEndRow = bufferRow
        selectEndCol = endC
        isSelecting = true
        invalidate()
        post {
            showActionPopup()
        }
    }

    private fun showActionPopup() {
        if (!isSelecting || !isAttachedToWindow) return
        hideActionPopup()

        val p = Chrome.palette
        val density = resources.displayMetrics.density
        val hairline = density.toInt().coerceAtLeast(1)
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            val pad = (4 * density).toInt()
            setPadding(pad, pad, pad, pad)
            background = Chrome.rounded(p.raised, 14 * density, p.outline, hairline)
            elevation = 8f * density
        }

        // The system's own words for these, in the language of the interface
        val createButton = { label: Int, onClick: () -> Unit ->
            TextView(context).apply {
                setText(label)
                setTextColor(p.text)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                val hPad = (14 * density).toInt()
                val vPad = (10 * density).toInt()
                setPadding(hPad, vPad, hPad, vPad)
                background = Chrome.ripple(Chrome.rounded(p.raised, 10 * density), Chrome.withAlpha(p.accent, 70), 10 * density)
                setOnClickListener { onClick() }
            }
        }

        layout.addView(createButton(android.R.string.copy) { copySelectionToClipboard() })
        layout.addView(createButton(android.R.string.paste) { pasteFromClipboard() })
        layout.addView(createButton(android.R.string.selectAll) { selectAllText() })

        val popup = PopupWindow(layout, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, false).apply {
            isOutsideTouchable = false
        }
        actionPopup = popup

        layout.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val origin = IntArray(2)
        getLocationInWindow(origin)
        val norm = getNormalizedSelection()
        val pos = actionPopupPosition(
            viewLeft = origin[0], viewTop = origin[1], viewWidth = width, viewHeight = height,
            popupWidth = layout.measuredWidth, popupHeight = layout.measuredHeight,
            selectionLeft = (norm[1] * charWidth).toInt(),
            selectionTop = ((norm[0] + scrollOffset).coerceIn(0, rows - 1) * charHeight).toInt(),
            selectionBottom = ((norm[2] + scrollOffset + 1).coerceIn(1, rows) * charHeight).toInt(),
            gap = (8 * density).toInt(),
            handles = (13 * density * 2.6f).toInt()
        )
        popup.showAtLocation(this, Gravity.NO_GRAVITY, pos[0], pos[1])
    }

    fun hideActionPopup() {
        actionPopup?.dismiss()
        actionPopup = null
    }

    fun clearSelection() {
        if (isSelecting) {
            isSelecting = false
            selectionAutoScrollDirection = 0
            removeCallbacks(selectionAutoScrollRunnable)
            activeHandle = ActiveHandle.NONE
            hideActionPopup()
            invalidate()
        }
    }

    fun sanitizePastedText(raw: String): String {
        // Strip trailing newlines and carriage returns so the pasted text NEVER triggers auto-execution
        var s = raw.trimEnd('\r', '\n')
        if (s.isEmpty()) return ""
        // Handle bash line continuations: backslash followed by newline
        s = s.replace(Regex("\\\\[\\r\\n]+\\s*"), " ")
        // Replace internal newlines with space to prevent splitting and accidental execution
        s = s.replace(Regex("[\\r\\n]+"), " ")
        return s
    }

    fun copySelectionToClipboard(): Boolean {
        // Without a selection there is nothing to copy (the numbers are those of the last one)
        if (!isSelecting) return false
        val text = selectedTerminalText()
        if (text != null) putOnClipboard(text)
        clearSelection()
        return text != null
    }

    /** Cut: copied, then what delete does to the selection (the command line, when the cursor is in it). */
    private fun cutSelectionToClipboard(): Boolean {
        val text = selectedTerminalText()
        if (text != null) putOnClipboard(text)
        deleteSelection()
        return text != null
    }

    private fun putOnClipboard(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Cortex", text))
        Toast.makeText(context, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
    }

    /** [plainTextOnly]: the clip's text, not the image it may also hold (the keyboard's Paste as plain text). */
    fun pasteFromClipboard(plainTextOnly: Boolean = false) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val clip = clipboard.primaryClip ?: return
        if (clip.itemCount == 0) return
        val item = clip.getItemAt(0)

        val uri = item.uri
        val mimeType = if (uri != null) {
            try { context.contentResolver.getType(uri) } catch (e: Exception) { null }
        } else null
        val hasImageMime = clip.description.hasMimeType("image/*") ||
            (mimeType != null && mimeType.startsWith("image/")) ||
            (uri != null && uri.path?.let { p ->
                p.endsWith(".png", true) || p.endsWith(".jpg", true) || p.endsWith(".jpeg", true) ||
                p.endsWith(".webp", true) || p.endsWith(".gif", true)
            } == true)

        val preferText = plainTextOnly && !item.text.isNullOrEmpty()
        if (!preferText && uri != null && hasImageMime) {
            val savedPath = saveImageFromUri(uri, mimeType)
            if (savedPath != null) {
                session?.write(savedPath)
                scrollOffset = 0
                invalidate()
                clearSelection()
                Toast.makeText(context, context.getString(R.string.pasted_image_path, savedPath), Toast.LENGTH_SHORT).show()
                return
            }
        }

        val rawText = item?.coerceToText(context)?.toString() ?: return
        val text = pastedText(rawText)
        if (text.isNotEmpty()) {
            session?.write(text)
            scrollOffset = 0
            invalidate()
        }
        clearSelection()
    }

    /**
     * What to send for pasted text: with its line breaks inside a bracketed paste when the program asked
     * for one (TerminalEmulator.bracketedPaste), else flattened to one line so a paste never runs a command.
     */
    private fun pastedText(raw: String): String {
        return session?.emulator?.bracketedPaste(raw) ?: sanitizePastedText(raw)
    }

    private fun pruneClipboardImages(dir: File) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        val cutoff = System.currentTimeMillis() - 86_400_000L // 24 hours
        val remaining = ArrayList<File>(files.size)
        for (f in files) {
            if (f.lastModified() < cutoff) {
                try { f.delete() } catch (_: Exception) {}
            } else {
                remaining.add(f)
            }
        }
        if (remaining.size > 32) {
            remaining.sortBy { it.lastModified() }
            val toRemove = remaining.size - 32
            for (i in 0 until toRemove) {
                try { remaining[i].delete() } catch (_: Exception) {}
            }
        }
    }

    private fun saveImageFromUri(uri: Uri, mimeType: String?): String? {
        try {
            if (uri.scheme == "file" && uri.path != null) {
                val f = File(uri.path!!)
                if (f.exists()) return f.absolutePath + " "
            }

            val ext = when (mimeType?.lowercase()) {
                "image/jpeg", "image/jpg" -> ".jpg"
                "image/gif" -> ".gif"
                "image/webp" -> ".webp"
                "image/svg+xml" -> ".svg"
                else -> ".png"
            }

            val destDir = File(context.filesDir, "clipboard_images").apply {
                if (!exists()) {
                    mkdirs()
                }
                setReadable(false, false)
                setReadable(true, true)
                setWritable(false, false)
                setWritable(true, true)
                setExecutable(false, false)
                setExecutable(true, true)
                try { Os.chmod(absolutePath, 448) } catch (_: Exception) {}
            }

            pruneClipboardImages(destDir)

            val timeStamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", java.util.Locale.US).format(java.util.Date())
            val uniqueSuffix = System.nanoTime() and 0xFFFF
            val targetFile = File(destDir, "cortex_img_${timeStamp}_${uniqueSuffix}${ext}")
            if (!targetFile.exists()) {
                targetFile.createNewFile()
            }
            targetFile.setReadable(false, false)
            targetFile.setReadable(true, true)
            targetFile.setWritable(false, false)
            targetFile.setWritable(true, true)
            try { Os.chmod(targetFile.absolutePath, 384) } catch (_: Exception) {}

            val copied = context.contentResolver.openInputStream(uri)?.use { inStream ->
                targetFile.outputStream().use { outStream ->
                    inStream.copyTo(outStream)
                }
                true
            } ?: false
            if (!copied) {
                try { targetFile.delete() } catch (_: Exception) {}
                return null
            }

            targetFile.setReadable(false, false)
            targetFile.setReadable(true, true)
            targetFile.setWritable(false, false)
            targetFile.setWritable(true, true)
            try { Os.chmod(targetFile.absolutePath, 384) } catch (_: Exception) {}
            return targetFile.absolutePath + " "
        } catch (e: Exception) {
            android.util.Log.e("TerminalView", "Failed to save clipboard image", e)
            return null
        }
    }

    fun selectAllText() {
        val histSize = session?.emulator?.buffer?.history?.size ?: 0
        isSelecting = true // from the keyboard's Select all as well, not only from the bar of a selection
        selectStartRow = -histSize
        selectStartCol = 0
        selectEndRow = rows - 1
        selectEndCol = cols - 1
        invalidate()
        post {
            showActionPopup()
        }
    }

    /** The text of the selection, for a keyboard that copies it itself; null without one. */
    private fun selectedTerminalText(): String? {
        if (!isSelecting) return null
        val norm = getNormalizedSelection()
        val emulator = session?.emulator ?: return null
        // Under the lock: output on the PTY thread moves the rows of the screen and the scrollback
        val text = emulator.lock.withLock {
            emulator.buffer.getSelectedText(norm[0], norm[1], norm[2], norm[3])
        }
        return text.takeIf { it.isNotEmpty() }
    }

    /**
     * What delete does to a selection (#32). The text on the screen is the programs', so what can be
     * taken away is what is being typed: with the cursor in the selection (all of it selected, say), the
     * command line is cleared, with End and then the kill of the line that shells and the tty know
     * (Ctrl+E, Ctrl+U). Not in a full-screen program, where those keys mean other things (in nano
     * Ctrl+U pastes). The selection is gone after it either way; true when the line was cleared.
     */
    private fun deleteSelection(): Boolean {
        if (!isSelecting) return false
        val emulator = session?.emulator
        var clear = false
        if (emulator != null) {
            emulator.lock.withLock {
                val buffer = emulator.buffer
                clear = !buffer.isAlternate &&
                    selectionHoldsCell(getNormalizedSelection(), buffer.cursorRow, buffer.cursorCol)
            }
        }
        clearSelection()
        if (clear) {
            scrollOffset = 0
            session?.write(CLEAR_LINE)
        }
        return clear
    }

    /**
     * Search state can be mutated from the PTY reader thread (throttled live refresh),
     * so always hand the result back to the UI thread before touching widgets.
     */
    private fun postSearchChanged() {
        val listener = onSearchChanged ?: return
        val q = searchQuery
        val i = searchIndex
        val t = searchHits.size
        post { try { listener.invoke(q, i, t) } catch (_: Exception) {} }
    }

    // ================= Search API =================
    fun startSearch(query: String) {
        val emulator = session?.emulator ?: return
        searchQuery = query
        emulator.lock.withLock {
            if (query.isEmpty()) {
                searchHits = emptyList()
                searchIndex = -1
                emulator.buffer.clearSearchHighlights()
            } else {
                val hits = TerminalSearch.findAll(emulator.buffer, query, ignoreCase = true)
                searchHits = hits
                searchIndex = if (hits.isEmpty()) -1 else hits.size - 1
                emulator.buffer.markSearchHits(hits, searchIndex)
                if (searchIndex >= 0) scrollToSearchHit(hits[searchIndex])
            }
        }
        postSearchChanged()
        postInvalidate()
    }

    fun nextSearchHit() {
        if (searchHits.isEmpty()) return
        val emulator = session?.emulator ?: return
        searchIndex = (searchIndex + 1) % searchHits.size
        emulator.lock.withLock {
            emulator.buffer.markSearchHits(searchHits, searchIndex)
            scrollToSearchHit(searchHits[searchIndex])
        }
        postSearchChanged()
        postInvalidate()
    }

    fun prevSearchHit() {
        if (searchHits.isEmpty()) return
        val emulator = session?.emulator ?: return
        searchIndex = if (searchIndex - 1 < 0) searchHits.size - 1 else searchIndex - 1
        emulator.lock.withLock {
            emulator.buffer.markSearchHits(searchHits, searchIndex)
            scrollToSearchHit(searchHits[searchIndex])
        }
        postSearchChanged()
        postInvalidate()
    }

    fun clearSearch() {
        searchHits = emptyList()
        searchIndex = -1
        searchQuery = ""
        try {
            val emulator = session?.emulator ?: return
            emulator.lock.withLock { emulator.buffer.clearSearchHighlights() }
        } finally {
            postSearchChanged()
            postInvalidate()
        }
    }

    /**
     * Buffer row -> screen row mapping is: screenRow = scrollOffset + bufferRow.
     * If the hit is already on screen we leave the viewport untouched; otherwise we
     * center it, clamping to the available scrollback depth.
     */
    private fun scrollToSearchHit(hit: TerminalBuffer.SearchHit) {
        val buffer = session?.emulator?.buffer ?: return
        val maxOffset = buffer.history.size
        val alreadyVisible = scrollOffset >= -hit.row && scrollOffset <= (rows - 1 - hit.row)
        if (alreadyVisible) return
        val desired = (rows / 2) - hit.row
        scrollOffset = desired.coerceIn(0, maxOffset)
    }

    // ================= URL links API =================
    fun refreshUrlLinks() {
        val emulator = session?.emulator ?: return
        emulator.lock.withLock {
            val urls = TerminalSearch.findUrls(emulator.buffer)
            urlHits = urls
            emulator.buffer.clearUrlHighlights()
            for (u in urls) {
                val row = u.rowRef ?: emulator.buffer.rowForIndex(u.row) ?: continue
                for (c in u.startCol..u.endCol) {
                    if (c in 0 until row.cols) row.isUrlLink[c] = true
                }
                row.hasUrlFlags = true
            }
        }
        postInvalidate()
    }

    private fun handleUrlTap(x: Float, y: Float): Boolean {
        val screenRow = (y / charHeight).toInt()
        if (screenRow !in 0 until rows) return false
        val col = (x / charWidth).toInt()
        if (col !in 0 until cols) return false
        val bufferRow = screenRow - scrollOffset

        // Explicit OSC 8 hyperlinks (e.g. `ls --hyperlink`, `gcc`, `rg --hyperlink-format`).
        val emulator = session?.emulator
        if (emulator != null) {
            val target = emulator.lock.withLock { emulator.buffer.rowForIndex(bufferRow)?.linkAt(col) }
            if (target != null) {
                onUrlTapped?.invoke(target)
                return true
            }
        }

        if (urlHits.isEmpty()) return false
        for (u in urlHits) {
            if (u.row == bufferRow && col in u.startCol..u.endCol) {
                val url = TerminalSearch.normalizeUrl(u.url)
                onUrlTapped?.invoke(url)
                return true
            }
        }
        return false
    }


    fun showKeyboard() {
        requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        post {
            imm?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    fun hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(windowToken, 0)
    }

    fun toggleKeyboard() {
        requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN

        val mimeTypes = arrayOf(
            "image/png",
            "image/jpeg",
            "image/jpg",
            "image/gif",
            "image/webp",
            "image/*"
        )
        EditorInfoCompat.setContentMimeTypes(outAttrs, mimeTypes)
        // The keyboard is shown a line of its own (IME_LINE) with the cursor at its end
        outAttrs.initialSelStart = IME_LINE.length
        outAttrs.initialSelEnd = IME_LINE.length

        val baseConnection = object : BaseInputConnection(this, false) {
            private var composingLength = 0
            private var batchDepth = 0
            private var linePending = false

            override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = IME_LINE.takeLast(n.coerceAtLeast(0))

            override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = ""

            override fun getSelectedText(flags: Int): CharSequence? = selectedTerminalText()

            /** The keyboard's own Select all, Copy, Cut and Paste buttons (the system's names for them). */
            override fun performContextMenuAction(id: Int): Boolean = when (id) {
                android.R.id.selectAll -> {
                    selectAllText()
                    true
                }
                android.R.id.copy -> copySelectionToClipboard()
                android.R.id.cut -> cutSelectionToClipboard()
                android.R.id.paste -> {
                    pasteFromClipboard()
                    true
                }
                android.R.id.pasteAsPlainText -> {
                    pasteFromClipboard(plainTextOnly = true)
                    true
                }
                else -> super.performContextMenuAction(id)
            }

            @RequiresApi(Build.VERSION_CODES.S)
            override fun getSurroundingText(beforeLength: Int, afterLength: Int, flags: Int): SurroundingText {
                val before = IME_LINE.takeLast(beforeLength.coerceAtLeast(0))
                return SurroundingText(before, before.length, before.length, IME_LINE.length - before.length)
            }

            override fun beginBatchEdit(): Boolean {
                batchDepth++
                return true
            }

            override fun endBatchEdit(): Boolean {
                if (batchDepth > 0) batchDepth--
                if (batchDepth == 0 && linePending) {
                    linePending = false
                    reportLine()
                }
                return batchDepth > 0
            }

            /**
             * After the keyboard deleted, its line is IME_LINE again: told so (once its batch of edits is
             * over), it has something to delete for the next repeat of a held backspace. Not while it
             * composes a word, whose place it keeps itself.
             */
            private fun lineIsBack() {
                if (batchDepth > 0) {
                    linePending = true
                } else {
                    reportLine()
                }
            }

            private fun reportLine() {
                if (composingLength != 0) return
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
                imm.updateSelection(this@TerminalView, IME_LINE.length, IME_LINE.length, -1, -1)
            }

            /** Types [str], an emoji (a pair of chars) as one character and a line break as Enter. */
            private fun sendCharacters(str: String) {
                var i = 0
                while (i < str.length) {
                    val c = str[i]
                    if (Character.isHighSurrogate(c) && i + 1 < str.length && Character.isLowSurrogate(str[i + 1])) {
                        sendText(str.substring(i, i + 2))
                        i += 2
                        continue
                    }
                    if (c == '\n' || c == '\r') sendKeySequence(KeyEvent.KEYCODE_ENTER) else sendChar(c)
                    i++
                }
            }

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                // Nothing committed over a selection is how a keyboard deletes it
                if (text.isNullOrEmpty() && composingLength == 0 && deleteSelection()) {
                    lineIsBack()
                    return true
                }
                clearSelection()
                // What the keyboard was composing (the pinyin of a Chinese word, the kana of a Japanese
                // one) is on the line already: the text it commits replaces it
                for (i in 0 until composingLength) {
                    sendKeySequence(KeyEvent.KEYCODE_DEL)
                }
                composingLength = 0
                if (!text.isNullOrEmpty()) {
                    if (text == "\n" || text == "\r" || text == "\r\n") {
                        sendKeySequence(KeyEvent.KEYCODE_ENTER)
                        return true
                    }
                    // Several lines at once come from the keyboard's clipboard: a paste, which keeps its
                    // lines when the program takes bracketed pastes.
                    if (text.length > 1 && session?.emulator?.isBracketedPasteMode == true &&
                        text.any { it == '\n' || it == '\r' }
                    ) {
                        val paste = pastedText(text.toString())
                        if (paste.isNotEmpty()) {
                            scrollOffset = 0
                            session?.write(paste)
                        }
                        return true
                    }
                    val str = if (text.length > 1) sanitizePastedText(text.toString()) else text.toString()
                    sendCharacters(str)
                }
                return true
            }

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                clearSelection()
                val str = text?.toString() ?: ""
                for (i in 0 until composingLength) {
                    sendKeySequence(KeyEvent.KEYCODE_DEL)
                }
                sendCharacters(str)
                // The line editor deletes a character (an emoji is one) for each backspace
                composingLength = str.codePointCount(0, str.length)
                return true
            }

            override fun setComposingRegion(start: Int, end: Int): Boolean = true

            override fun finishComposingText(): Boolean {
                composingLength = 0
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (deleteSelection()) {
                    composingLength = 0 // the line it was composed on is gone
                    lineIsBack()
                    return true
                }
                if (beforeLength == 0 && afterLength == 0) {
                    sendKeySequence(KeyEvent.KEYCODE_DEL)
                } else {
                    for (i in 0 until beforeLength) {
                        sendKeySequence(KeyEvent.KEYCODE_DEL)
                    }
                }
                lineIsBack()
                return true
            }

            override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean =
                deleteSurroundingText(beforeLength, afterLength)

            override fun performEditorAction(actionCode: Int): Boolean {
                clearSelection()
                sendKeySequence(KeyEvent.KEYCODE_ENTER)
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    val handled = onKeyDown(event.keyCode, event)
                    if (event.keyCode == KeyEvent.KEYCODE_DEL || event.keyCode == KeyEvent.KEYCODE_FORWARD_DEL) lineIsBack()
                    return handled
                }
                return true
            }
        }

        val callback = InputConnectionCompat.OnCommitContentListener { inputContentInfo, flags, _ ->
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1 &&
                    (flags and InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION) != 0) {
                    try {
                        inputContentInfo.requestPermission()
                    } catch (e: Exception) {
                        android.util.Log.e("TerminalView", "Failed to request permission for content info", e)
                        return@OnCommitContentListener false
                    }
                }

                val uri = inputContentInfo.contentUri
                val description = inputContentInfo.description
                val mimeType = if (description.mimeTypeCount > 0) description.getMimeType(0) else null

                val savedPath = saveImageFromUri(uri, mimeType)
                if (savedPath != null) {
                    post {
                        session?.write(savedPath)
                        scrollOffset = 0
                        invalidate()
                        clearSelection()
                        Toast.makeText(context, context.getString(R.string.pasted_image_path, savedPath), Toast.LENGTH_SHORT).show()
                    }
                    true
                } else {
                    post {
                        Toast.makeText(context, R.string.image_save_failed, Toast.LENGTH_SHORT).show()
                    }
                    false
                }
            } catch (e: Exception) {
                android.util.Log.e("TerminalView", "Error committing rich content", e)
                false
            } finally {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1 &&
                    (flags and InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION) != 0) {
                    try {
                        inputContentInfo.releasePermission()
                    } catch (e: Exception) {}
                }
            }
        }

        return InputConnectionCompat.createWrapper(baseConnection, outAttrs, callback)
    }

    fun sendChar(ch: Char) {
        val sess = session ?: return
        if (!sess.isRunning) {
            if (ch == '\n' || ch == '\r') {
                sess.onCloseRequested?.invoke()
            }
            return
        }
        clearSelection()
        scrollOffset = 0
        val targetChar = if (ch == '\n') '\r' else ch
        val bytes = KeyMapper.getCharBytes(targetChar, isCtrlPressed, isAltPressed)
        sess.write(bytes)
        restartCursorBlink()
        isCtrlPressed = false
        isAltPressed = false
    }

    /** Types [text] as it is (a key of the toolbar); "\n" is Enter, CTRL and ALT do not apply. */
    fun sendText(text: String) {
        val sess = session ?: return
        if (!sess.isRunning) return
        clearSelection()
        scrollOffset = 0
        sess.write(text.replace("\r\n", "\r").replace('\n', '\r').toByteArray(Charsets.UTF_8))
        restartCursorBlink()
        isCtrlPressed = false
        isAltPressed = false
    }

    fun sendKeySequence(keyCode: Int): Boolean {
        val sess = session ?: return false
        if (!sess.isRunning) {
            if (keyCode == KeyEvent.KEYCODE_ENTER) {
                sess.onCloseRequested?.invoke()
                return true
            }
            return false
        }
        clearSelection()
        scrollOffset = 0
        val isAppCursor = sess.emulator.isApplicationCursorKeys
        val bytes = KeyMapper.getEscapeSequence(keyCode, isCtrlPressed, isAltPressed, isAppCursor)
        return if (bytes != null) {
            sess.write(bytes)
            restartCursorBlink()
            isCtrlPressed = false
            isAltPressed = false
            true
        } else {
            false
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // The shortcuts of a hardware keyboard (#41). Paste is always taken: the terminal would send
        // the program the control character of Ctrl+V. Copy only with a selection: without one, the
        // keys go on as they did. A held key repeats: it is done once, and the repeats are taken too.
        when (clipboardShortcut(keyCode, event.isCtrlPressed, event.isShiftPressed, event.isAltPressed)) {
            ClipboardShortcut.PASTE -> {
                if (event.repeatCount == 0) pasteFromClipboard()
                return true
            }
            ClipboardShortcut.COPY -> if (isSelecting || event.repeatCount > 0) {
                if (event.repeatCount == 0) copySelectionToClipboard()
                return true
            }
            null -> {}
        }
        // The keys of a keyboard's clipboard buttons, and delete over a selection
        when (keyCode) {
            KeyEvent.KEYCODE_PASTE -> {
                pasteFromClipboard()
                return true
            }
            KeyEvent.KEYCODE_COPY -> {
                copySelectionToClipboard()
                return true
            }
            KeyEvent.KEYCODE_CUT -> {
                cutSelectionToClipboard()
                return true
            }
            KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL -> if (deleteSelection()) return true
        }
        clearSelection()
        scrollOffset = 0
        if (sendKeySequence(keyCode)) {
            return true
        }

        val unicode = event.getUnicodeChar(event.metaState)
        if (unicode > 0) {
            sendChar(unicode.toChar())
            return true
        }

        return super.onKeyDown(keyCode, event)
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.EditText"
        info.isFocusable = true
        info.isFocused = isFocused
        info.isScrollable = true

        val emulator = session?.emulator
        if (emulator != null) {
            val visibleText = StringBuilder()
            emulator.lock.withLock {
                val buffer = emulator.buffer
                for (r in 0 until rows) {
                    val row = buffer.getVisibleRow(r, scrollOffset)
                    val text = row.getText()
                    if (text.isNotEmpty()) {
                        visibleText.append(text).append("\n")
                    }
                }
            }
            info.text = visibleText.toString().trimEnd()
            info.contentDescription = context.getString(R.string.terminal_screen_desc, cols, rows)
        } else {
            info.contentDescription = context.getString(R.string.terminal_screen_inactive_desc)
        }
    }
}

/**
 * The line the keyboard is shown: one space, with the cursor after it. The terminal keeps no text of
 * its own (what is typed goes to the program at once), and a keyboard that sees nothing before the
 * cursor deletes once for a held backspace and then stops (Gboard). With the space there, each repeat
 * is one backspace for the program, and after it the line is the space again.
 */
private const val IME_LINE = " "

/**
 * The color behind selected text (which is drawn in black): white on the dark schemes, as it was, and
 * the accent faintly on the light ones, where white would not show on the background.
 */
internal fun selectionBackground(dark: Boolean, background: Int, accent: Int): Int =
    if (dark) Chrome.WHITE else Chrome.mix(background, accent, 0.35f)

/** What clears the command line: End, then the kill of the line (the tty's and readline's Ctrl+U). */
private const val CLEAR_LINE = "\u0005\u0015"

internal enum class ClipboardShortcut { PASTE, COPY }

/**
 * The clipboard shortcuts of a hardware keyboard, those of other terminal emulators: Ctrl+Shift+V,
 * Ctrl+Alt+V (Termux's) and Shift+Insert paste; Ctrl+Shift+C and Ctrl+Insert copy. Ctrl+V alone is
 * not one: it is the program's (the shell's quoted insert, the block selection of vim).
 */
internal fun clipboardShortcut(keyCode: Int, ctrl: Boolean, shift: Boolean, alt: Boolean): ClipboardShortcut? = when {
    keyCode == KeyEvent.KEYCODE_V && ctrl && (shift || alt) -> ClipboardShortcut.PASTE
    keyCode == KeyEvent.KEYCODE_INSERT && shift && !ctrl && !alt -> ClipboardShortcut.PASTE
    keyCode == KeyEvent.KEYCODE_C && ctrl && shift && !alt -> ClipboardShortcut.COPY
    keyCode == KeyEvent.KEYCODE_INSERT && ctrl && !shift && !alt -> ClipboardShortcut.COPY
    else -> null
}

/**
 * Whether the cell ([row], [col]) is in the selection [norm]: its first row and column, then its last
 * ones, in this order (the buffer's rows: those of the scrollback are negative).
 */
internal fun selectionHoldsCell(norm: IntArray, row: Int, col: Int): Boolean =
    (row > norm[0] || (row == norm[0] && col >= norm[1])) &&
        (row < norm[2] || (row == norm[2] && col <= norm[3]))

/** The text sizes two fingers can set, in sp. */
internal const val MIN_PINCH_TEXT_SP = 6f
internal const val MAX_PINCH_TEXT_SP = 36f

/** The text size for a pinch that started at [startSp] and has scaled by [scale]: whole sp. */
internal fun pinchTextSize(startSp: Float, scale: Float): Float =
    Math.round(startSp * scale).toFloat().coerceIn(MIN_PINCH_TEXT_SP, MAX_PINCH_TEXT_SP)

/**
 * Where the Copy / Paste / Select all bar goes, in the window's coordinates (which is what
 * PopupWindow.showAtLocation takes; the terminal is not at the top of the window, the tabs and the
 * status bar are above it). Above the selection when that fits in the terminal, else under it below
 * its handles; never outside the terminal, so never under the tabs, the status bar or the keys.
 */
internal fun actionPopupPosition(
    viewLeft: Int, viewTop: Int, viewWidth: Int, viewHeight: Int,
    popupWidth: Int, popupHeight: Int,
    selectionLeft: Int, selectionTop: Int, selectionBottom: Int,
    gap: Int, handles: Int
): IntArray {
    val above = selectionTop - gap - popupHeight
    val y = if (above >= 0) above else (selectionBottom + handles).coerceAtMost(viewHeight - popupHeight)
    val x = selectionLeft.coerceAtMost(viewWidth - popupWidth)
    return intArrayOf(viewLeft + x.coerceAtLeast(0), viewTop + y.coerceAtLeast(0))
}
