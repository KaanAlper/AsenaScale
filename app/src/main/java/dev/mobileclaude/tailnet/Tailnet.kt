package dev.mobileclaude.tailnet

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.NetworkInterface
import java.util.Collections

data class Peer(
    val name: String,
    val dnsName: String,
    val os: String,
    val ips: List<String>,
    val online: Boolean,
) {
    /** Short MagicDNS name ("my-pc" out of "my-pc.tail1234.ts.net"). */
    val shortName: String get() = dnsName.substringBefore('.').ifEmpty { name }
    val ipv4: String? get() = ips.firstOrNull { '.' in it }
}

data class TailnetState(
    val enabled: Boolean = false,
    /** Tailscale backend state: Stopped, NoState, Starting, NeedsLogin, NeedsMachineAuth, Running. */
    val backend: String = "Stopped",
    val authUrl: String = "",
    val self: Peer? = null,
    val tailnet: String = "",
    val peers: List<Peer> = emptyList(),
    val error: String = "",
) {
    val running get() = backend == "Running"
    val needsLogin get() = backend == "NeedsLogin" || backend == "NeedsMachineAuth"
}

/**
 * Embedded Tailscale node (Go tsnet via gomobile). All tailnet traffic stays
 * inside the app process: no VpnService, and it works next to the official
 * Tailscale app.
 */
class Tailnet(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences("tailnet", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(TailnetState(enabled = prefs.getBoolean("enabled", true)))
    val state: StateFlow<TailnetState> = _state
    private var poller: Job? = null
    @Volatile private var fastPollUntil = 0L

    private val platform = object : tsbridge.Platform {
        override fun interfacesJSON(): String = interfacesJson()
        override fun log(line: String) {
            Log.i("tailscale", line)
        }
    }

    fun startIfEnabled() {
        watchNetwork()
        if (_state.value.enabled) setEnabled(true)
    }

    /**
     * Tells the embedded node about Wi-Fi <-> mobile data switches right away
     * (like the official app does), so connections recover in a second
     * instead of whenever Tailscale's slow Android poll notices.
     */
    private fun watchNetwork() {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                val gateway = lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is java.net.Inet4Address }
                    ?.gateway?.hostAddress.orEmpty()
                notifyNetwork(lp.interfaceName.orEmpty(), gateway)
            }

            override fun onLost(network: Network) = notifyNetwork("", "")
        })
    }

    private var lastIface: String? = null

    private fun notifyNetwork(iface: String, gateway: String) {
        if (iface == lastIface) return
        lastIface = iface
        scope.launch {
            runCatching { tsbridge.Tsbridge.networkChanged(iface, gateway) }
            fastPollUntil = System.currentTimeMillis() + 15_000
        }
    }

    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean("enabled", on).apply()
        _state.value = _state.value.copy(enabled = on, error = "")
        scope.launch {
            if (on) {
                try {
                    val host = "mobile-claude-" + Build.MODEL.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
                    tsbridge.Tsbridge.start(context.filesDir.absolutePath, host.take(60), platform)
                } catch (e: Exception) {
                    _state.value = _state.value.copy(error = e.message ?: e.toString())
                }
                startPolling()
            } else {
                poller?.cancel()
                tsbridge.Tsbridge.stop()
                _state.value = TailnetState(enabled = false)
            }
        }
    }

    /** Asks the backend for a fresh login URL; it appears in [state] shortly after. */
    fun login() = scope.launch {
        runCatching { tsbridge.Tsbridge.login() }.onFailure {
            _state.value = _state.value.copy(error = it.message ?: it.toString())
        }
        fastPollUntil = System.currentTimeMillis() + 60_000
        refresh()
    }

    fun logout() = scope.launch {
        runCatching { tsbridge.Tsbridge.logout() }
        refresh()
    }

    /** Polls quickly while things are changing, slowly once connected. */
    private fun startPolling() {
        poller?.cancel()
        fastPollUntil = System.currentTimeMillis() + 30_000
        poller = scope.launch {
            while (isActive) {
                refresh()
                val fast = !_state.value.running || System.currentTimeMillis() < fastPollUntil
                delay(if (fast) 1_000 else 4_000)
            }
        }
    }

    fun refresh() {
        if (!_state.value.enabled) return
        val json = runCatching { JSONObject(tsbridge.Tsbridge.status()) }.getOrNull() ?: return
        _state.value = _state.value.copy(
            backend = json.optString("state", "Stopped"),
            authUrl = json.optString("authURL"),
            tailnet = json.optString("tailnet"),
            self = json.optJSONObject("self")?.toPeer(),
            peers = json.optJSONArray("peers").toPeers(),
            error = json.optString("error"),
        )
    }

    /** Maps a saved address (short name, FQDN or IP) to the peer's Tailscale IP. */
    fun resolve(address: String): String {
        val a = address.trim().trimEnd('.').lowercase()
        val peer = _state.value.peers.firstOrNull {
            a == it.shortName.lowercase() || a == it.dnsName.lowercase() || a == it.name.lowercase() || a in it.ips
        }
        return peer?.ipv4 ?: peer?.ips?.firstOrNull() ?: address
    }

    /** Opens a 127.0.0.1 port that tunnels to [host]:[port] over the tailnet. */
    fun forward(host: String, port: Int): Int {
        val h = if (':' in host) "[$host]" else host
        return tsbridge.Tsbridge.forward("$h:$port").toInt()
    }

    fun closeForward(localPort: Int) = tsbridge.Tsbridge.closeForward(localPort.toLong())

    private fun JSONObject.toPeer() = Peer(
        name = optString("name"),
        dnsName = optString("dnsName"),
        os = optString("os"),
        ips = optJSONArray("ips")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList(),
        online = optBoolean("online"),
    )

    private fun JSONArray?.toPeers(): List<Peer> =
        if (this == null) emptyList() else List(length()) { getJSONObject(it).toPeer() }

    private fun interfacesJson(): String {
        val out = JSONArray()
        val ifaces = runCatching { Collections.list(NetworkInterface.getNetworkInterfaces()) }.getOrNull()
            ?: return "[]"
        for (nif in ifaces) {
            try {
                val addrs = JSONArray()
                for (ia in nif.interfaceAddresses) {
                    val host = ia.address?.hostAddress ?: continue
                    addrs.put(JSONObject().put("ip", host).put("prefixLen", ia.networkPrefixLength.toInt()))
                }
                out.put(
                    JSONObject()
                        .put("name", nif.name)
                        .put("index", nif.index)
                        .put("mtu", nif.mtu)
                        .put("up", nif.isUp)
                        .put("broadcast", nif.supportsMulticast())
                        .put("loopback", nif.isLoopback)
                        .put("pointToPoint", nif.isPointToPoint)
                        .put("multicast", nif.supportsMulticast())
                        .put("addrs", addrs)
                )
            } catch (_: Exception) {
            }
        }
        return out.toString()
    }
}
