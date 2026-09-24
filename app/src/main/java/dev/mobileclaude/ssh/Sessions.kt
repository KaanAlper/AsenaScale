package dev.mobileclaude.ssh

import android.content.Context
import dev.mobileclaude.KeepAliveService
import dev.mobileclaude.data.Host
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Open terminal sessions, one per saved host. Lives as long as the process. */
class Sessions(private val context: Context) {
    private val _open = MutableStateFlow<Map<String, SshConnection>>(emptyMap())
    val open: StateFlow<Map<String, SshConnection>> = _open

    /** Returns the live session for [host], starting one if needed. */
    fun connect(host: Host): SshConnection {
        _open.value[host.id]?.let { conn ->
            if (conn.state.value !is ConnState.Closed) return conn
        }
        val conn = SshConnection(host)
        _open.value = _open.value + (host.id to conn)
        conn.connect()
        KeepAliveService.update(context, _open.value.size)
        return conn
    }

    fun get(hostId: String) = _open.value[hostId]

    fun disconnect(hostId: String) {
        _open.value[hostId]?.close()
        _open.value = _open.value - hostId
        KeepAliveService.update(context, _open.value.size)
    }

    internal fun onClosed(conn: SshConnection) {
        // Keep closed sessions in the map so the screen can show why; they are
        // replaced on reconnect. Only the keep-alive count changes.
        val live = _open.value.values.count { it.state.value !is ConnState.Closed }
        KeepAliveService.update(context, live)
    }
}
