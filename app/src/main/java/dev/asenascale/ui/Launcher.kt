package dev.asenascale.ui

import androidx.compose.foundation.background
import dev.asenascale.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.asenascale.App
import dev.asenascale.data.Host
import dev.asenascale.data.Tool
import dev.asenascale.data.Tools
import dev.asenascale.data.sessionKey
import dev.asenascale.ssh.ConnState
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import dev.asenascale.tailnet.HOST_APP_PORT
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect

/** Pick what to run on the PC: each tool is its own lasting terminal. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LauncherSheet(host: Host, onPick: (Tool) -> Unit, onEdit: () -> Unit, onDismiss: () -> Unit) {
    val open by App.instance.sessions.open.collectAsState()
    // Terminals already running on the PC (started there with
    // `asenascale claude`, or by another phone): join instead of starting anew.
    var running by remember(host.id) { mutableStateOf<List<PcSession>>(emptyList()) }
    if (host.port == HOST_APP_PORT) {
        LaunchedEffect(host.id) {
            running = withContext(Dispatchers.IO) { runCatching { pcSessions(host) }.getOrDefault(emptyList()) }
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding()) {
            Row(Modifier.padding(start = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(host.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Icon(
                    AsIcons.Edit, stringResource(R.string.edit), tint = Pal.overlay0,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onEdit).padding(8.dp).size(18.dp),
                )
            }
            for (tool in Tools.forHost(host)) {
                val state = open[sessionKey(host.id, tool.id)]?.state?.collectAsState()?.value
                val running = state != null && state !is ConnState.Closed
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onPick(tool) }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(AsIcons.Terminal, null, tint = if (running) Pal.mauve else Pal.subtext, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(tool.name, fontWeight = FontWeight.Medium)
                        Text(tool.command.ifEmpty { stringResource(R.string.tool_shell_desc) }, style = MonoSmall, color = Pal.overlay0)
                    }
                    if (running) {
                        Text(
                            stringResource(R.string.badge_open),
                            style = MonoSmall,
                            color = Pal.mauve,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Pal.mauve.copy(alpha = 0.12f))
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                }
            }
            if (running.isNotEmpty()) {
                Text(
                    stringResource(R.string.running_on_pc),
                    style = MonoSmall,
                    color = Pal.overlay0,
                    modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp),
                )
                for (ps in running) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable {
                                val tool = Tools.joined(ps.id, ps.label)
                                App.instance.sessionIds.set(sessionKey(host.id, tool.id), ps.id)
                                onPick(tool)
                            }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(AsIcons.Monitor, null, tint = Pal.mauve, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(ps.label, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOf(ps.cwd, ps.title).filter { it.isNotBlank() }.joinToString("  ·  "),
                                style = MonoSmall,
                                color = Pal.overlay0,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (ps.viewers > 0) {
                            Text(stringResource(R.string.badge_open), style = MonoSmall, color = Pal.mauve)
                        }
                    }
                }
            }
            Text(
                stringResource(R.string.launcher_note),
                fontSize = 12.sp,
                color = Pal.overlay0,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** A terminal session on the PC app (`mc sessions`). */
data class PcSession(val id: String, val viewers: Int, val command: String, val title: String, val cwd: String) {
    val label: String
        get() {
            val base = command.trim().substringBefore(' ').substringAfterLast('/').substringAfterLast('\\')
            if (base.isEmpty()) return Tools.shell.name
            return Tools.builtIn.firstOrNull { it.command == base }?.name ?: base
        }
}

/** Sessions on the PC that this phone doesn't already have as a tab/tool. */
private fun pcSessions(host: Host): List<PcSession> {
    val app = App.instance
    val r = app.transfers.run(host, "mc sessions")
    val mine = Tools.forHost(host).mapNotNull { app.sessionIds.get(sessionKey(host.id, it.id)) }.toSet()
    return r.stdout.toString(Charsets.UTF_8).lines().mapNotNull { line ->
        val f = line.split('\t')
        if (f.size < 6 || f[0] != "session" || f[1] in mine) return@mapNotNull null
        PcSession(f[1], f[2].toIntOrNull() ?: 0, f[4], f[5], f.getOrElse(6) { "" })
    }
}
