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
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import kotlin.math.abs

/**
 * Shows the PC's screen and turns touches into mouse input:
 * tap = click, double tap = double click, long press = right click,
 * two fingers the same way = scroll (up/down, left/right), two fingers
 * apart/together = zoom, one finger = move around when zoomed in.
 * With the keyboard up, typing goes to the PC.
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
    private val xform = Matrix()
    private val inverse = Matrix()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private var tapX = 0f
    private var tapY = 0f
    private var tapAt = 0L

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
        xform.setScale(s, s)
        xform.postTranslate((width - w) / 2 + panX, (height - h) / 2 + panY)
        xform.invert(inverse)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val c = client ?: return
        synchronized(c.lock) {
            val b = c.bitmap ?: return
            layoutMatrix(b.width, b.height)
            canvas.drawBitmap(b, xform, paint)
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
            longPressed = true
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (multi) return true
            if (longPressed) {
                longPressed = false
                dragging = true
                val p = toFrame(e2.x, e2.y)
                client?.down(p[0], p[1])
            }
            if (dragging) {
                val p = toFrame(e2.x, e2.y)
                client?.move(p[0], p[1])
                return true
            }

            // One finger moves around a zoomed-in screen.
            if (zoom <= 1.01f) return true
            panX -= dx
            panY -= dy
            invalidate()
            return true
        }
    })

    // Two fingers: apart/together = zoom, same direction = scroll. Decided
    // once per gesture so the two never mix.
    private enum class Two { UNDECIDED, ZOOM, SCROLL }

    private var longPressed = false
    private var dragging = false
    private var multi = false
    private var two = Two.UNDECIDED
    private var lastSpan = 0f
    private var lastFx = 0f
    private var lastFy = 0f
    private var accSpan = 0f
    private var accX = 0f
    private var accY = 0f
    private var restX = 0f
    private var restY = 0f
    private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop * 1.5f

    private fun twoFingers(e: MotionEvent): Triple<Float, Float, Float> {
        val x0 = e.getX(0)
        val y0 = e.getY(0)
        val x1 = e.getX(1)
        val y1 = e.getY(1)
        return Triple(kotlin.math.hypot(x1 - x0, y1 - y0), (x0 + x1) / 2, (y0 + y1) / 2)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {

        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> if (event.pointerCount == 2) {
                // Second finger: no tap / long press any more.
                val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                gestures.onTouchEvent(cancel)
                cancel.recycle()
                multi = true
                two = Two.UNDECIDED
                val (span, fx, fy) = twoFingers(event)
                lastSpan = span
                lastFx = fx
                lastFy = fy
                accSpan = 0f
                accX = 0f
                accY = 0f
                restX = 0f
                restY = 0f
            }
            MotionEvent.ACTION_MOVE -> if (multi && event.pointerCount >= 2) {
                val (span, fx, fy) = twoFingers(event)
                val dSpan = span - lastSpan
                val dx = fx - lastFx
                val dy = fy - lastFy
                if (two == Two.UNDECIDED) {
                    accSpan += dSpan
                    accX += dx
                    accY += dy
                    if (abs(accSpan) > slop * 1.5f) {
                        two = Two.ZOOM
                    } else if (kotlin.math.hypot(accX, accY) > slop) {
                        two = Two.SCROLL
                        // Point the mouse between the fingers so that window scrolls.
                        val p = toFrame(fx, fy)
                        client?.move(p[0], p[1])
                    }
                }
                when (two) {
                    Two.ZOOM -> if (lastSpan > 0f) {
                        val old = zoom
                        zoom = (zoom * span / lastSpan).coerceIn(1f, 6f)
                        val f = zoom / old
                        val cx = width / 2f + panX
                        val cy = height / 2f + panY
                        panX += (cx - fx) * (f - 1) + dx
                        panY += (cy - fy) * (f - 1) + dy
                        invalidate()
                    }
                    Two.SCROLL -> {
                        // Like a touchpad: fingers up = page moves up (scroll down).
                        restY -= dy
                        restX -= dx
                        val lines = (restY / 40f).toInt()
                        if (lines != 0) {
                            restY -= lines * 40f
                            client?.scroll(lines)
                        }
                        val cols = (restX / 60f).toInt()
                        if (cols != 0) {
                            restX -= cols * 60f
                            client?.scrollSideways(cols)
                        }
                    }
                    Two.UNDECIDED -> {}
                }
                lastSpan = span
                lastFx = fx
                lastFy = fy
            }
            MotionEvent.ACTION_POINTER_UP -> if (event.pointerCount <= 2) {
                // Down to one finger: stop until the next gesture.
                two = Two.UNDECIDED
                lastSpan = 0f
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (longPressed) {
                    val p = toFrame(event.x, event.y)
                    client?.click(p[0], p[1], button = 'r')
                    feedback(event.x, event.y)
                } else if (dragging) {
                    val p = toFrame(event.x, event.y)
                    client?.up(p[0], p[1])
                }
                longPressed = false
                dragging = false

                if (!multi) gestures.onTouchEvent(event)
                multi = false
                two = Two.UNDECIDED
                return true
            }
        }
        if (!multi) gestures.onTouchEvent(event)

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
            private var composing = ""
            
            private fun typeDiff(diff: String) {
                diff.split('\n').forEachIndexed { i, part ->
                    if (i > 0) client?.key("enter")
                    client?.type(part)
                }
            }

            override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
                val current = text.toString()
                if (current.startsWith(composing)) {
                    val diff = current.substring(composing.length)
                    if (diff.isNotEmpty()) typeDiff(diff)
                } else {
                    repeat(composing.length) { client?.key("backspace") }
                    if (current.isNotEmpty()) typeDiff(current)
                }
                composing = current
                return true
            }

            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                val current = text.toString()
                if (current.startsWith(composing)) {
                    val diff = current.substring(composing.length)
                    if (diff.isNotEmpty()) typeDiff(diff)
                } else {
                    repeat(composing.length) { client?.key("backspace") }
                    if (current.isNotEmpty()) typeDiff(current)
                }
                composing = ""
                return true
            }

            override fun finishComposingText(): Boolean {
                composing = ""
                return super.finishComposingText()
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) onKeyDown(event.keyCode, event)
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength.coerceAtLeast(1)) { client?.key("backspace") }
                return true
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



