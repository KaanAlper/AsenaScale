package dev.asenascale.vpn

import dev.asenascale.tailnet.TailnetState

/** Storage the toggle needs; SharedPreferences in the app, a map in tests. */
interface FlagStore {
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
}

/** The "Phone-wide" switch. Off unless the user turned it on. */
class PhoneWidePrefs(private val store: FlagStore) {
    var enabled: Boolean
        get() = store.getBoolean(KEY, false)
        set(value) = store.putBoolean(KEY, value)

    companion object {
        const val KEY = "phone_wide"
    }
}

/** Tailnet ranges routed into the VPN; nothing else goes through it. */
val TAILNET_ROUTES = listOf("100.64.0.0" to 10, "fd7a:115c:a1e0::" to 48)

const val VPN_MTU = 1280

/**
 * The phone's own tailnet addresses for the TUN interface, or null while
 * phone-wide mode can't (or shouldn't) run: switch off, Tailscale off,
 * signed out, or no address yet. Then the VPN is stopped.
 */
data class VpnAddresses(val ipv4: String?, val ipv6: String?)

fun phoneWideTarget(enabled: Boolean, ts: TailnetState): VpnAddresses? {
    if (!enabled || !ts.enabled || !ts.running) return null
    val self = ts.self ?: return null
    val v4 = self.ipv4
    val v6 = self.ipv6
    if (v4 == null && v6 == null) return null
    return VpnAddresses(v4, v6)
}

/** What the card shows under the switch. */
sealed interface PhoneWideNote {
    data object None : PhoneWideNote
    data object Denied : PhoneWideNote
    data object Revoked : PhoneWideNote
    data class Failed(val message: String) : PhoneWideNote
}

data class PhoneWideUi(
    val enabled: Boolean = false,
    /** The TUN device is up and served. */
    val active: Boolean = false,
    val note: PhoneWideNote = PhoneWideNote.None,
)
