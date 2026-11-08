package org.cortex.terminal.desktop

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import kotlin.math.abs
import kotlin.math.hypot

/**
 * What the X server draws, and the finger, the keyboard and the mouse wheel for it. The desktop fills
 * the width of the view; two fingers apart zoom in, and the view then follows the pointer.
 *
 * Touch ("touch" mode): the finger is the pointer. A tap clicks, a long press is the right button, a
 * drag holds the left button (moves windows, selects text), two fingers up or down turn the wheel.
 * Touchpad mode: the finger moves the pointer by as much as it moves; a tap clicks where the pointer
 * is, a tap with two fingers is the right button, a long press then a drag holds the left button.
 */
class DesktopView(context: Context) : View(context), RfbClient.Listener {

    @Volatile
    var client: RfbClient? = null
    var touchpad = false

    /** Keysyms held down by the key bar (Ctrl, Alt, Super), pressed around the next key; then released. */
    var heldModifiers: () -> IntArray = { IntArray(0) }
    var onModifiersUsed: () -> Unit = {}

    /** The view's size changed: (width, height, widthChanged). */
    var onViewSizeChanged: ((Int, Int, Boolean) -> Unit)? = null

    private var bitmap: Bitmap? = null
    private val dirty = Rect()
    private val dirtyLock = Any()
    @Volatile
    private var flushPosted = false
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()
    private val inverse = Matrix()
    private var baseScale = 1f
    private var zoom = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    private var cursor: Bitmap? = null
    private var cursorHotX = 0
    private var cursorHotY = 0

    /** Where the pointer is, in the desktop's pixels. */
    private var pointerX = 0f
    private var pointerY = 0f
    private var buttons = 0

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downTime = 0L
    private var moved = false
    private var longPressed = false
    private var dragging = false
    private var twoFinger = false
    private var twoFingerMoved = false
    private var twoFingerDown = 0L
    private var startSpan = 0f
    private var lastSpan = 0f
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var wheelAccum = 0f
    private var zooming = false

    private val longPress = Runnable {
        if (moved || twoFinger) return@Runnable
        longPressed = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        if (touchpad) {
            pressButtons(BUTTON_LEFT) // the drag that follows holds it
        } else {
            movePointerTo(downX, downY)
            click(BUTTON_RIGHT)
        }
    }

