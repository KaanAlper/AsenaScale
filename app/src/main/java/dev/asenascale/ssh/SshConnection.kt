package dev.asenascale.ssh

import android.os.Handler
import dev.asenascale.R
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
import dev.asenascale.App
import dev.asenascale.data.AuthMode
import dev.asenascale.data.Host
import dev.asenascale.data.Tool
import dev.asenascale.data.sessionKey
import dev.asenascale.term.Term
import dev.asenascale.tailnet.HOST_APP_PORT
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
class SshConnection(
    val host: Host,
    val tool: Tool,
    previous: SshConnection? = null,
    val attempt: Int = 0,
) {
    /** Identifies this terminal among the open ones: host + tool. */
    val key: String = sessionKey(host.id, tool.id)

    private val app = App.instance
    private val main = Handler(Looper.getMainLooper())
    private val writer = Executors.newSingleThreadExecutor()

    private val _state = MutableStateFlow<ConnState>(ConnState.Connecting)
    val state: StateFlow<ConnState> = _state

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

    /** Screen and scrollback; handed over to the next connection on reconnect. */
    val term: Term = previous?.term ?: Term(host.title)
    val emulator: TerminalEmulator get() = term.emulator
    val title: StateFlow<String> get() = term.title

    /** Id of the terminal session on the PC app (kept across reconnects and app restarts). */
    val sessionId: String = App.instance.sessionIds.getOrCreate(key)

    /** Why the last attempt failed, for the UI to offer the right fix. */
    enum class Failure { AUTH, NEEDS_SETUP }
    @Volatile var failure: Failure? = null
        private set

    /** Set when the user leaves on purpose; no automatic reconnect then. */
    @Volatile var userClosed = false
    /** Reached the shell at least once (so a drop is worth reconnecting). */
    @Volatile var everConnected = false
        private set

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
        term.onScreenUpdate?.invoke()
    }

    private var cols = 80
    private var rows = 24

    init {
        term.sink = { send(it) }
        isHostApp = previous?.isHostApp ?: false
    }

    /** connect() was called; it runs once. */
    @Volatile var started = false
        private set

    fun connect() {
        if (started) return
        started = true
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
            if (!tn.state.value.running) error(str(R.string.err_tailscale_off))
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
            // Compressed traffic: terminal text shrinks several times over.
            s.setConfig("compression.s2c", "zlib@openssh.com,zlib,none")
            s.setConfig("compression.c2s", "zlib@openssh.com,zlib,none")
            s.setConfig("compression_level", "6")
            // Few keepalives: drops are cheap now (the PC keeps the session).
            s.setServerAliveInterval(30_000)
            s.setServerAliveCountMax(3)
            s.timeout = 0
            // The first time, the PC app waits for someone to click "Yes" on the PC.
            if (isHostApp) _hint.value = str(R.string.hint_approve)
            s.connect(if (isHostApp) 120_000 else 20_000)
            _hint.value = null
            session = s

            val ch = s.openChannel("shell") as ChannelShell
            ch.setPtyType("xterm-256color", cols, rows, 0, 0)
            ch.setEnv("COLORTERM", "truecolor")
            if (isHostApp) {
                // The PC app keeps the session alive between connections.
                ch.setEnv("AS_SESSION", sessionId)
                ch.setEnv("AS_CMD", tool.command)
            }
            val input = ch.inputStream
            shellOut = ch.outputStream
            ch.connect(15_000)
            shell = ch
            _state.value = ConnState.Connected
            everConnected = true

            // The PC app starts the command itself (and only once per session).
            if (!isHostApp && tool.command.isNotBlank()) send((tool.command + "\r").toByteArray())
            readLoop(input)
            close(str(R.string.conn_closed))
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
        val s = session ?: error(str(R.string.not_connected))
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
        if (timedOut) error(str(R.string.err_exec_timeout))
        return ExecResult(ch.exitStatus, out.toByteArray(), err.toString(Charsets.UTF_8.name()))
    }

    /**
     * Copies a file to the PC's Downloads/AsenaScale folder over SFTP (for
     * plain SSH servers; the PC app has its own `mc put`). Returns the PC path.
     */
    fun sftpPut(name: String, bytes: ByteArray): String {
        val s = session ?: error(str(R.string.not_connected))
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
                ms < 0 -> str(R.string.err_unreachable)
                windows -> str(R.string.err_port_silent_windows, ms, port).also { failure = Failure.NEEDS_SETUP }
                else -> str(R.string.err_port_silent, ms, port)
            }
        }
        return when {
            "refused" in e -> if (windows) {
                failure = Failure.NEEDS_SETUP
                str(R.string.err_refused_windows, port)
            } else str(R.string.err_refused, port)
            "unknown" in e || "no such host" in e -> str(R.string.err_unknown_host, host.address)
            else -> str(R.string.err_generic, err)
        }
    }

    private fun friendly(e: Exception): String {
        val m = e.message ?: e.toString()
        val auth = "Auth fail" in m || "Auth cancel" in m
        return when {
            auth && isHostApp -> str(R.string.err_denied)
            auth -> str(R.string.err_auth).also { failure = Failure.AUTH }
            "timeout" in m.lowercase() -> str(R.string.err_timeout)
            "refused" in m.lowercase() || "dial" in m.lowercase() -> str(R.string.err_refused_generic)
            else -> m
        }
    }

    private fun str(id: Int, vararg args: Any): String = app.getString(id, *args)


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
