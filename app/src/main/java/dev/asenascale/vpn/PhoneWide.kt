package dev.asenascale.vpn

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.asenascale.tailnet.Tailnet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Phone-wide mode: when on, an Android VpnService routes only tailnet ranges
 * into the embedded node, so every app on the phone can reach tailnet IPs.
 * When off (the default) nothing changes: tailnet traffic stays inside this
 * app and no VPN permission is involved.
 *
 * The VPN runs only while the switch is on and Tailscale is connected; it
 * stops on its own when Tailscale is switched off or signed out, and comes
 * back when Tailscale does (if Android still grants the permission).
 */
class PhoneWide(private val context: Context, private val tailnet: Tailnet) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())
    private val prefs = PhoneWidePrefs(object : FlagStore {
        private val sp = context.getSharedPreferences("tailnet", Context.MODE_PRIVATE)
        override fun getBoolean(key: String, default: Boolean) = sp.getBoolean(key, default)
        override fun putBoolean(key: String, value: Boolean) {
            sp.edit().putBoolean(key, value).apply()
        }
    })
    private val _ui = MutableStateFlow(PhoneWideUi(enabled = prefs.enabled))
    val ui: StateFlow<PhoneWideUi> = _ui
    private val enabledFlow = MutableStateFlow(prefs.enabled)
    @Volatile private var target: VpnAddresses? = null

    fun start() {
        scope.launch {
            combine(enabledFlow, tailnet.state) { on, ts -> phoneWideTarget(on, ts) }
                .distinctUntilChanged()
                .collect { apply(it) }
        }
    }

    /** The user flipped the switch (after the VPN permission, when turning on). */
    fun setEnabled(on: Boolean) {
        prefs.enabled = on
        _ui.value = _ui.value.copy(enabled = on, note = PhoneWideNote.None)
        enabledFlow.value = on
    }

    fun note(n: PhoneWideNote) {
        _ui.value = _ui.value.copy(note = n)
    }

    /** Called when the app comes to the front: a refused start or a dead tunnel is retried. */
    fun retry() {
        if (target == null) return
        // Not running (start refused in the background), or running with a
        // tunnel that died: the service re-establishes it.
        if (PhoneWideVpnService.running == null || !tsbridge.Tsbridge.vpnActive()) startService()
    }

    private fun apply(t: VpnAddresses?) {
        target = t
        if (t == null) {
            PhoneWideVpnService.running?.shutdown()
            return
        }
        if (VpnService.prepare(context) != null) {
            // Permission gone (another VPN app was approved since): don't
            // leave a switch on that can't work.
            Log.i(TAG, "VPN permission missing; phone-wide off")
            setEnabled(false)
            note(PhoneWideNote.Revoked)
            return
        }
        startService()
    }

    private fun startService() {
        // The service reads the addresses from currentTarget() when it starts.
        val i = Intent(context, PhoneWideVpnService::class.java)
        // Refused while the app is in the background on newer Android; then
        // it is retried when the app comes back (retry()).
        runCatching { context.startForegroundService(i) }
            .onFailure { Log.w(TAG, "start: $it") }
    }

    /** What the service reports back (main thread). */
    internal fun onServiceState(active: Boolean, note: PhoneWideNote? = null) = main.post {
        _ui.value = _ui.value.copy(active = active, note = note ?: _ui.value.note)
    }

    /** Another VPN app took over: Android revoked ours. */
    internal fun onRevoked() = main.post {
        setEnabled(false)
        note(PhoneWideNote.Revoked)
    }

    /** Addresses the running service should use now (null: stop). */
    internal fun currentTarget(): VpnAddresses? = target

    companion object {
        private const val TAG = "phonewide"

        /**
         * True when some other app's VPN is up. Android allows one VPN at a
         * time; approving ours disconnects it, so the user is told first.
         */
        fun otherVpnActive(context: Context): Boolean {
            if (PhoneWideVpnService.running != null) return false
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
            @Suppress("DEPRECATION")
            return runCatching {
                cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
            }.getOrDefault(false)
        }
    }
}
