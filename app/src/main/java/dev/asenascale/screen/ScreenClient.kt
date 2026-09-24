package dev.asenascale.screen

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import dev.asenascale.App
import dev.asenascale.data.Host
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.DataInputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One screen-sharing stream from the PC app (`mc screen`): keeps a bitmap
 * of the PC's screen up to date from changed-region JPEGs and sends input
 * back as text lines. See host/src/screen.rs for the wire format.
 */
class ScreenClient(private val host: Host, monitor: Int) {
    data class Info(val monitor: Int, val monitors: Int, val width: Int, val height: Int)

    private val _info = MutableStateFlow<Info?>(null)
    val info: StateFlow<Info?> = _info
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    /** Bytes received per second, for the data meter. */
    private val _rate = MutableStateFlow(0L)
    val rate: StateFlow<Long> = _rate

    /** The PC screen, frame-sized. Read under [lock]. */
    @Volatile var bitmap: Bitmap? = null
        private set
    val lock = Any()
    /** Called on the main thread after each complete frame. */
    var onFrame: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val writer = Executors.newSingleThreadExecutor()
    private var out: OutputStream? = null
    @Volatile private var release: (() -> Unit)? = null
    @Volatile private var closed = false

    init {
        Thread({ run(monitor) }, "screen").start()
    }

    private fun run(monitor: Int) {
        try {
            val (ch, done) = App.instance.transfers.openChannel(host, "mc screen $monitor")
            val once = AtomicBoolean()
            release = { if (once.compareAndSet(false, true)) done() }
            if (closed) {
                release?.invoke()
                return
            }
            val input = DataInputStream(ch.inputStream.buffered(64 * 1024))
            out = ch.outputStream
            ch.connect(15_000)
            var windowStart = System.currentTimeMillis()
            var windowBytes = 0L
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            while (!closed) {
                when (val tag = input.read()) {
                    -1 -> break
                    'H'.code -> {
                        val m = input.readUnsignedByte()
                        val count = input.readUnsignedByte()
                        val w = input.readUnsignedShort()
                        val h = input.readUnsignedShort()
                        synchronized(lock) { bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }
                        _info.value = Info(m, count, w, h)
                    }
                    'T'.code -> {
                        val x = input.readUnsignedShort()
                        val y = input.readUnsignedShort()
                        input.readUnsignedShort() // w
                        input.readUnsignedShort() // h
                        val len = input.readInt()
                        val jpeg = ByteArray(len)
                        input.readFully(jpeg)
                        windowBytes += len + 13
                        val tile = BitmapFactory.decodeByteArray(jpeg, 0, len, opts) ?: continue
                        synchronized(lock) { bitmap?.let { Canvas(it).drawBitmap(tile, x.toFloat(), y.toFloat(), null) } }
                        tile.recycle()
                    }
                    'F'.code -> {
                        val now = System.currentTimeMillis()
                        if (now - windowStart >= 1000) {
                            _rate.value = windowBytes * 1000 / (now - windowStart)
                            windowStart = now
                            windowBytes = 0
                        }
                        main.post { onFrame?.invoke() }
                    }
                    else -> error("bad stream ($tag)")
                }
            }
        } catch (e: Exception) {
            if (!closed) {
                Log.w("screen", "stream failed", e)
                _error.value = e.message ?: e.toString()
            }
        } finally {
            release?.invoke()
            release = null
        }
    }

    private fun send(line: String) {
        writer.execute {
            val o = out ?: return@execute
            try {
                o.write((line + "\n").toByteArray())
                o.flush()
            } catch (_: Exception) {
            }
        }
    }

    fun click(x: Float, y: Float, button: Char = 'l', count: Int = 1) = send("c ${x.toInt()} ${y.toInt()} $button $count")
    fun move(x: Float, y: Float) = send("m ${x.toInt()} ${y.toInt()}")
    fun scroll(lines: Int) = send("w $lines")
    fun key(combo: String) = send("k $combo")
    fun monitor(i: Int) = send("o $i")
    fun quality(q: Int, maxWidth: Int) = send("q $q $maxWidth")
    fun type(text: String) {
        if (text.isEmpty()) return
        send("t " + Base64.encodeToString(text.toByteArray(), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
    }

    fun close() {
        if (closed) return
        closed = true
        writer.execute {
            runCatching { out?.close() }
            release?.invoke()
            release = null
        }
        writer.shutdown()
    }
}
