package dev.asenascale.ssh

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.asenascale.App
import dev.asenascale.KeepAliveService
import dev.asenascale.data.Host
import dev.asenascale.data.Tool
import dev.asenascale.data.sessionKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Open terminals, one per (host, tool), keyed by [SshConnection.key].
 * Lives as long as the process.
 *
 * With the AsenaScale PC app the terminal keeps running on the PC, so a
 * dropped link (network change, phone asleep) is quietly re-established
 * with backoff, reusing the same screen and scrollback.
 */
class Sessions(private val context: Context) {
    private val _open = MutableStateFlow<Map<String, SshConnection>>(emptyMap())
    val open: StateFlow<Map<String, SshConnection>> = _open
    private val main = Handler(Looper.getMainLooper())

    /** Returns the live connection for [tool] on [host], starting one if needed. */
    fun connect(host: Host, tool: Tool): SshConnection {
        _open.value[sessionKey(host.id, tool.id)]?.let { conn ->
            if (conn.state.value !is ConnState.Closed || conn.attempt > 0) return conn
        }
        return start(SshConnection(latest(host), tool))
    }

    fun get(key: String) = _open.value[key]

    /** Open terminals on one host. */
    fun forHost(hostId: String) = _open.value.values.filter { it.host.id == hostId }

    /** Drops the link and opens a fresh one to the same PC session. */
    fun reconnect(key: String) {
        val old = _open.value[key] ?: return
        old.userClosed = true
        old.close()
        start(SshConnection(latest(old.host), old.tool, previous = old))
    }

    /** Leaves; the terminal keeps running on the PC (AsenaScale). */
    fun disconnect(key: String) {
        _open.value[key]?.let {
            it.userClosed = true
            it.close()
        }
        _open.value = _open.value - key
        updateKeepAlive()
    }

    /** Leaves every terminal of a host (e.g. it was deleted). */
    fun disconnectHost(hostId: String) = forHost(hostId).forEach { disconnect(it.key) }

    /** Leaves and ends the terminal on the PC too; next time starts fresh. */
    fun end(key: String) {
        val conn = _open.value[key] ?: return
        val id = conn.sessionId
        Thread {
            if (conn.isHostApp) runCatching { conn.exec("mc kill $id", timeoutMs = 5_000) }
            main.post { disconnect(key) }
        }.start()
        App.instance.sessionIds.clear(key)
    }

    /** Network is back: retry waiting reconnects right away. */
    fun onNetworkAvailable() = main.post {
        for (conn in _open.value.values) {
            // A reconnect still sleeping through its backoff: go now.
            if (conn.attempt > 0 && !conn.started && !conn.userClosed) conn.connect()
        }
    }

    internal fun onClosed(conn: SshConnection) {
        if (_open.value[conn.key] !== conn) return // already replaced
        if (!conn.userClosed && conn.isHostApp && (conn.everConnected || conn.attempt > 0)) {
            retry(conn, now = false)
        }
        updateKeepAlive()
    }

    private fun retry(conn: SshConnection, now: Boolean) {
        val delays = longArrayOf(800, 1_500, 3_000, 6_000, 10_000, 20_000, 30_000)
        val delay = if (now) 0 else delays[conn.attempt.coerceAtMost(delays.size - 1)]
        val next = SshConnection(latest(conn.host), conn.tool, previous = conn, attempt = conn.attempt + 1)
        // Shown as "reconnecting" while it waits.
        _open.value = _open.value + (conn.key to next)
        main.postDelayed({
            if (_open.value[conn.key] === next && !next.userClosed && !next.started) next.connect()
        }, delay)
    }

    private fun start(conn: SshConnection): SshConnection {
        _open.value = _open.value + (conn.key to conn)
        conn.connect()
        updateKeepAlive()
        return conn
    }

    private fun latest(host: Host) = App.instance.hosts.get(host.id) ?: host

    private fun updateKeepAlive() {
        val live = _open.value.values.count { it.state.value !is ConnState.Closed || it.attempt > 0 }
        KeepAliveService.update(context, live)
    }
}
