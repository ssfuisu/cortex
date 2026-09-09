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
import android.graphics.Rect
import android.graphics.Typeface
import android.text.InputType
import android.os.Build
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import android.widget.LinearLayout
import android.widget.OverScroller
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import org.cortex.terminal.emulator.KeyMapper
import org.cortex.terminal.emulator.TerminalColor
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
            field?.onRedraw = null
            field?.emulator?.onScreenUpdate = null
            field = value
            value?.onRedraw = { postInvalidate() }
            value?.emulator?.onScreenUpdate = { postInvalidate() }
            isCtrlPressed = false
            isAltPressed = false
            updateTerminalDimensions()
            postInvalidate()
        }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        session?.onRedraw = { postInvalidate() }
        session?.emulator?.onScreenUpdate = { postInvalidate() }
        updateTerminalDimensions()
        postInvalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        scroller.abortAnimation()
        removeCallbacks(flingRunnable)
        hideActionPopup()
        isSelecting = false
        session?.onRedraw = null
        session?.emulator?.onScreenUpdate = null
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = 28f
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

    private val selectionBgPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
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

    private var actionPopup: PopupWindow? = null

    var charWidth = 0f
        private set
    var charHeight = 0f
        private set
    private var charBaseline = 0f

    var rows = 24
        private set
    var cols = 80
        private set

    var scrollOffset = 0
        private set

    var onModifiersChanged: (() -> Unit)? = null

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
            if (session?.emulator?.buffer?.isAlternate == true) return false
            scroller.abortAnimation()
            removeCallbacks(flingRunnable)

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
            if (session?.emulator?.buffer?.isAlternate == true) return false
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
            if (isSelecting) {
                clearSelection()
            }
            requestFocus()
            showKeyboard()
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

    fun setTerminalTextSize(sp: Float) {
        val px = sp * resources.displayMetrics.scaledDensity
        textPaint.textSize = px
        measureCharDimensions()
        updateTerminalDimensions()
        invalidate()
    }

    private fun measureCharDimensions() {
        textPaint.getTextBounds("X", 0, 1, textBounds)
        charWidth = textPaint.measureText("M")
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

        val currentSession = session ?: return
        val emulator = currentSession.emulator
        val buffer = emulator.buffer

        // Draw overall background
        canvas.drawColor(TerminalColor.DEFAULT_BG)

        if (!emulator.lock.tryLock()) {
            postInvalidateDelayed(16)
            return
        }
        try {
            for (r in 0 until rows) {
                val row = buffer.getVisibleRow(r, scrollOffset)
                val bufferRow = r - scrollOffset
                val y = r * charHeight

                for (c in 0 until min(cols, row.cols)) {
                    val x = c * charWidth
                    val char = row.chars[c]
                    val fg = row.fgColors[c]
                    val bg = row.bgColors[c]
                    val style = row.styles[c]

                    val selected = isCellSelected(bufferRow, c)

                    if (selected) {
                        // Highlight selected region with crisp white background
                        canvas.drawRect(x, y, x + charWidth, y + charHeight, selectionBgPaint)

                        // Render character in dark black so underlying text is crystal clear
                        textPaint.color = Color.BLACK
                        textPaint.isFakeBoldText = (style.toInt() and 1) != 0
                        textPaint.isUnderlineText = (style.toInt() and 2) != 0
                        canvas.drawText(char.toString(), x, y + charBaseline, textPaint)
                    } else {
                        val isInverse = (style.toInt() and 4) != 0
                        val drawBg = if (isInverse) fg else bg
                        val drawFg = if (isInverse) bg else fg

                        // Draw cell background
                        if (drawBg != TerminalColor.DEFAULT_BG) {
                            bgPaint.color = drawBg
                            canvas.drawRect(x, y, x + charWidth, y + charHeight, bgPaint)
                        }

                        // Draw character
                        if (char != ' ') {
                            textPaint.color = drawFg
                            textPaint.isFakeBoldText = (style.toInt() and 1) != 0
                            textPaint.isUnderlineText = (style.toInt() and 2) != 0
                            canvas.drawText(char.toString(), x, y + charBaseline, textPaint)
                        }
                    }
                }
            }

            // Draw selection pin handles ("iğneler")
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

            // Draw cursor if at bottom of scrollback and not selecting
            if (!isSelecting && scrollOffset == 0 && buffer.isCursorVisible) {
                val cRow = buffer.cursorRow
                val cCol = buffer.cursorCol
                if (cRow in 0 until rows && cCol in 0 until cols && cRow in 0 until buffer.screen.size) {
                    val cursorX = cCol * charWidth
                    val cursorY = cRow * charHeight
                    canvas.drawRect(cursorX, cursorY, cursorX + charWidth, cursorY + charHeight, cursorPaint)

                    // Draw inverse char on cursor
                    val row = buffer.screen[cRow]
                    val char = if (cCol in 0 until row.chars.size) row.chars[cCol] else ' '
                    if (char != ' ') {
                        textPaint.color = TerminalColor.DEFAULT_BG
                        canvas.drawText(char.toString(), cursorX, cursorY + charBaseline, textPaint)
                    }
                }
            }
        } finally {
            emulator.lock.unlock()
        }
    }

    private fun drawPinHandle(canvas: Canvas, tipX: Float, tipY: Float, isStart: Boolean, radius: Float) {
        val centerX = if (isStart) tipX - radius * 0.7f else tipX + radius * 0.7f
        val centerY = tipY + radius

        val path = Path().apply {
            moveTo(tipX, tipY)
            lineTo(centerX, centerY - radius * 0.5f)
            lineTo(if (isStart) tipX else tipX, centerY)
            close()
        }
        canvas.drawPath(path, selectionHandlePaint)
        canvas.drawCircle(centerX, centerY, radius, selectionHandlePaint)
        canvas.drawCircle(centerX, centerY, radius, selectionHandleStroke)
    }

    private var downX = 0f
    private var downY = 0f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isSelecting) {
            handleSelectionTouch(event)
            return true
        }

        gestureDetector.onTouchEvent(event)
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                scroller.abortAnimation()
                removeCallbacks(flingRunnable)
                downX = event.x
                downY = event.y
            }
            MotionEvent.ACTION_UP -> {
                val dx = kotlin.math.abs(event.x - downX)
                val dy = kotlin.math.abs(event.y - downY)
                val slop = 24 * resources.displayMetrics.density
                if (dx < slop && dy < slop) {
                    requestFocus()
                    showKeyboard()
                }
            }
        }
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
                if (activeHandle == ActiveHandle.START) {
                    selectStartRow = screenYToBufferRow(event.y)
                    selectStartCol = screenXToCol(event.x)
                    invalidate()
                } else if (activeHandle == ActiveHandle.END) {
                    selectEndRow = screenYToBufferRow(event.y)
                    selectEndCol = screenXToCol(event.x)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
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

    private fun isCellSelected(bufferRow: Int, col: Int): Boolean {
        if (!isSelecting) return false
        val norm = getNormalizedSelection()
        val minR = norm[0]
        val minC = norm[1]
        val maxR = norm[2]
        val maxC = norm[3]

        if (bufferRow < minR || bufferRow > maxR) return false
        if (bufferRow == minR && bufferRow == maxR) {
            return col in minC..maxC
        }
        if (bufferRow == minR) return col >= minC
        if (bufferRow == maxR) return col <= maxC
        return true
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
            val row = if (bufferRow < 0) {
                val hIdx = buffer.history.size + bufferRow
                if (hIdx in 0 until buffer.history.size) buffer.history[hIdx] else null
            } else if (bufferRow in 0 until rows && bufferRow in 0 until buffer.screen.size) {
                buffer.screen[bufferRow]
            } else null

            if (row != null && col in 0 until row.cols) {
                val isWordChar = { c: Char -> c.isLetterOrDigit() || c == '_' || c == '-' || c == '/' || c == '.' }
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

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#181825"))
            val pad = (6 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            elevation = 16f * resources.displayMetrics.density
        }

        val createButton = { text: String, onClick: () -> Unit ->
            TextView(context).apply {
                this.text = text
                setTextColor(Color.WHITE)
                textSize = 13f
                isAllCaps = true
                typeface = Typeface.DEFAULT_BOLD
                val hPad = (14 * resources.displayMetrics.density).toInt()
                val vPad = (10 * resources.displayMetrics.density).toInt()
                setPadding(hPad, vPad, hPad, vPad)
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener { onClick() }
            }
        }

        val btnCopy = createButton("Copy") {
            copySelectionToClipboard()
        }

        val btnPaste = createButton("Paste") {
            pasteFromClipboard()
        }

        val btnSelectAll = createButton("Select All") {
            selectAllText()
        }

        layout.addView(btnCopy)
        layout.addView(btnPaste)
        layout.addView(btnSelectAll)

        val popup = PopupWindow(layout, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, false).apply {
            isOutsideTouchable = false
        }
        actionPopup = popup

        val norm = getNormalizedSelection()
        val startScreenR = (norm[0] + scrollOffset).coerceIn(0, rows - 1)
        val popupX = (norm[1] * charWidth).toInt().coerceIn(20, max(20, width - 260))
        val popupY = ((startScreenR * charHeight) - 56 * resources.displayMetrics.density).toInt().coerceAtLeast(10)

        popup.showAtLocation(this, Gravity.NO_GRAVITY, popupX, popupY)
    }

    fun hideActionPopup() {
        actionPopup?.dismiss()
        actionPopup = null
    }

    fun clearSelection() {
        if (isSelecting) {
            isSelecting = false
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
        val norm = getNormalizedSelection()
        val text = session?.emulator?.buffer?.getSelectedText(norm[0], norm[1], norm[2], norm[3]) ?: ""
        if (text.isNotEmpty()) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Cortex", text))
            Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            clearSelection()
            return true
        }
        clearSelection()
        return false
    }

    fun pasteFromClipboard() {
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

        if (uri != null && hasImageMime) {
            val savedPath = saveImageFromUri(uri, mimeType)
            if (savedPath != null) {
                session?.write(savedPath)
                scrollOffset = 0
                invalidate()
                clearSelection()
                Toast.makeText(context, "Pasted image path: $savedPath", Toast.LENGTH_SHORT).show()
                return
            }
        }

        val rawText = item?.coerceToText(context)?.toString() ?: return
        val text = sanitizePastedText(rawText)
        if (text.isNotEmpty()) {
            session?.write(text)
            scrollOffset = 0
            invalidate()
        }
        clearSelection()
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

            val picturesDir = File("/sdcard/Pictures")
            val destDir = if (picturesDir.exists() || picturesDir.mkdirs()) {
                picturesDir
            } else {
                val extDir = File(android.os.Environment.getExternalStorageDirectory(), "Pictures")
                if (extDir.exists() || extDir.mkdirs()) extDir else File(context.filesDir, "pictures").apply { mkdirs() }
            }

            val timeStamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
            val targetFile = File(destDir, "cortex_img_${timeStamp}${ext}")

            context.contentResolver.openInputStream(uri)?.use { inStream ->
                targetFile.outputStream().use { outStream ->
                    inStream.copyTo(outStream)
                }
            } ?: return null

            targetFile.setReadable(true, false)
            return targetFile.absolutePath + " "
        } catch (e: Exception) {
            android.util.Log.e("TerminalView", "Failed to save clipboard image", e)
            return null
        }
    }

    fun selectAllText() {
        val histSize = session?.emulator?.buffer?.history?.size ?: 0
        selectStartRow = -histSize
        selectStartCol = 0
        selectEndRow = rows - 1
        selectEndCol = cols - 1
        invalidate()
        post {
            showActionPopup()
        }
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
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_FULLSCREEN

        val mimeTypes = arrayOf(
            "image/png",
            "image/jpeg",
            "image/jpg",
            "image/gif",
            "image/webp",
            "image/*"
        )
        EditorInfoCompat.setContentMimeTypes(outAttrs, mimeTypes)

        val baseConnection = object : BaseInputConnection(this, false) {
            private var composingLength = 0

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                clearSelection()
                composingLength = 0
                if (!text.isNullOrEmpty()) {
                    val str = if (text.length > 1) sanitizePastedText(text.toString()) else text.toString()
                    for (i in 0 until str.length) {
                        sendChar(str[i])
                    }
                }
                return true
            }

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                clearSelection()
                val str = text?.toString() ?: ""
                for (i in 0 until composingLength) {
                    sendKeySequence(KeyEvent.KEYCODE_DEL)
                }
                for (ch in str) {
                    sendChar(ch)
                }
                composingLength = str.length
                return true
            }

            override fun setComposingRegion(start: Int, end: Int): Boolean = true

            override fun finishComposingText(): Boolean {
                composingLength = 0
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                clearSelection()
                if (beforeLength == 0 && afterLength == 0) {
                    sendKeySequence(KeyEvent.KEYCODE_DEL)
                } else {
                    for (i in 0 until beforeLength) {
                        sendKeySequence(KeyEvent.KEYCODE_DEL)
                    }
                }
                return true
            }

            override fun performEditorAction(actionCode: Int): Boolean {
                clearSelection()
                sendKeySequence(KeyEvent.KEYCODE_ENTER)
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    clearSelection()
                    return onKeyDown(event.keyCode, event)
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
                        Toast.makeText(context, "Pasted image path: $savedPath", Toast.LENGTH_SHORT).show()
                    }
                    true
                } else {
                    post {
                        Toast.makeText(context, "Failed to save image", Toast.LENGTH_SHORT).show()
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
        clearSelection()
        scrollOffset = 0
        val bytes = KeyMapper.getCharBytes(ch, isCtrlPressed, isAltPressed)
        session?.write(bytes)
        isCtrlPressed = false
        isAltPressed = false
    }

    fun sendKeySequence(keyCode: Int): Boolean {
        clearSelection()
        scrollOffset = 0
        val isAppCursor = session?.emulator?.isApplicationCursorKeys ?: false
        val bytes = KeyMapper.getEscapeSequence(keyCode, isCtrlPressed, isAltPressed, isAppCursor)
        return if (bytes != null) {
            session?.write(bytes)
            isCtrlPressed = false
            isAltPressed = false
            true
        } else {
            false
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
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
}
