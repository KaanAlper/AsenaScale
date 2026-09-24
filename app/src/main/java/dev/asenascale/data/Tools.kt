package dev.asenascale.data

import android.content.Context
import dev.asenascale.R
import dev.asenascale.App
import java.util.UUID

/** Something to run in a terminal on the PC: an AI CLI, or just the shell. */
data class Tool(val id: String, val name: String, val command: String)

object Tools {
    val shell get() = Tool("shell", App.instance.getString(R.string.tool_shell), "")
    val builtIn get() = listOf(
        Tool("claude", "Claude Code", "claude"),
        Tool("codex", "Codex", "codex"),
        Tool("gemini", "Gemini CLI", "gemini"),
        Tool("grok", "Grok CLI", "grok"),
        shell,
    )

    /** The built-ins, plus the host's own startup command when it's something else. */
    fun forHost(host: Host): List<Tool> {
        val custom = host.startup.trim()
        val extra = if (custom.isNotEmpty() && builtIn.none { it.command == custom }) {
            listOf(Tool("custom", custom, custom))
        } else emptyList()
        return extra + builtIn
    }

    fun find(host: Host, id: String): Tool = forHost(host).firstOrNull { it.id == id } ?: shell

    /** Which tool a plain tap opens: the host's startup command, else Claude. */
    fun default(host: Host): Tool = forHost(host).first()
}

/**
 * Session ids on the PC app per (host, tool), so each terminal is found
 * again after reconnects and app restarts.
 */
class SessionIds(context: Context) {
    private val prefs = context.getSharedPreferences("session_ids", Context.MODE_PRIVATE)

    fun getOrCreate(key: String): String =
        prefs.getString(key, null) ?: ("p" + UUID.randomUUID().toString().replace("-", "").take(12)).also {
            prefs.edit().putString(key, it).apply()
        }

    fun clear(key: String) = prefs.edit().remove(key).apply()
}

fun sessionKey(hostId: String, toolId: String) = "$hostId/$toolId"
