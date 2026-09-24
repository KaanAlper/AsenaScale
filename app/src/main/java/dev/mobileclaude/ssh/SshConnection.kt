package dev.mobileclaude.ssh

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSessionClient
import dev.mobileclaude.App
import dev.mobileclaude.data.AuthMode
import dev.mobileclaude.data.Host
import dev.mobileclaude.term.TermTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors

sealed interface ConnState {
    data object Connecting : ConnState
    data object Connected : ConnState
    data class Closed(val reason: String?) : ConnState
}

/**
 * One SSH shell to a tailnet machine, feeding a terminal emulator.
 *
 * Threading: the emulator is only touched on the main thread. Network reads
 * happen on a reader thread and are batched into one main-thread post per
 * burst; writes go through a single-thread executor so the UI never blocks.
 */
class SshConnection(val host: Host) {
    private val app = App.instance
    private val main = Handler(Looper.getMainLooper())
    private val writer = Executors.newSingleThreadExecutor()

    private val _state = MutableStateFlow<ConnState>(ConnState.Connecting)
    val state: StateFlow<ConnState> = _state
    private val _title = MutableStateFlow(host.title)
    val title: StateFlow<String> = _title

    private var session: Session? = null
    private var shell: ChannelShell? = null
    private var shellOut: OutputStream? = null
    private var localPort = 0

    /** Called on the main thread whenever the screen changed. */
    var onScreenUpdate: (() -> Unit)? = null
    var onBell: (() -> Unit)? = null

    private val pending = ByteArrayOutputStream()
    private var drainPosted = false
    private val drain = Runnable {
        val bytes: ByteArray
        synchronized(pending) {
            bytes = pending.toByteArray()
            pending.reset()
            drainPosted = false
        }
        emulator.append(bytes, bytes.size)
        onScreenUpdate?.invoke()
    }

    private val output = object : TerminalOutput() {
        override fun write(data: ByteArray, offset: Int, count: Int) = send(data.copyOfRange(offset, offset + count))
        override fun titleChanged(oldTitle: String?, newTitle: String?) {
            _title.value = newTitle?.takeIf { it.isNotBlank() } ?: host.title
        }
        override fun onCopyTextToClipboard(text: String?) {
            if (text != null) app.copyToClipboard(text)
        }
        override fun onPasteTextFromClipboard() {
            app.clipboardText()?.let { emulator.paste(it) }
        }
        override fun onBell() {
            onBell?.invoke()
        }
        override fun onColorsChanged() {
            onScreenUpdate?.invoke()
        }
    }

    private val client = object : TerminalSessionClient {
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun getTerminalCursorStyle(): Int = TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR
        override fun logError(tag: String?, message: String?) {}
        override fun logWarn(tag: String?, message: String?) {}
        override fun logInfo(tag: String?, message: String?) {}
        override fun logDebug(tag: String?, message: String?) {}
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
        override fun logStackTrace(tag: String?, e: Exception?) {}
    }

    val emulator: TerminalEmulator = run {
        TermTheme.apply()
        TerminalEmulator(output, 80, 24, 5000, client)
    }

    private var cols = 80
    private var rows = 24

    fun connect() {
        _state.value = ConnState.Connecting
        Thread({ runConnect() }, "ssh-${host.title}").start()
    }

    private fun runConnect() {
        try {
            val tn = app.tailnet
            if (!tn.state.value.running) error("Tailscale bağlı değil")
            localPort = tn.forward(host.address, host.port)

            val jsch = JSch()
            val known = File(app.filesDir, "known_hosts").apply { if (!exists()) createNewFile() }
            jsch.setKnownHosts(known.absolutePath)
            jsch.addIdentity(Keys.privateKeyFile(app).absolutePath)

            val s = jsch.getSession(host.user, "127.0.0.1", localPort)
            s.setHostKeyAlias(host.address)
            s.setConfig("StrictHostKeyChecking", "no") // tailnet peers are WireGuard-authenticated already
            s.setConfig(
                "PreferredAuthentications",
                when (host.auth) {
                    AuthMode.TAILSCALE -> "publickey,keyboard-interactive,password"
                    AuthMode.KEY -> "publickey"
                    AuthMode.PASSWORD -> "keyboard-interactive,password"
                },
            )
            if (host.auth == AuthMode.PASSWORD) s.setPassword(host.password)
            s.userInfo = PasswordInfo(host.password)
            s.setServerAliveInterval(15_000)
            s.setServerAliveCountMax(4)
            s.timeout = 0
            s.connect(20_000)
            session = s

            val ch = s.openChannel("shell") as ChannelShell
            ch.setPtyType("xterm-256color", cols, rows, 0, 0)
            ch.setEnv("COLORTERM", "truecolor")
            val input = ch.inputStream
            shellOut = ch.outputStream
            ch.connect(15_000)
            shell = ch
            _state.value = ConnState.Connected

            if (host.startup.isNotBlank()) send((host.startup.trim() + "\r").toByteArray())
            readLoop(input)
            close("Bağlantı kapandı")
        } catch (e: Exception) {
            Log.w("ssh", "connect failed", e)
            close(friendly(e))
        }
    }

