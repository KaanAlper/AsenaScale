package dev.asenascale.screen

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.os.SystemClock
import android.text.InputType
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import kotlin.math.abs

/**
 * Shows the PC's screen and turns touches into mouse input:
 * tap = click, double tap = double click, long press = right click,
 * one-finger drag = scroll (pans instead when zoomed in),
 * two fingers = zoom and pan. With the keyboard up, typing goes to the PC.
 */
class ScreenView(context: Context) : View(context) {
    var client: ScreenClient? = null
        set(value) {
            field?.onFrame = null
            field = value
            value?.onFrame = { invalidate() }
            zoom = 1f
            panX = 0f
            panY = 0f
            invalidate()
        }

    /** User zoom on top of fit-to-view, and pan in view pixels. */
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private val matrix = Matrix()
    private val inverse = Matrix()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private var tapX = 0f
    private var tapY = 0f
    private var tapAt = 0L
    private var scrollRest = 0f

    var accent: Int = Color.WHITE
        set(value) {
            field = value
            ring.color = value
        }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun resetZoom() {
        zoom = 1f
        panX = 0f
        panY = 0f
        invalidate()
    }

    private fun layoutMatrix(bw: Int, bh: Int) {
        val fit = minOf(width / bw.toFloat(), height / bh.toFloat())
        val s = fit * zoom
        val w = bw * s
        val h = bh * s
        // Keep the picture on screen: centered when smaller, edges clamped when larger.
        panX = if (w <= width) 0f else panX.coerceIn(-(w - width) / 2, (w - width) / 2)
        panY = if (h <= height) 0f else panY.coerceIn(-(h - height) / 2, (h - height) / 2)
        matrix.setScale(s, s)
        matrix.postTranslate((width - w) / 2 + panX, (height - h) / 2 + panY)
        matrix.invert(inverse)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val c = client ?: return
        synchronized(c.lock) {
            val b = c.bitmap ?: return
            layoutMatrix(b.width, b.height)
            canvas.drawBitmap(b, matrix, paint)
        }
        // A short ring where the click landed.
        val age = SystemClock.uptimeMillis() - tapAt
        if (age < 350) {
            ring.alpha = (255 * (1 - age / 350f)).toInt()
            canvas.drawCircle(tapX, tapY, 14f + age / 12f, ring)
            postInvalidateOnAnimation()
        }
    }

    /** View point -> PC frame pixel. */
    private fun toFrame(x: Float, y: Float): FloatArray = floatArrayOf(x, y).also { inverse.mapPoints(it) }

    private fun feedback(x: Float, y: Float) {
        tapX = x
        tapY = y
        tapAt = SystemClock.uptimeMillis()
        invalidate()
    }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            val p = toFrame(e.x, e.y)
            client?.click(p[0], p[1])
            feedback(e.x, e.y)
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val p = toFrame(e.x, e.y)
            client?.click(p[0], p[1], count = 2)
            feedback(e.x, e.y)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            val p = toFrame(e.x, e.y)
            client?.click(p[0], p[1], button = 'r')
            feedback(e.x, e.y)
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (e2.pointerCount > 1 || scaling) return true
            if (zoom > 1.01f) {
                panX -= dx
                panY -= dy
                invalidate()
            } else {
                // Wheel: about one line per 40 px of finger travel. Move the
                // pointer there first so the right window scrolls.
                scrollRest += dy
                val lines = (scrollRest / 40f).toInt()
                if (lines != 0) {
                    scrollRest -= lines * 40f
                    val p = toFrame(e2.x, e2.y)
                    client?.move(p[0], p[1])
                    client?.scroll(lines)
                }
            }
            return true
        }
    })

    private var scaling = false
    private var lastFocusX = 0f
    private var lastFocusY = 0f

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
            scaling = true
            lastFocusX = d.focusX
            lastFocusY = d.focusY
            return true
        }

        override fun onScale(d: ScaleGestureDetector): Boolean {
            val old = zoom
            zoom = (zoom * d.scaleFactor).coerceIn(1f, 6f)
            val f = zoom / old
            // Zoom around the fingers, and follow them as they move.
            val cx = width / 2f + panX
            val cy = height / 2f + panY
            panX += (cx - d.focusX) * (f - 1) + (d.focusX - lastFocusX)
            panY += (cy - d.focusY) * (f - 1) + (d.focusY - lastFocusY)
            lastFocusX = d.focusX
            lastFocusY = d.focusY
            invalidate()
            return true
        }

        override fun onScaleEnd(d: ScaleGestureDetector) {
            scaling = false
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaler.onTouchEvent(event)
        if (event.pointerCount == 1 && !scaling) gestures.onTouchEvent(event)
        else if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            // Second finger: cancel the pending tap / long press.
            val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
            gestures.onTouchEvent(cancel)
            cancel.recycle()
        }
        if (event.actionMasked == MotionEvent.ACTION_UP) scrollRest = 0f
        return true
    }

    // --- Keyboard ------------------------------------------------------------------

    fun toggleKeyboard(show: Boolean) {
        val imm = context.getSystemService(InputMethodManager::class.java)
        if (show) {
            requestFocus()
            imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        } else {
            imm.hideSoftInputFromWindow(windowToken, 0)
        }
    }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return object : BaseInputConnection(this, true) {
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                super.commitText(text, newCursorPosition)
                flush()
                return true
            }

            override fun finishComposingText(): Boolean {
                super.finishComposingText()
                flush()
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength.coerceAtLeast(1)) { client?.key("backspace") }
                return super.deleteSurroundingText(beforeLength, afterLength)
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) onKeyDown(event.keyCode, event)
                return true
            }

            private fun flush() {
                val content = editable ?: return
                if (content.isNotEmpty()) {
                    val text = content.toString()
                    // Enter from the IME arrives as "\n": send it as the key.
                    text.split('\n').forEachIndexed { i, part ->
                        if (i > 0) client?.key("enter")
                        client?.type(part)
                    }
                }
                content.clear()
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val name = when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "enter"
            KeyEvent.KEYCODE_DEL -> "backspace"
            KeyEvent.KEYCODE_FORWARD_DEL -> "del"
            KeyEvent.KEYCODE_TAB -> "tab"
            KeyEvent.KEYCODE_ESCAPE -> "esc"
            KeyEvent.KEYCODE_DPAD_UP -> "up"
            KeyEvent.KEYCODE_DPAD_DOWN -> "down"
            KeyEvent.KEYCODE_DPAD_LEFT -> "left"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
            else -> null
        }
        if (name != null) {
            client?.key(name)
            return true
        }
        val ch = event.unicodeChar
        if (ch != 0 && abs(ch) < 0x110000) {
            client?.type(String(Character.toChars(ch)))
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
