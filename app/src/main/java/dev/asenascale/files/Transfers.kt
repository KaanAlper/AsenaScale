package dev.asenascale.files

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import dev.asenascale.App
import dev.asenascale.R
import dev.asenascale.data.Host
import dev.asenascale.ssh.Keys
import dev.asenascale.ssh.execOn
import dev.asenascale.tailnet.HOST_APP_PORT
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * File transfers between the phone and the PC app, IDM style: the file is
 * cut into chunks and several workers move them at once over two SSH
 * connections of their own (uncompressed: photos and archives don't shrink,
 * and the terminal's connection stays snappy meanwhile).
 */
class Transfers(private val context: Context) {
    sealed interface Status {
        data object Running : Status
        data class Done(val result: String) : Status
        data class Failed(val message: String) : Status
        data object Cancelled : Status
    }

    class Transfer(
        val id: Long,
        val hostId: String,
        val upload: Boolean,
        val name: String,
        val total: Long,
    ) {
        private val _done = MutableStateFlow(0L)
        /** Bytes moved so far. */
        val done: StateFlow<Long> = _done
        private val _status = MutableStateFlow<Status>(Status.Running)
        val status: StateFlow<Status> = _status
        val startedAt = System.currentTimeMillis()
        @Volatile var cancelled = false
            internal set
        /** For downloads: where the file landed on the phone. */
        @Volatile var localUri: Uri? = null
            internal set

        internal val moved = AtomicLong()
        internal fun progress(n: Int) { _done.value = moved.addAndGet(n.toLong()) }
        internal fun finish(s: Status) { if (_status.value == Status.Running) _status.value = s }
    }

    private val _list = MutableStateFlow<List<Transfer>>(emptyList())
    /** Newest first; finished ones stay until cleared. */
    val list: StateFlow<List<Transfer>> = _list

    private val ids = AtomicLong()
    private val pool = Executors.newFixedThreadPool(WORKERS * 2) { r -> Thread(r, "transfer").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private val links = HashMap<String, Link>()

    /** Sends [uri] into [dir] on the PC ("" = the received-files folder). */
    fun upload(host: Host, uri: Uri, dir: String, onDone: ((String) -> Unit)? = null): Transfer {
        val name = displayName(uri)
        val size = runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } }.getOrNull() ?: -1
        val t = add(Transfer(ids.incrementAndGet(), host.id, true, name, size.coerceAtLeast(0)))
        Thread({ runUpload(t, host, uri, size, dir, onDone) }, "upload").start()
        return t
    }

    /** Fetches [entry] from the PC into the phone's Downloads/AsenaScale. */
    fun download(host: Host, entry: PcEntry): Transfer {
        val t = add(Transfer(ids.incrementAndGet(), host.id, false, entry.name, entry.size))
        Thread({ runDownload(t, host, entry) }, "download").start()
        return t
    }

    fun cancel(t: Transfer) {
        t.cancelled = true
        t.finish(Status.Cancelled)
    }

    fun clearFinished() {
        _list.value = _list.value.filter { it.status.value == Status.Running }
    }

    fun active(hostId: String) = _list.value.count { it.hostId == hostId && it.status.value == Status.Running }

    private fun add(t: Transfer): Transfer {
        _list.value = listOf(t) + _list.value.take(30)
        return t
    }

    // --- Upload --------------------------------------------------------------------

    private fun runUpload(t: Transfer, host: Host, uri: Uri, size: Long, dir: String, onDone: ((String) -> Unit)?) {
        val link = link(host)
        var remote: String? = null
        try {
            // Unknown size (some providers): spool to a temp file first.
            var spooled: File? = null
            val pfd: ParcelFileDescriptor = if (size >= 0) {
                context.contentResolver.openFileDescriptor(uri, "r") ?: error(str(R.string.file_unreadable))
            } else {
                val f = File.createTempFile("up", null, context.cacheDir).also { spooled = it }
                context.contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } }
                    ?: error(str(R.string.file_unreadable))
                ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
            }
            pfd.use {
                val channel = FileInputStream(pfd.fileDescriptor).channel
                val total = channel.size()
                val r = link.exec(0, "mc prepare ${if (dir.isEmpty()) "-" else PcFiles.b64(dir)} ${PcFiles.b64(t.name)} $total")
                val path = r.stdout.toString(Charsets.UTF_8).trim().ifEmpty { error(r.stderr.trim()) }
                remote = path
                chunked(t, total) { worker, offset, len ->
                    val buf = ByteBuffer.allocate(len)
                    while (buf.hasRemaining()) {
                        if (channel.read(buf, offset + buf.position()) < 0) break
                    }
                    val out = link.exec(worker, "mc write ${PcFiles.b64(path)} $offset", buf.array().copyOf(buf.position()), timeoutMs = 120_000)
                    if (out.exitCode != 0) error(out.stderr.trim().ifEmpty { "write failed" })
                    buf.position()
                }
                val done = link.exec(0, "mc done ${PcFiles.b64(path)}")
                if (done.exitCode != 0) error(done.stderr.trim())
                t.finish(Status.Done(path))
                main.post { onDone?.invoke(path) }
            }
            spooled?.delete()
        } catch (e: Exception) {
            Log.w("transfer", "upload failed", e)
            remote?.let { p -> runCatching { link.exec(0, "mc abort ${PcFiles.b64(p)}") } }
            t.finish(if (t.cancelled) Status.Cancelled else Status.Failed(e.message ?: e.toString()))
        } finally {
            release(host.id)
        }
    }

    // --- Download ------------------------------------------------------------------

    private fun runDownload(t: Transfer, host: Host, entry: PcEntry) {
        val link = link(host)
        var target: Target? = null
        try {
            target = Target.create(context, entry.name)
            val channel = target.channel
            val path = PcFiles.b64(entry.path)
            chunked(t, entry.size) { worker, offset, len ->
                val r = link.exec(worker, "mc read $path $offset $len", timeoutMs = 120_000)
                if (r.exitCode != 0) error(r.stderr.trim().ifEmpty { "read failed" })
                val buf = ByteBuffer.wrap(r.stdout)
                while (buf.hasRemaining()) channel.write(buf, offset + buf.position())
                r.stdout.size
            }
            target.commit()
            t.localUri = target.uri
            t.finish(Status.Done(target.label))
        } catch (e: Exception) {
            Log.w("transfer", "download failed", e)
            target?.discard()
            t.finish(if (t.cancelled) Status.Cancelled else Status.Failed(e.message ?: e.toString()))
        } finally {
            release(host.id)
        }
    }

    /** Where a download is written: MediaStore Downloads (Android 10+) or app storage. */
    private class Target(val uri: Uri, val label: String, private val pfd: ParcelFileDescriptor, private val file: File?) {
        val channel: FileChannel = FileOutputStream(pfd.fileDescriptor).channel

        fun commit() {
            channel.force(false)
            pfd.close()
            if (file == null && Build.VERSION.SDK_INT >= 29) {
                val v = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                App.instance.contentResolver.update(uri, v, null, null)
            }
        }

        fun discard() {
            runCatching { pfd.close() }
            if (file != null) file.delete() else runCatching { App.instance.contentResolver.delete(uri, null, null) }
        }

        companion object {
            fun create(context: Context, name: String): Target {
                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
                    ?: "application/octet-stream"
                if (Build.VERSION.SDK_INT >= 29) {
                    val v = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                        put(MediaStore.MediaColumns.MIME_TYPE, mime)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AsenaScale")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
                        ?: error("can't create $name")
                    val pfd = context.contentResolver.openFileDescriptor(uri, "rw") ?: error("can't open $name")
                    return Target(uri, "Download/AsenaScale", pfd, null)
                }
                val dir = File(context.getExternalFilesDir(null), "Download").apply { mkdirs() }
                var f = File(dir, name)
                val stem = name.substringBeforeLast('.', name)
                val ext = name.removePrefix(stem)
                var n = 1
                while (f.exists()) f = File(dir, "$stem (${n++})$ext")
                val pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE)
                val uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
                return Target(uri, f.absolutePath, pfd, f)
            }
        }
    }

    // --- Chunks --------------------------------------------------------------------

    /**
     * Moves [total] bytes in CHUNK pieces with WORKERS workers; [move] handles
     * one piece (worker index, offset, length) and returns the bytes it moved.
     * A failed piece is retried a few times (the link reconnects) before the
     * whole transfer fails.
     */
    private fun chunked(t: Transfer, total: Long, move: (Int, Long, Int) -> Int) {
        val chunks = ((total + CHUNK - 1) / CHUNK).toInt()
        if (chunks == 0) return
        val next = AtomicInteger()
        val failure = AtomicReference<Throwable>()
        val workers = (0 until minOf(WORKERS, chunks)).map { w ->
            pool.submit {
                while (failure.get() == null && !t.cancelled) {
                    val i = next.getAndIncrement()
                    if (i >= chunks) break
                    val offset = i.toLong() * CHUNK
                    val len = minOf(CHUNK.toLong(), total - offset).toInt()
                    var attempt = 0
                    while (true) {
                        try {
                            val n = move(w, offset, len)
                            if (n != len) error("short transfer ($n of $len bytes)")
                            t.progress(n)
                            break
                        } catch (e: Exception) {
                            if (t.cancelled) return@submit
                            if (++attempt >= 4) {
                                failure.compareAndSet(null, e)
                                return@submit
                            }
                            Log.w("transfer", "chunk $i retry $attempt", e)
                            Thread.sleep(1000L shl attempt)
                        }
                    }
                }
            }
        }
        workers.forEach { it.get() }
        if (t.cancelled) error("cancelled")
        failure.get()?.let { throw it }
    }

    // --- Links ---------------------------------------------------------------------

    private fun link(host: Host): Link = synchronized(links) {
        links.getOrPut(host.id) { Link(host) }.also { it.users++ }
    }

    /** Closes a host's link a little after its last transfer. */
    private fun release(hostId: String) {
        synchronized(links) { links[hostId]?.let { it.users-- } }
        main.postDelayed({
            val idle = synchronized(links) { links[hostId]?.takeIf { it.users <= 0 }?.also { links.remove(hostId) } }
            idle?.let { l -> Thread { l.close() }.start() }
        }, 60_000)
    }

    /** A tunnel to the PC app plus up to two SSH connections for transfers. */
    private class Link(val host: Host) {
        var users = 0
        private var localPort = 0
        private val sessions = arrayOfNulls<Session>(CONNECTIONS)

        fun exec(worker: Int, command: String, stdin: ByteArray? = null, timeoutMs: Long = 30_000) =
            execOn(session(worker % CONNECTIONS), command, stdin, timeoutMs)

        private fun session(slot: Int): Session = synchronized(sessions) {
            sessions[slot]?.takeIf { it.isConnected }?.let { return it }
            val app = App.instance
            if (localPort == 0) localPort = app.tailnet.forward(app.tailnet.resolve(host.address), HOST_APP_PORT)
            val jsch = JSch()
            jsch.addIdentity(Keys.privateKeyFile(app).absolutePath)
            val s = jsch.getSession(host.user.ifBlank { "pc" }, "127.0.0.1", localPort)
            s.setConfig("StrictHostKeyChecking", "no") // tailnet peers are WireGuard-authenticated already
            s.setConfig("PreferredAuthentications", "publickey")
            s.setServerAliveInterval(30_000)
            s.connect(20_000)
            sessions[slot] = s
            s
        }

        fun close() = synchronized(sessions) {
            sessions.forEach { runCatching { it?.disconnect() } }
            sessions.fill(null)
            if (localPort != 0) runCatching { App.instance.tailnet.closeForward(localPort) }
            localPort = 0
        }
    }

    private fun displayName(uri: Uri): String {
        val raw = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "file"
        val clean = raw.substringAfterLast('/').map { if (it in "\\/:*?\"<>|" || it.isISOControl()) '_' else it }
            .joinToString("").trim().trim('.')
        return clean.ifEmpty { "file" }
    }

    private fun str(id: Int) = context.getString(id)

    companion object {
        /** Bytes per piece: big enough to keep the pipe full, small enough to retry cheaply. */
        const val CHUNK = 2 * 1024 * 1024
        /** Pieces in flight at once, spread over [CONNECTIONS] SSH connections. */
        const val WORKERS = 4
        const val CONNECTIONS = 2
    }
}
