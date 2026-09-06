package org.cortex.terminal.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.InputType
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import org.cortex.terminal.emulator.KeyMapper
import org.cortex.terminal.emulator.TerminalColor
import org.cortex.terminal.session.TerminalSession
import kotlin.math.max
import kotlin.math.min

class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var session: TerminalSession? = null
        set(value) {
            field = value
            updateTerminalDimensions()
            invalidate()
        }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = 38f
    }

    private val bgPaint = Paint()
    private val cursorPaint = Paint().apply {
        color = TerminalColor.CURSOR_COLOR
    }

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

    var isCtrlPressed = false
    var isAltPressed = false

    private val textBounds = Rect()
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            val historySize = session?.emulator?.buffer?.history?.size ?: 0
            val lineDelta = (distanceY / charHeight).toInt()
            if (lineDelta != 0) {
                scrollOffset = (scrollOffset + lineDelta).coerceIn(0, historySize)
                invalidate()
                return true
            }
            return false
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            requestFocus()
            showKeyboard()
            return true
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
        updateTerminalDimensions()
    }

    private fun updateTerminalDimensions() {
        if (width > 0 && height > 0 && charWidth > 0 && charHeight > 0) {
            val newCols = max(10, (width / charWidth).toInt())
            val newRows = max(4, (height / charHeight).toInt())
            if (newCols != cols || newRows != rows) {
                cols = newCols
                rows = newRows
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

        emulator.lock.lock()
        try {
            for (r in 0 until rows) {
                val row = buffer.getVisibleRow(r, scrollOffset)
                val y = r * charHeight

                for (c in 0 until min(cols, row.cols)) {
                    val x = c * charWidth
                    val char = row.chars[c]
                    val fg = row.fgColors[c]
                    val bg = row.bgColors[c]
                    val style = row.styles[c]

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

            // Draw cursor if at bottom of scrollback
            if (scrollOffset == 0 && buffer.isCursorVisible) {
                val cRow = buffer.cursorRow
                val cCol = buffer.cursorCol
                if (cRow in 0 until rows && cCol in 0 until cols) {
                    val cursorX = cCol * charWidth
                    val cursorY = cRow * charHeight
                    canvas.drawRect(cursorX, cursorY, cursorX + charWidth, cursorY + charHeight, cursorPaint)

                    // Draw inverse char on cursor
                    val row = buffer.screen[cRow]
                    val char = if (cCol < row.cols) row.chars[cCol] else ' '
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

    private var downX = 0f
    private var downY = 0f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
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

        return object : BaseInputConnection(this, false) {
            private var composingLength = 0

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                composingLength = 0
                if (!text.isNullOrEmpty()) {
                    for (i in 0 until text.length) {
                        sendChar(text[i])
                    }
                }
                return true
            }

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
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
                sendKeySequence(KeyEvent.KEYCODE_ENTER)
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    return onKeyDown(event.keyCode, event)
                }
                return true
            }
        }
    }

    fun sendChar(ch: Char) {
        val bytes = KeyMapper.getCharBytes(ch, isCtrlPressed, isAltPressed)
        session?.write(bytes)
        isCtrlPressed = false
        isAltPressed = false
    }

    fun sendKeySequence(keyCode: Int): Boolean {
        val bytes = KeyMapper.getEscapeSequence(keyCode, isCtrlPressed, isAltPressed)
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
