package dev.mobileclaude.term

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.InputType
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.OverScroller
import androidx.core.content.res.ResourcesCompat
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalEmulator
import com.termux.view.TerminalRenderer
import dev.mobileclaude.R
import dev.mobileclaude.ssh.SshConnection
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Draws a [TerminalEmulator] straight onto a Canvas and turns touch / IME /
 * hardware keys into bytes for the SSH channel. No WebView, no Compose text
 * layout: one drawText per style run, redrawn only when the screen changes.
 */
@SuppressLint("ViewConstructor")
class TerminalCanvasView(context: Context) : View(context) {

    var connection: SshConnection? = null
        set(value) {
            field?.onScreenUpdate = null
            field = value
            value?.onScreenUpdate = { onScreenChanged() }
            value?.onBell = { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
            value?.emulator?.setCursorBlinkingEnabled(true)
            topRow = 0
            updateSize()
            invalidate()
        }

    /** Sticky modifiers driven by the extra-keys bar. Cleared after one key. */
    var ctrlDown = false
    var altDown = false
    var onModifiersConsumed: (() -> Unit)? = null
    var onLongPress: (() -> Unit)? = null

    private val typeface: Typeface =
        ResourcesCompat.getFont(context, R.font.jetbrains_mono) ?: Typeface.MONOSPACE
    private val prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE)
    private var textSizePx = prefs.getFloat("fontPx", 13.5f * resources.displayMetrics.scaledDensity)
    private var renderer = TerminalRenderer(textSizePx.roundToInt(), typeface)
    private val padding = (6 * resources.displayMetrics.density).roundToInt()