    private fun readLoop(input: InputStream) {
        val buf = ByteArray(32 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            synchronized(pending) {
                pending.write(buf, 0, n)
                if (!drainPosted) {
                    drainPosted = true
                    main.post(drain)
                }
            }
        }
    }

    fun send(bytes: ByteArray) {
        val out = shellOut ?: return
        writer.execute {
            try {
                out.write(bytes)
                out.flush()
            } catch (_: Exception) {
            }
        }
    }

    fun sendText(text: String) = send(text.toByteArray(Charsets.UTF_8))

    /** Called by the view when its size in cells changes. */
    fun resize(columns: Int, rows: Int, widthPx: Int, heightPx: Int) {
        if (columns == cols && rows == this.rows) return
        cols = columns
        this.rows = rows
        emulator.resize(columns, rows)
        val ch = shell ?: return
        writer.execute { runCatching { ch.setPtySize(columns, rows, widthPx, heightPx) } }
    }

    /** Runs [command] on the machine, optionally piping [stdin]; returns stdout. */
    fun exec(command: String, stdin: ByteArray? = null, timeoutMs: Long = 30_000): ExecResult {
        val s = session ?: error("Bağlı değil")
        val ch = s.openChannel("exec") as ChannelExec
        ch.setCommand(command)
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        ch.setErrStream(err)
        val input = ch.inputStream
        val stdinStream = ch.outputStream
        ch.connect(10_000)
        if (stdin != null) stdinStream.write(stdin)
        stdinStream.close()
        val deadline = System.currentTimeMillis() + timeoutMs
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (System.currentTimeMillis() > deadline) break
        }
        while (!ch.isClosed && System.currentTimeMillis() < deadline) Thread.sleep(20)
        val code = ch.exitStatus
        ch.disconnect()
        return ExecResult(code, out.toByteArray(), err.toString(Charsets.UTF_8.name()))
    }

    fun close(reason: String? = null) {
        if (_state.value is ConnState.Closed) return
        _state.value = ConnState.Closed(reason)
        runCatching { shell?.disconnect() }
        runCatching { session?.disconnect() }
        if (localPort != 0) runCatching { app.tailnet.closeForward(localPort) }
        localPort = 0
        shellOut = null
        main.post { app.sessions.onClosed(this) }
    }

    private fun friendly(e: Exception): String {
        val m = e.message ?: e.toString()
        return when {
            "Auth fail" in m || "Auth cancel" in m -> "Kimlik doğrulama başarısız. Kullanıcı adını ve anahtarı/şifreyi kontrol et."
            "timeout" in m.lowercase() -> "Zaman aşımı: bilgisayar açık ve Tailscale'e bağlı mı?"
            "refused" in m.lowercase() -> "Bağlantı reddedildi: bilgisayarda SSH sunucusu çalışıyor mu?"
            else -> m
        }
    }

    private class PasswordInfo(private val pw: String) : UserInfo, UIKeyboardInteractive {
        override fun getPassphrase(): String? = null
        override fun getPassword(): String = pw
        override fun promptPassword(message: String?) = pw.isNotEmpty()
        override fun promptPassphrase(message: String?) = false
        override fun promptYesNo(message: String?) = true
        override fun showMessage(message: String?) {}
        override fun promptKeyboardInteractive(
            destination: String?, name: String?, instruction: String?, prompt: Array<out String>?, echo: BooleanArray?,
        ): Array<String>? = if (pw.isEmpty()) null else Array(prompt?.size ?: 0) { pw }
    }
}

class ExecResult(val exitCode: Int, val stdout: ByteArray, val stderr: String)
