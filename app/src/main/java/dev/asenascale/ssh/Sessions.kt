package dev.asenascale.ssh

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.asenascale.App
import dev.asenascale.KeepAliveService
import dev.asenascale.data.Host
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Open terminals, one per saved host. Lives as long as the process.
 *
 * With the AsenaScale PC app the terminal keeps running on the PC, so a
 * dropped link (network change, phone asleep) is quietly re-established
 * with backoff, reusing the same screen and scrollback.
 */
class Sessions(private val context: Context) {
    private val _open = MutableStateFlow<Map<String, SshConnection>>(emptyMap())
    val open: StateFlow<Map<String, SshConnection>> = _open
    private val main = Handler(Looper.getMainLooper())

    /** Returns the live connection for [host], starting one if needed. */
    fun connect(host: Host): SshConnection {
        _open.value[host.id]?.let { conn ->
            if (conn.state.value !is ConnState.Closed || conn.attempt > 0) return conn
        }
        return start(SshConnection(latest(host)))
    }

    fun get(hostId: String) = _open.value[hostId]

    /** Drops the link and opens a fresh one to the same PC session. */
    fun reconnect(hostId: String) {
        val old = _open.value[hostId] ?: return
        old.userClosed = true
        old.close()
        start(SshConnection(latest(old.host), previous = old))
    }

    /** Leaves; the terminal keeps running on the PC (AsenaScale). */
    fun disconnect(hostId: String) {
        _open.value[hostId]?.let {
            it.userClosed = true
            it.close()
        }
        _open.value = _open.value - hostId
        updateKeepAlive()
    }

    /** Leaves and ends the terminal on the PC too; next time starts fresh. */
    fun end(hostId: String) {
        val conn = _open.value[hostId] ?: return
        val id = conn.sessionId
        Thread {
            if (conn.isHostApp) runCatching { conn.exec("mc kill $id", timeoutMs = 5_000) }
            main.post { disconnect(hostId) }
        }.start()
        App.instance.hosts.get(hostId)?.let { App.instance.hosts.save(it.copy(session = "")) }
    }

    /** Network is back: retry waiting reconnects right away. */
    fun onNetworkAvailable() = main.post {
        for (conn in _open.value.values) {
            if (conn.state.value is ConnState.Closed && conn.isHostApp && !conn.userClosed) retry(conn, now = true)
        }
    }

    internal fun onClosed(conn: SshConnection) {
        if (_open.value[conn.host.id] !== conn) return // already replaced
        if (!conn.userClosed && conn.isHostApp && (conn.everConnected || conn.attempt > 0)) {
            retry(conn, now = false)
        }
        updateKeepAlive()
    }

    private fun retry(conn: SshConnection, now: Boolean) {
        val delays = longArrayOf(800, 1_500, 3_000, 6_000, 10_000, 20_000, 30_000)
        val delay = if (now) 0 else delays[conn.attempt.coerceAtMost(delays.size - 1)]
        val next = SshConnection(latest(conn.host), previous = conn, attempt = conn.attempt + 1)
        // Shown as "reconnecting" while it waits.
        _open.value = _open.value + (conn.host.id to next)
        main.postDelayed({
            if (_open.value[conn.host.id] === next && !next.userClosed) next.connect()
        }, delay)
    }

    private fun start(conn: SshConnection): SshConnection {
        _open.value = _open.value + (conn.host.id to conn)
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