    /** Scrollback offset: 0 = bottom, negative = looking at history. */
    private var topRow = 0
    private var scrollRemainder = 0f

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(TermTheme.BACKGROUND)
    }

    val emulator: TerminalEmulator? get() = connection?.emulator

    private fun onScreenChanged() {
        val emu = emulator ?: return
        // Stay pinned to the bottom unless the user scrolled up; if they did,
        // keep the same lines in view as new output arrives.
        if (topRow != 0) {
            topRow = (topRow - emu.scrollCounter).coerceAtLeast(-emu.screen.activeTranscriptRows)
        }
        emu.clearScrollCounter()
        blinkOn = true
        invalidate()
    }

    // ---- layout & drawing ----------------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = updateSize()

    private fun updateSize() {
        val conn = connection ?: return
        if (width == 0 || height == 0) return
        val cols = max(4, ((width - 2 * padding) / renderer.fontWidth).toInt())
        val rows = max(4, (height - 2 * padding - topInset()) / renderer.fontLineSpacing)
        conn.resize(cols, rows, width, height)
        topRow = 0
        invalidate()
    }

    /** Gap TerminalRenderer leaves above the first row (line spacing + ascent). */
    private fun topInset(): Int {
        val p = Paint().apply { typeface = this@TerminalCanvasView.typeface; textSize = textSizePx.roundToInt().toFloat() }
        return kotlin.math.ceil(p.fontSpacing).toInt() + kotlin.math.ceil(p.ascent()).toInt()
    }

    override fun onDraw(canvas: Canvas) {
        val emu = emulator ?: return
        canvas.save()
        canvas.translate(padding.toFloat(), padding.toFloat())
        emu.setCursorBlinkState(blinkOn)
        renderer.render(emu, canvas, topRow, -1, -1, -1, -1)
        canvas.restore()
    }

    // Soft cursor blink, kitty style. Stops while idle in the background.
    private var blinkOn = true
    private val blink = object : Runnable {
        override fun run() {
            blinkOn = !blinkOn
            invalidate()
            postDelayed(this, 530)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postDelayed(blink, 530)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(blink)
        super.onDetachedFromWindow()
    }

    // ---- touch ---------------------------------------------------------------

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val emu = emulator
            if (emu != null && emu.isMouseTrackingActive) {
                val (col, row) = cellAt(e.x, e.y)
                emu.sendMouseEvent(TerminalEmulator.MOUSE_LEFT_BUTTON, col, row, true)
                emu.sendMouseEvent(TerminalEmulator.MOUSE_LEFT_BUTTON, col, row, false)
            }
            showKeyboard()
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scaling) return true
            lastTouchX = e2.x
            lastTouchY = e2.y
            scrollBy(dy)
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (scaling) return true
            flingLastY = 0
            scroller.fling(0, 0, 0, -vy.toInt(), 0, 0, Int.MIN_VALUE / 2, Int.MAX_VALUE / 2)
            postInvalidateOnAnimation()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onLongPress?.invoke()
        }
    })

    private val scroller = OverScroller(context)
    private var flingLastY = 0
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            val y = scroller.currY
            val stuck = !scrollBy((y - flingLastY).toFloat())
            flingLastY = y
            // Stop at the edges of the scrollback instead of spinning forever.
            if (stuck) scroller.forceFinished(true) else postInvalidateOnAnimation()
        }
    }

    /** Positive [dy] = finger moved up = newer content. False if already at an edge. */
    private fun scrollBy(dy: Float): Boolean {
        scrollRemainder += dy
        val lines = (scrollRemainder / renderer.fontLineSpacing).toInt()
        if (lines == 0) return true
        scrollRemainder -= lines * renderer.fontLineSpacing
        return scrollLines(lines)
    }

    private var scaling = false
    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            scaling = true
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val min = 8f * resources.displayMetrics.scaledDensity
            val maxSize = 28f * resources.displayMetrics.scaledDensity
            val next = (textSizePx * detector.scaleFactor).coerceIn(min, maxSize)
            if (abs(next - textSizePx) >= 1f) {
                textSizePx = next
                renderer = TerminalRenderer(textSizePx.roundToInt(), typeface)
                updateSize()
            }
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            scaling = false
            prefs.edit().putFloat("fontPx", textSizePx).apply()
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaler.onTouchEvent(event)
        gestures.onTouchEvent(event)
        return true
    }

    /** Positive [lines] = finger moved up = show newer content. */
    private fun scrollLines(lines: Int): Boolean {
        val emu = emulator ?: return false
        val up = lines < 0
        val count = abs(lines)
        when {
            emu.isMouseTrackingActive -> {
                val (col, row) = cellAt(lastTouchX, lastTouchY)
                val button = if (up) TerminalEmulator.MOUSE_WHEELUP_BUTTON else TerminalEmulator.MOUSE_WHEELDOWN_BUTTON
                repeat(count) { emu.sendMouseEvent(button, col, row, true) }
            }
            emu.isAlternateBufferActive -> {
                // Full-screen apps without mouse support: behave like arrow keys.
                val code = KeyHandler.getCode(
                    if (up) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN, 0,
                    emu.isCursorKeysApplicationMode, emu.isKeypadApplicationMode,
                )
                repeat(count) { connection?.sendText(code) }
            }
            else -> {
                val next = (topRow + lines).coerceIn(-emu.screen.activeTranscriptRows, 0)
                if (next == topRow) {
                    scrollRemainder = 0f
                    return false
                }
                topRow = next
                invalidate()
            }
        }
        return true
    }

    private fun cellAt(x: Float, y: Float): Pair<Int, Int> {
        val emu = emulator!!
        val col = ((x - padding) / renderer.fontWidth).toInt().coerceIn(0, emu.mColumns - 1) + 1
        val row = ((y - padding) / renderer.fontLineSpacing).toInt().coerceIn(0, emu.mRows - 1) + 1
        return col to row
    }

    fun showKeyboard() {
        requestFocus()
        // Posted so it also works right after the view is attached.
        post { context.getSystemService(InputMethodManager::class.java).showSoftInput(this, 0) }
    }

    // ---- keyboard ------------------------------------------------------------

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // Visible-password + no-suggestions makes IMEs commit every key right
        // away instead of holding a composing word — essential for a terminal.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return object : BaseInputConnection(this, true) {
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                super.commitText(text, newCursorPosition)
                flushEditable()
                return true
            }

            override fun finishComposingText(): Boolean {
                super.finishComposingText()
                flushEditable()
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                val del = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)
                repeat(beforeLength.coerceAtLeast(1)) { sendKeyEvent(del) }
                return super.deleteSurroundingText(beforeLength, afterLength)
            }

            private fun flushEditable() {
                val content = editable ?: return
                if (content.isNotEmpty()) typeText(content.toString())
                content.clear()
            }
        }
    }

    /** Text from the IME or the extra-keys bar, with sticky Ctrl/Alt applied. */
    fun typeText(text: String) {
        val conn = connection ?: return
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            sendCodePoint(if (cp == '\n'.code) '\r'.code else cp, conn)
        }
    }

    private fun sendCodePoint(codePoint: Int, conn: SshConnection) {
        var cp = codePoint
        if (ctrlDown) {
            cp = when (cp) {
                in 'a'.code..'z'.code -> cp - 'a'.code + 1
                in 'A'.code..'Z'.code -> cp - 'A'.code + 1
                ' '.code, '2'.code, '@'.code -> 0
                '['.code, '3'.code -> 27
                '\\'.code, '4'.code -> 28
                ']'.code, '5'.code -> 29
                '^'.code, '6'.code -> 30
                '_'.code, '7'.code, '/'.code, '-'.code -> 31
                '8'.code, '?'.code -> 127
                else -> cp
            }
        }
        val sb = StringBuilder()
        if (altDown) sb.append('\u001b')
        sb.appendCodePoint(cp)
        conn.sendText(sb.toString())
        consumeModifiers()
    }

    private fun consumeModifiers() {
        if (ctrlDown || altDown) {
            ctrlDown = false
            altDown = false
            onModifiersConsumed?.invoke()
        }
        scrollToBottom()
    }

    private fun scrollToBottom() {
        if (topRow != 0) {
            topRow = 0
            invalidate()
        }
    }

    /** Sends a special key (arrows, Esc, Tab…) honoring terminal modes. */
    fun sendKey(keyCode: Int, shift: Boolean = false) {
        val emu = emulator ?: return
        var mod = 0
        if (ctrlDown) mod = mod or KeyHandler.KEYMOD_CTRL
        if (altDown) mod = mod or KeyHandler.KEYMOD_ALT
        if (shift) mod = mod or KeyHandler.KEYMOD_SHIFT
        val code = KeyHandler.getCode(keyCode, mod, emu.isCursorKeysApplicationMode, emu.isKeypadApplicationMode)
        if (code != null) connection?.sendText(code)
        consumeModifiers()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val emu = emulator ?: return super.onKeyDown(keyCode, event)
        if (keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyDown(keyCode, event)
        if (event.isSystem && keyCode != KeyEvent.KEYCODE_ENTER) return super.onKeyDown(keyCode, event)

        var mod = 0
        if (event.isCtrlPressed || ctrlDown) mod = mod or KeyHandler.KEYMOD_CTRL
        if (event.isAltPressed || altDown) mod = mod or KeyHandler.KEYMOD_ALT
        if (event.isShiftPressed) mod = mod or KeyHandler.KEYMOD_SHIFT
        if (event.isNumLockOn) mod = mod or KeyHandler.KEYMOD_NUM_LOCK

        val special = KeyHandler.getCode(keyCode, mod, emu.isCursorKeysApplicationMode, emu.isKeypadApplicationMode)
        if (special != null) {
            connection?.sendText(special)
            consumeModifiers()
            return true
        }

        // Printable character from a hardware keyboard (or the IME's key events).
        val meta = event.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK).inv()
        val cp = event.getUnicodeChar(meta)
        if (cp == 0) return super.onKeyDown(keyCode, event)
        if (cp and KeyCharacterMap.COMBINING_ACCENT != 0) return true
        ctrlDown = ctrlDown || event.isCtrlPressed
        altDown = altDown || event.isAltPressed
        connection?.let { sendCodePoint(cp, it) }
        return true
    }

    /** Scrollback + screen as text, capped so the selection view stays snappy. */
    fun copyAllText(maxLines: Int = 1500): String =
        (emulator?.screen?.transcriptTextWithoutJoinedLines ?: "").lines().takeLast(maxLines).joinToString("\n")

    fun paste(text: String) {
        emulator?.paste(text)
        scrollToBottom()
    }
}