    private val keysDown = HashMap<Int, Int>()

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(Color.BLACK)
    }

    // ---- what the client reports (its reader thread) --------------------------------------------

    override fun onResize(width: Int, height: Int) {
        post {
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            synchronized(dirtyLock) { dirty.set(0, 0, width, height) }
            pointerX = pointerX.coerceIn(0f, width - 1f)
            pointerY = pointerY.coerceIn(0f, height - 1f)
            updateMatrix()
            flush()
        }
    }

    override fun onUpdate(x: Int, y: Int, width: Int, height: Int) {
        synchronized(dirtyLock) { dirty.union(x, y, x + width, y + height) }
    }

    override fun onUpdateEnd() {
        if (!flushPosted) {
            flushPosted = true
            postOnAnimation { flush() }
        }
    }

    override fun onCursor(width: Int, height: Int, hotX: Int, hotY: Int, pixels: IntArray?) {
        val shape = if (pixels == null || width == 0 || height == 0) null
        else Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        post {
            cursor = shape
            cursorHotX = hotX
            cursorHotY = hotY
            invalidate()
        }
    }

    /** Copies the changed pixels into the bitmap (on the main thread, where it is drawn). */
    private fun flush() {
        flushPosted = false
        val c = client ?: return
        val bmp = bitmap ?: return
        val r = Rect()
        synchronized(dirtyLock) {
            r.set(dirty)
            dirty.setEmpty()
        }
        synchronized(c.lock) {
            if (bmp.width != c.width || bmp.height != c.height) return
            if (!r.intersect(0, 0, c.width, c.height)) return
            bmp.setPixels(c.pixels, r.top * c.width + r.left, c.width, r.left, r.top, r.width(), r.height())
        }
        invalidate()
    }

    // ---- drawing --------------------------------------------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateMatrix()
        onViewSizeChanged?.invoke(w, h, w != oldw)
    }

    private fun updateMatrix() {
        val bmp = bitmap ?: return
        if (width == 0 || bmp.width == 0) return
        baseScale = width.toFloat() / bmp.width
        val scale = baseScale * zoom
        val contentW = bmp.width * scale
        val contentH = bmp.height * scale
        offsetX = if (contentW <= width) (width - contentW) / 2 else offsetX.coerceIn(width - contentW, 0f)
        offsetY = if (contentH <= height) 0f else offsetY.coerceIn(height - contentH, 0f)
        matrix.setScale(scale, scale)
        matrix.postTranslate(offsetX, offsetY)
        matrix.invert(inverse)
        invalidate()
    }

    /** Keeps the pointer in view when the desktop is larger than the view (zoomed, or the keyboard is up). */
    private fun followPointer() {
        val bmp = bitmap ?: return
        val scale = baseScale * zoom
        val margin = 48 * resources.displayMetrics.density
        val sx = pointerX * scale + offsetX
        val sy = pointerY * scale + offsetY
        if (sx < margin) offsetX += margin - sx
        if (sx > width - margin) offsetX -= sx - (width - margin)
        if (sy < margin) offsetY += margin - sy
        if (sy > height - margin) offsetY -= sy - (height - margin)
        if (bmp.width * scale > width || bmp.height * scale > height) updateMatrix() else invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = bitmap ?: return
        paint.isFilterBitmap = baseScale * zoom < 2f
        canvas.drawBitmap(bmp, matrix, paint)
        val shape = cursor ?: return
        val scale = baseScale * zoom
        canvas.save()
        canvas.translate((pointerX - cursorHotX) * scale + offsetX, (pointerY - cursorHotY) * scale + offsetY)
        canvas.scale(scale.coerceAtLeast(1f), scale.coerceAtLeast(1f))
        canvas.drawBitmap(shape, 0f, 0f, paint)
        canvas.restore()
    }

    // ---- pointer --------------------------------------------------------------------------------

    private fun toDesktop(x: Float, y: Float): FloatArray {
        val pt = floatArrayOf(x, y)
        inverse.mapPoints(pt)
        val bmp = bitmap
        if (bmp != null) {
            pt[0] = pt[0].coerceIn(0f, bmp.width - 1f)
            pt[1] = pt[1].coerceIn(0f, bmp.height - 1f)
        }
        return pt
    }

    private fun sendPointer() {
        val c = client ?: return
        val x = pointerX.toInt()
        val y = pointerY.toInt()
        val b = buttons
        sendAsync { c.sendPointer(b, x, y) }
        invalidate()
    }

    private fun movePointerTo(viewX: Float, viewY: Float) {
        val pt = toDesktop(viewX, viewY)
        pointerX = pt[0]
        pointerY = pt[1]
        sendPointer()
    }

    private fun movePointerBy(dx: Float, dy: Float) {
        val bmp = bitmap ?: return
        // A touchpad moves the pointer a little faster than the finger
        val factor = 1.4f / (baseScale * zoom)
        pointerX = (pointerX + dx * factor).coerceIn(0f, bmp.width - 1f)
        pointerY = (pointerY + dy * factor).coerceIn(0f, bmp.height - 1f)
        sendPointer()
        followPointer()
    }

    private fun pressButtons(mask: Int) {
        buttons = buttons or mask
        sendPointer()
    }

    private fun releaseButtons(mask: Int) {
        buttons = buttons and mask.inv()
        sendPointer()
    }

    private fun click(mask: Int) {
        pressButtons(mask)
        releaseButtons(mask)
    }

    private fun wheel(steps: Int) {
        val mask = if (steps < 0) BUTTON_WHEEL_UP else BUTTON_WHEEL_DOWN
        repeat(abs(steps)) { click(mask) }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(android.view.InputDevice.SOURCE_MOUSE)) return onMouseEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestFocus()
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                downTime = event.eventTime
                moved = false
                longPressed = false
                dragging = false
                twoFinger = false
                zooming = false
                if (!touchpad) movePointerTo(event.x, event.y)
                postDelayed(longPress, longPressTimeout)
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (event.pointerCount == 2) {
                removeCallbacks(longPress)
                if (dragging || longPressed) releaseButtons(BUTTON_LEFT)
                dragging = false
                twoFinger = true
                twoFingerMoved = false
                twoFingerDown = event.eventTime
                startSpan = span(event)
                lastSpan = startSpan
                lastFocusX = focusX(event)
                lastFocusY = focusY(event)
                wheelAccum = 0f
            }
            MotionEvent.ACTION_MOVE -> {
                if (twoFinger && event.pointerCount >= 2) {
                    twoFingers(event)
                } else if (!twoFinger) {
                    val dist = hypot(event.x - downX, event.y - downY)
                    if (!moved && dist > touchSlop) {
                        moved = true
                        if (!longPressed) removeCallbacks(longPress)
                    }
                    if (moved) {
                        if (touchpad) {
                            movePointerBy(event.x - lastX, event.y - lastY)
                        } else {
                            if (!dragging && !longPressed) {
                                dragging = true
                                pressButtons(BUTTON_LEFT)
                            }
                            movePointerTo(event.x, event.y)
                        }
                    }
                }
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_POINTER_UP -> if (twoFinger && !twoFingerMoved && touchpad &&
                event.eventTime - twoFingerDown < TWO_FINGER_TAP_MS
            ) {
                click(BUTTON_RIGHT)
                twoFingerMoved = true // once
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                when {
                    twoFinger -> Unit
                    dragging -> {
                        movePointerTo(event.x, event.y)
                        releaseButtons(BUTTON_LEFT)
                    }
                    longPressed -> if (touchpad) releaseButtons(BUTTON_LEFT)
                    !moved -> {
                        if (!touchpad) movePointerTo(event.x, event.y)
                        click(BUTTON_LEFT)
                    }
                }
                dragging = false
                twoFinger = false
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                if (buttons != 0) releaseButtons(buttons)
                dragging = false
                twoFinger = false
            }
        }
        return true
    }

    /** Two fingers: apart or together zooms the view, up or down turns the wheel. */
    private fun twoFingers(event: MotionEvent) {
        val s = span(event)
        val fx = focusX(event)
        val fy = focusY(event)
        if (!zooming && abs(s - startSpan) > 4 * touchSlop) zooming = true
        if (zooming) {
            val factor = if (lastSpan > 0) s / lastSpan else 1f
            val newZoom = (zoom * factor).coerceIn(1f, 6f)
            val applied = newZoom / zoom
            // zoom around the fingers
            offsetX = fx - (fx - offsetX) * applied + (fx - lastFocusX)
            offsetY = fy - (fy - offsetY) * applied + (fy - lastFocusY)
            zoom = newZoom
            updateMatrix()
            twoFingerMoved = true
        } else {
            wheelAccum += fy - lastFocusY
            val step = WHEEL_STEP_DP * resources.displayMetrics.density
            while (abs(wheelAccum) >= step) {
                // fingers up scroll down, as on a phone
                wheel(if (wheelAccum < 0) 1 else -1)
                wheelAccum -= if (wheelAccum < 0) -step else step
                twoFingerMoved = true
            }
            if (abs(fy - lastFocusY) > touchSlop / 2f) twoFingerMoved = true
        }
        lastSpan = s
        lastFocusX = fx
        lastFocusY = fy
    }

    /** A real mouse (or a touchpad of a keyboard): its buttons and wheel as they are. */
    private fun onMouseEvent(event: MotionEvent): Boolean {
        movePointerTo(event.x, event.y)
        var mask = 0
        if (event.buttonState and MotionEvent.BUTTON_PRIMARY != 0) mask = mask or BUTTON_LEFT
        if (event.buttonState and MotionEvent.BUTTON_TERTIARY != 0) mask = mask or BUTTON_MIDDLE
        if (event.buttonState and MotionEvent.BUTTON_SECONDARY != 0) mask = mask or BUTTON_RIGHT
        if (mask != buttons) {
            buttons = mask
            sendPointer()
        }
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(android.view.InputDevice.SOURCE_CLASS_POINTER)) {
            when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_MOVE -> {
                    movePointerTo(event.x, event.y)
                    return true
                }
                MotionEvent.ACTION_SCROLL -> {
                    val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                    if (v != 0f) wheel(if (v > 0) -1 else 1)
                    return true
                }
            }
        }
        return super.onGenericMotionEvent(event)
    }

    private fun span(e: MotionEvent) = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
    private fun focusX(e: MotionEvent) = (e.getX(0) + e.getX(1)) / 2
    private fun focusY(e: MotionEvent) = (e.getY(0) + e.getY(1)) / 2

    /** Back to the whole desktop in view. */
    fun resetZoom() {
        zoom = 1f
        offsetX = 0f
        offsetY = 0f
        updateMatrix()
    }

    // ---- keyboard -------------------------------------------------------------------------------

    /** A key of the key bar or the keyboard: down and up, with the held modifiers around it. */
    fun sendKeysym(keysym: Int) {
        val c = client ?: return
        val mods = heldModifiers()
        sendAsync {
            for (m in mods) c.sendKey(m, true)
            c.sendKey(keysym, true)
            c.sendKey(keysym, false)
            for (m in mods.reversed()) c.sendKey(m, false)
        }
        if (mods.isNotEmpty()) onModifiersUsed()
    }

    fun sendText(text: CharSequence) {
        for (sym in XKeySyms.forText(text)) sendKeysym(sym)
    }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // No suggestions and no composing: each key goes to X as it is typed
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, false) {
            // What an input method that composes anyway (gesture typing, Chinese and Japanese input)
            // has typed so far: X gets it at once, and each change replaces it, by erasing what
            // differs from the end and typing the new rest
            private var composing = ""

            private fun replaceComposing(text: String) {
                var common = 0
                while (common < composing.length && common < text.length && composing[common] == text[common]) common++
                if (common > 0 && Character.isHighSurrogate(composing[common - 1])) common-- // a whole character
                repeat(composing.codePointCount(common, composing.length)) { sendKeysym(XKeySyms.BACKSPACE) }
                if (common < text.length) sendText(text.substring(common))
                composing = text
            }

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                replaceComposing(text?.toString() ?: "")
                composing = ""
                return true
            }

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                replaceComposing(text?.toString() ?: "")
                return true
            }

            override fun finishComposingText(): Boolean {
                composing = ""
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength.coerceAtMost(64)) { sendKeysym(XKeySyms.BACKSPACE) }
                repeat(afterLength.coerceAtMost(64)) { sendKeysym(XKeySyms.DELETE) }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) onKeyDown(event.keyCode, event)
                else if (event.action == KeyEvent.ACTION_UP) onKeyUp(event.keyCode, event)
                return true
            }
        }
    }

    private fun keysymFor(keyCode: Int, event: KeyEvent): Int {
        val special = XKeySyms.forKeyCode(keyCode)
        if (special != 0) return special
        // The character without Ctrl or Alt (X applies those itself from the modifier keys)
        val cp = event.getUnicodeChar(event.metaState and (KeyEvent.META_SHIFT_MASK or KeyEvent.META_CAPS_LOCK_ON))
        return if (cp > 0) XKeySyms.forCodePoint(cp) else 0
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) return false
        val sym = keysymFor(keyCode, event)
        if (sym == 0) return super.onKeyDown(keyCode, event)
        val c = client ?: return true
        keysDown[keyCode] = sym
        val mods = if (XKeySyms.forKeyCode(keyCode) in MODIFIERS) IntArray(0) else heldModifiers()
        sendAsync {
            for (m in mods) c.sendKey(m, true)
            c.sendKey(sym, true)
        }
        if (mods.isNotEmpty()) {
            post {
                sendAsync { for (m in mods.reversed()) c.sendKey(m, false) }
                onModifiersUsed()
            }
        }
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) return false
        val sym = keysDown.remove(keyCode) ?: keysymFor(keyCode, event)
        if (sym == 0) return super.onKeyUp(keyCode, event)
        val c = client ?: return true
        sendAsync { c.sendKey(sym, false) }
        return true
    }

    /** Sends the release of whatever is still down (the screen is left). */
    fun releaseEverything() {
        val c = client ?: return
        val held = keysDown.values.toList()
        keysDown.clear()
        val hadButtons = buttons != 0
        buttons = 0
        val x = pointerX.toInt()
        val y = pointerY.toInt()
        sendAsync {
            for (s in held) c.sendKey(s, false)
            if (hadButtons) c.sendPointer(0, x, y)
        }
    }

    private fun sendAsync(block: () -> Unit) {
        SENDER.execute {
            try {
                block()
            } catch (e: Exception) {
                // the connection is gone: the activity sees it from the reader
            }
        }
    }

    companion object {
        const val BUTTON_LEFT = 1
        const val BUTTON_MIDDLE = 2
        const val BUTTON_RIGHT = 4
        const val BUTTON_WHEEL_UP = 8
        const val BUTTON_WHEEL_DOWN = 16
        private const val TWO_FINGER_TAP_MS = 250L
        private const val WHEEL_STEP_DP = 24f

        private val MODIFIERS = setOf(
            XKeySyms.SHIFT_L, XKeySyms.SHIFT_R, XKeySyms.CONTROL_L, XKeySyms.CONTROL_R,
            XKeySyms.ALT_L, XKeySyms.ALT_R, XKeySyms.SUPER_L, XKeySyms.SUPER_R
        )

        /** One thread for what goes to the X server, in order (no network on the main thread). */
        private val SENDER = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "Cortex-DesktopSend").apply { isDaemon = true }
        }
    }
}
