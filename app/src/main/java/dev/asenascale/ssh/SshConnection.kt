package dev.asenascale.ssh

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSessionClient
import dev.asenascale.App
import dev.asenascale.data.AuthMode
import dev.asenascale.data.Host
import dev.asenascale.tailnet.HOST_APP_PORT
import dev.asenascale.term.TermTheme
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

    /** Connected to the AsenaScale PC app (not a plain SSH server). */
    @Volatile var isHostApp = false
        private set

    private val _hint = MutableStateFlow<String?>(null)
    /** Something the user should do on the PC while connecting, if any. */
    val hint: StateFlow<String?> = _hint

    /** "posix", "win-ps" or "win-cmd", detected on first use by the screenshot helper. */
    @Volatile var remoteOs: String? = null

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
        if (TermTheme.dark == null) TermTheme.set(true)
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
            // Right after app start the embedded node needs a moment to come up.
            val deadline = System.currentTimeMillis() + 20_000
            while (!tn.state.value.running && System.currentTimeMillis() < deadline) {
                if (!tn.state.value.enabled || tn.state.value.needsLogin) break
                Thread.sleep(250)
            }
            if (!tn.state.value.running) error("Tailscale bağlı değil. Ana ekrandan aç ve giriş yap.")
            // Prefer the peer's Tailscale IP; fall back to MagicDNS resolution.
            val target = tn.resolve(host.address)
            // The PC app, when running, beats a plain SSH server: no setup,
            // direct screenshots, file drop. Only on the default ports so a
            // deliberately chosen port is left alone.
            var port = host.port
            if ((port == 22 || port == HOST_APP_PORT) && tn.isHostApp(target)) {
                port = HOST_APP_PORT
                isHostApp = true
                if (host.port != port) app.hosts.save(host.copy(port = port))
            }
            // Knock first: tells "no SSH server" apart from "firewall / asleep".
            tn.probe(target, port)?.let { error(probeMessage(it, target, port)) }
            localPort = tn.forward(target, port)

            val jsch = JSch()
            val known = File(app.filesDir, "known_hosts").apply { if (!exists()) createNewFile() }
            jsch.setKnownHosts(known.absolutePath)
            jsch.addIdentity(Keys.privateKeyFile(app).absolutePath)

            val s = jsch.getSession(host.user.ifBlank { "pc" }, "127.0.0.1", localPort)
            s.setHostKeyAlias(host.address)
            s.setConfig("StrictHostKeyChecking", "no") // tailnet peers are WireGuard-authenticated already
            s.setConfig(
                "PreferredAuthentications",
                when {
                    isHostApp -> "publickey" // the PC app approves this phone's key
                    host.auth == AuthMode.TAILSCALE -> "publickey,keyboard-interactive,password"
                    host.auth == AuthMode.KEY -> "publickey"
                    else -> "keyboard-interactive,password"
                },
            )
            if (host.auth == AuthMode.PASSWORD) s.setPassword(host.password.toByteArray())
            s.userInfo = PasswordInfo(host.password)
            s.setServerAliveInterval(15_000)
            s.setServerAliveCountMax(4)
            s.timeout = 0
            // The first time, the PC app waits for someone to click "Yes" on the PC.
            if (isHostApp) _hint.value = "PC'de çıkan izin penceresinde \"Evet\"e bas (ilk bağlantıda bir kez)."
            s.connect(if (isHostApp) 120_000 else 20_000)
            _hint.value = null
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
        // Reads block, so a watchdog closes the channel if the command hangs.
        var timedOut = false
        val watchdog = Thread {
            try {
                Thread.sleep(timeoutMs)
                timedOut = true
                ch.disconnect()
            } catch (_: InterruptedException) {
            }
        }.apply { isDaemon = true; start() }
        try {
            if (stdin != null) stdinStream.write(stdin)
            stdinStream.close()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
            while (!ch.isClosed && !timedOut) Thread.sleep(10)
        } catch (e: java.io.IOException) {
            if (!timedOut) throw e
        } finally {
            watchdog.interrupt()
            ch.disconnect()
        }
        if (timedOut) error("Zaman aşımı: bilgisayar yanıt vermedi")
        return ExecResult(ch.exitStatus, out.toByteArray(), err.toString(Charsets.UTF_8.name()))
    }

    /**
     * Copies a file to the PC's Downloads/AsenaScale folder over SFTP (for
     * plain SSH servers; the PC app has its own `mc put`). Returns the PC path.
     */
    fun sftpPut(name: String, bytes: ByteArray): String {
        val s = session ?: error("Bağlı değil")
        val ch = s.openChannel("sftp") as ChannelSftp
        ch.connect(10_000)
        try {
            val home = ch.home.trimEnd('/')
            runCatching { ch.mkdir("$home/Downloads") }
            val dir = "$home/Downloads/AsenaScale"
            runCatching { ch.mkdir(dir) }
            val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
            var remote = "$dir/$name"
            var n = 1
            while (runCatching { ch.stat(remote) }.isSuccess) {
                remote = "$dir/${name.substring(0, dot)} ($n)${name.substring(dot)}"
                n++
            }
            ch.put(bytes.inputStream(), remote)
            // Windows OpenSSH reports paths like /C:/Users/...
            return if (Regex("^/[A-Za-z]:/").containsMatchIn(remote)) remote.drop(1).replace('/', '\\') else remote
        } finally {
            ch.disconnect()
        }
    }

    fun close(reason: String? = null) {
        if (_state.value is ConnState.Closed) return
        _hint.value = null
        _state.value = ConnState.Closed(reason)
        runCatching { shell?.disconnect() }
        runCatching { session?.disconnect() }
        if (localPort != 0) runCatching { app.tailnet.closeForward(localPort) }
        localPort = 0
        shellOut = null
        main.post { app.sessions.onClosed(this) }
    }

    private fun probeMessage(err: String, target: String, port: Int): String {
        val e = err.lowercase()
        val windows = app.tailnet.peerFor(host.address)?.isWindows == true
        if ("timeout" in e || "deadline" in e) {
            // Windows' firewall silently drops connections to closed ports, so
            // "no answer" can also mean no SSH server. A Tailscale-level ping
            // (answered by tailscaled, not the OS) tells tunnel from PC.
            val ms = app.tailnet.ping(target)
            return when {
                ms < 0 -> "Tailscale üzerinden PC'ye ulaşılamıyor. PC'de Tailscale açık ve bağlı mı? PC uykuda olabilir."
                windows -> "Tailscale tüneli PC'ye ulaşıyor ($ms ms) ama $port. port yanıt vermiyor: Windows'ta " +
                    "SSH sunucusu kurulu değil ya da Güvenlik Duvarı engelliyor. Kurulum komutunu PC'de " +
                    "Yönetici PowerShell'de çalıştır."
                else -> "Tailscale tüneli PC'ye ulaşıyor ($ms ms) ama $port. port yanıt vermiyor: " +
                    "SSH sunucusu kapalı ya da güvenlik duvarı engelliyor."
            }
        }
        return when {
            "refused" in e -> if (windows) {
                "PC'ye ulaşıldı ama $port. portta SSH sunucusu yok. Windows kurulum komutunu " +
                    "(ana ekran > anahtar simgesi > Windows kurulum komutu) Yönetici PowerShell'de çalıştırdın mı?"
            } else {
                "PC'ye ulaşıldı ama $port. portta SSH sunucusu yok: sudo systemctl enable --now sshd"
            }
            "timeout" in e || "deadline" in e -> if (windows) {
                "PC'den yanıt yok. Windows Güvenlik Duvarı SSH'ı engelliyor olabilir: kurulum komutunu tekrar " +
                    "çalıştır (kuralı tüm ağlar için açar). PC uykuda da olabilir."
            } else {
                "PC'den yanıt yok: güvenlik duvarı 22. portu engelliyor olabilir ya da PC uykuda."
            }
            "unknown" in e || "no such host" in e -> "\"${host.address}\" adında bir cihaz bulunamadı. Adresi kontrol et."
            else -> "PC'ye bağlanılamadı: $err"
        }
    }

    private fun friendly(e: Exception): String {
        val m = e.message ?: e.toString()
        return when {
            ("Auth fail" in m || "Auth cancel" in m) && isHostApp ->
                "PC'de izin verilmedi. Tekrar bağlan ve PC'de çıkan pencerede \"Evet\"e bas."
            "Auth fail" in m || "Auth cancel" in m -> "Kimlik doğrulama başarısız. Kullanıcı adını ve anahtarı/şifreyi kontrol et."
            "timeout" in m.lowercase() -> "Zaman aşımı: bilgisayar açık ve Tailscale'e bağlı mı?"
            "refused" in m.lowercase() || "dial" in m.lowercase() ->
                "Bağlantı reddedildi: PC'de SSH sunucusu çalışıyor mu? (Windows'ta: PC'ye AsenaScale kur)"
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
