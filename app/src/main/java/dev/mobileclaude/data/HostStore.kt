package dev.mobileclaude.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class AuthMode { TAILSCALE, KEY, PASSWORD }

data class Host(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    /** MagicDNS name or 100.x address of the machine on the tailnet. */
    val address: String = "",
    val port: Int = 22,
    val user: String = "",
    val auth: AuthMode = AuthMode.KEY,
    val password: String = "",
    /** Typed into the shell right after connecting, e.g. "claude". */
    val startup: String = "",
) {
    val title get() = name.ifBlank { address }
}

class HostStore(context: Context) {
    private val prefs = context.getSharedPreferences("hosts", Context.MODE_PRIVATE)
    private val _hosts = MutableStateFlow(load())
    val hosts: StateFlow<List<Host>> = _hosts

    fun get(id: String) = _hosts.value.firstOrNull { it.id == id }

    fun save(host: Host) {
        val list = _hosts.value.toMutableList()
        val i = list.indexOfFirst { it.id == host.id }
        if (i >= 0) list[i] = host else list += host
        persist(list)
    }

    fun delete(id: String) = persist(_hosts.value.filterNot { it.id == id })

    private fun persist(list: List<Host>) {
        _hosts.value = list
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id).put("name", it.name).put("address", it.address)
                    .put("port", it.port).put("user", it.user).put("auth", it.auth.name)
                    .put("password", it.password).put("startup", it.startup)
            )
        }
        prefs.edit().putString("list", arr.toString()).apply()
    }

    private fun load(): List<Host> {
        val arr = runCatching { JSONArray(prefs.getString("list", "[]")) }.getOrElse { JSONArray() }
        return List(arr.length()) {
            val o = arr.getJSONObject(it)
            Host(
                id = o.getString("id"),
                name = o.optString("name"),
                address = o.optString("address"),
                port = o.optInt("port", 22),
                user = o.optString("user"),
                auth = runCatching { AuthMode.valueOf(o.optString("auth")) }.getOrDefault(AuthMode.KEY),
                password = o.optString("password"),
                startup = o.optString("startup"),
            )
        }
    }
}
