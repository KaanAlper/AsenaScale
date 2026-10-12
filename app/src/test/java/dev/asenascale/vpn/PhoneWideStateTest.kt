package dev.asenascale.vpn

import dev.asenascale.tailnet.Peer
import dev.asenascale.tailnet.TailnetState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneWideStateTest {
    private class MapStore : FlagStore {
        val map = HashMap<String, Boolean>()
        override fun getBoolean(key: String, default: Boolean) = map[key] ?: default
        override fun putBoolean(key: String, value: Boolean) {
            map[key] = value
        }
    }

    private val self = Peer(
        name = "phone", dnsName = "phone.tail.ts.net", os = "android",
        ips = listOf("100.101.102.103", "fd7a:115c:a1e0::1234"), online = true,
    )
    private val connected = TailnetState(enabled = true, backend = "Running", self = self)

    @Test
    fun toggleIsOffByDefault() {
        assertFalse(PhoneWidePrefs(MapStore()).enabled)
    }

    @Test
    fun toggleIsPersisted() {
        val store = MapStore()
        PhoneWidePrefs(store).enabled = true
        assertTrue("survives a restart", PhoneWidePrefs(store).enabled)
        PhoneWidePrefs(store).enabled = false
        assertFalse(PhoneWidePrefs(store).enabled)
        assertEquals(false, store.map[PhoneWidePrefs.KEY])
    }

    @Test
    fun offMeansNoVpn() {
        assertNull(phoneWideTarget(false, connected))
    }

    @Test
    fun runsWithTheNodesOwnAddresses() {
        assertEquals(VpnAddresses("100.101.102.103", "fd7a:115c:a1e0::1234"), phoneWideTarget(true, connected))
    }

    @Test
    fun stopsWhenTailscaleIsOffOrSignedOut() {
        assertNull("tailscale switched off", phoneWideTarget(true, connected.copy(enabled = false)))
        assertNull("signed out", phoneWideTarget(true, connected.copy(backend = "NeedsLogin")))
        assertNull("still starting", phoneWideTarget(true, connected.copy(backend = "Starting")))
        assertNull("no address yet", phoneWideTarget(true, connected.copy(self = null)))
        assertNull("no IPs", phoneWideTarget(true, connected.copy(self = self.copy(ips = emptyList()))))
    }

    @Test
    fun ipv4OnlyNodeStillWorks() {
        val v4only = connected.copy(self = self.copy(ips = listOf("100.64.0.7")))
        assertEquals(VpnAddresses("100.64.0.7", null), phoneWideTarget(true, v4only))
    }

    @Test
    fun routesAreTailnetOnly() {
        assertEquals(listOf("100.64.0.0" to 10, "fd7a:115c:a1e0::" to 48), TAILNET_ROUTES)
        assertTrue("no default route", TAILNET_ROUTES.none { it.second == 0 })
    }
}
