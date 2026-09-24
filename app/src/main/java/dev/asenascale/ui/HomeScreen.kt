package dev.asenascale.ui

import android.content.Intent
import dev.asenascale.R
import androidx.compose.ui.res.stringResource
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import dev.asenascale.tailnet.HOST_APP_PORT
import dev.asenascale.data.Tool
import dev.asenascale.data.AuthMode
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.asenascale.App
import dev.asenascale.data.Host
import dev.asenascale.ssh.ConnState
import dev.asenascale.ssh.Keys
import dev.asenascale.tailnet.Peer
import dev.asenascale.tailnet.TailnetState

@Composable
fun HomeScreen(
    onOpenTerminal: (Host, Tool) -> Unit,
    onEditHost: (Host) -> Unit,
    onNewHost: (address: String?) -> Unit,
) {
    val app = App.instance
    val ts by app.tailnet.state.collectAsState()
    val hosts by app.hosts.hosts.collectAsState()
    val open by app.sessions.open.collectAsState()
    var showKey by remember { mutableStateOf(false) }
    var launcherFor by remember { mutableStateOf<Host?>(null) }
    var deviceFor by remember { mutableStateOf<Peer?>(null) }
    val onOpenHost: (Host) -> Unit = { launcherFor = it }

    // PCs running AsenaScale show up by themselves: while this screen is
    // open, online non-phone devices are knocked on once a minute.
    LaunchedEffect(ts.running) {
        while (ts.running) {
            withContext(Dispatchers.IO) { discoverHostApps() }
            delay(60_000)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AsIcons.Logo, null, tint = Pal.mauve, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(10.dp))
                Text("AsenaScale", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { showKey = true }) {
                    Icon(AsIcons.Key, contentDescription = stringResource(R.string.my_ssh_key), tint = Pal.subtext)
                }
            }
        }

        item { TailscaleCard(ts) }

        item {
            SectionHeader(stringResource(R.string.section_computers)) {
                IconButton(onClick = { onNewHost(null) }) {
                    Icon(AsIcons.Plus, contentDescription = stringResource(R.string.add), tint = Pal.subtext)
                }
            }
        }
        if (hosts.isEmpty()) {
            item {
                Hint(stringResource(R.string.hint_no_computers))
            }
        }
        items(hosts, key = { it.id }) { host ->
            val peer = ts.peers.firstOrNull { it.matches(host.address) }
            val openHere = open.values.count { it.host.id == host.id && it.state.collectAsState().value !is ConnState.Closed }
            HostRow(
                host = host,
                online = peer?.online,
                sessionOpen = openHere > 0,
                onClick = { onOpenHost(host) },
                onEdit = { onEditHost(host) },
            )
        }

        if (ts.running) {
            item { SectionHeader(stringResource(R.string.section_devices)) }
            if (ts.peers.isEmpty()) item { Hint(stringResource(R.string.no_other_devices)) }
            items(ts.peers, key = { "peer:" + it.dnsName + it.name }) { peer ->
                PeerRow(peer, saved = hosts.any { peer.matches(it.address) }) { deviceFor = peer }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    if (showKey) KeyDialog(onDismiss = { showKey = false })
    deviceFor?.let { d ->
        // Keep the sheet's numbers live while it's open.
        val peer = ts.peers.firstOrNull { it.dnsName == d.dnsName } ?: d
        val existing = hosts.firstOrNull { peer.matches(it.address) }
        DeviceSheet(
            peer = peer,
            saved = existing != null,
            onAction = {
                deviceFor = null
                if (existing != null) launcherFor = existing else onNewHost(peer.shortName)
            },
            onDismiss = { deviceFor = null },
        )
    }
    launcherFor?.let { host ->
        LauncherSheet(
            host = host,
            onPick = { tool -> launcherFor = null; onOpenTerminal(host, tool) },
            onEdit = { launcherFor = null; onEditHost(host) },
            onDismiss = { launcherFor = null },
        )
    }
}

private val checked = HashMap<String, Long>()

/** Adds every online PC that runs the AsenaScale app to the saved hosts. */
private fun discoverHostApps() {
    val app = App.instance
    val now = System.currentTimeMillis()
    val candidates = app.tailnet.state.value.peers.filter { peer ->
        peer.online && !peer.os.equals("android", true) && !peer.os.equals("iOS", true) &&
            app.hosts.hosts.value.none { peer.matches(it.address) } &&
            (checked[peer.dnsName] ?: 0L) < now - 5 * 60_000
    }
    for (peer in candidates) {
        checked[peer.dnsName] = now
        val ip = peer.ipv4 ?: continue
        if (app.tailnet.isHostApp(ip)) {
            app.hosts.save(
                Host(
                    name = peer.shortName.removePrefix("asenascale-"),
                    address = peer.shortName,
                    port = HOST_APP_PORT,
                    user = "pc",
                    auth = AuthMode.KEY,
                ),
            )
        }
    }
}

private fun Peer.matches(address: String): Boolean {
    val a = address.trim().trimEnd('.').lowercase()
    return a.isNotEmpty() && (a == shortName.lowercase() || a == dnsName.lowercase() || a in ips || a == name.lowercase())
}

@Composable
private fun TailscaleCard(ts: TailnetState) {
    val app = App.instance
    val context = LocalContext.current
    var waitingForUrl by remember { mutableStateOf(false) }

    // After "Giriş yap", open the browser as soon as the login URL shows up.
    LaunchedEffect(ts.authUrl, waitingForUrl) {
        if (waitingForUrl && ts.authUrl.isNotEmpty()) {
            waitingForUrl = false
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ts.authUrl)))
        }
    }

    val (dot, label) = when {
        !ts.enabled -> Pal.overlay0 to stringResource(R.string.ts_off)
        ts.running -> Pal.green to stringResource(R.string.ts_connected)
        ts.needsLogin -> Pal.yellow to stringResource(R.string.ts_needs_login)
        else -> Pal.peach to stringResource(R.string.ts_connecting)
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column(Modifier.padding(start = 18.dp, end = 14.dp, top = 14.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(dot)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Tailscale", fontWeight = FontWeight.Medium)
                    val detail = when {
                        ts.running -> listOfNotNull(ts.self?.ipv4, ts.tailnet.ifEmpty { null }).joinToString("  ·  ")
                        else -> label
                    }
                    Text(
                        if (ts.running) detail else label,
                        style = MonoSmall,
                        color = Pal.subtext,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Switch(
                    checked = ts.enabled,
                    onCheckedChange = { app.tailnet.setEnabled(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Pal.crust,
                        checkedTrackColor = Pal.mauve,
                        uncheckedThumbColor = Pal.overlay0,
                        uncheckedTrackColor = Pal.surface0,
                        uncheckedBorderColor = Pal.surface1,
                    ),
                )
            }
            if (ts.enabled && ts.needsLogin) {
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        if (ts.authUrl.isNotEmpty()) {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ts.authUrl)))
                        } else {
                            waitingForUrl = true
                            app.tailnet.login()
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (waitingForUrl) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Pal.crust)
                    } else {
                        Text(stringResource(R.string.sign_in))
                    }
                }
            }
            if (ts.enabled && ts.running) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { app.tailnet.logout() }) {
                        Icon(AsIcons.Logout, null, Modifier.size(16.dp), tint = Pal.overlay0)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.sign_out), color = Pal.overlay0, fontSize = 13.sp)
                    }
                }
            }
            if (ts.enabled && ts.error.isNotEmpty() && !ts.running && ts.backend != "Starting") {
                Spacer(Modifier.height(6.dp))
                Text(ts.error, style = MonoSmall, color = Pal.red, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun StatusDot(color: Color, size: Int = 8) {
    val c by animateColorAsState(color, label = "dot")
    Box(Modifier.size(size.dp).clip(CircleShape).background(c))
}

@Composable
private fun SectionHeader(title: String, action: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, top = 16.dp).height(40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = Pal.overlay0,
            modifier = Modifier.weight(1f),
        )
        action()
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = Pal.overlay0, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HostRow(host: Host, online: Boolean?, sessionOpen: Boolean, onClick: () -> Unit, onEdit: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick, onLongClick = onEdit),
    ) {
        Row(Modifier.padding(start = 18.dp, end = 6.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusDot(
                when (online) {
                    true -> Pal.green
                    false -> Pal.overlay0
                    null -> Pal.surface1
                },
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(host.title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (sessionOpen) {
                        Spacer(Modifier.width(8.dp))
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
                Text(
                    "${host.user}@${host.address}" + if (host.startup.isNotBlank()) "  ›  ${host.startup}" else "",
                    style = MonoSmall,
                    color = Pal.subtext,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onEdit) {
                Icon(AsIcons.Edit, contentDescription = stringResource(R.string.edit), tint = Pal.overlay0, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun PeerRow(peer: Peer, saved: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(start = 18.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(if (peer.online) Pal.green else Pal.surface1, size = 7)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                peer.shortName,
                color = if (peer.online) Pal.text else Pal.overlay0,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(peer.os.ifEmpty { null }, peer.ipv4).joinToString("  ·  "),
                style = MonoSmall,
                color = Pal.overlay0,
            )
        }
        Text(if (peer.online) stringResource(R.string.online) else stringResource(R.string.offline), fontSize = 12.sp, color = if (peer.online) Pal.green else Pal.overlay0)
        Spacer(Modifier.width(4.dp))
        Icon(
            if (saved) AsIcons.Chevron else AsIcons.Plus,
            contentDescription = null,
            tint = Pal.overlay0,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun KeyDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val key = remember { Keys.publicKey(context) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        title = { Text(stringResource(R.string.my_ssh_key)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.key_dialog_windows),
                    fontSize = 14.sp,
                    color = Pal.subtext,
                )
                Button(
                    onClick = { App.instance.copyToClipboard(Keys.windowsSetupScript(context)); onDismiss() },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.copy_windows_setup)) }
                Text(
                    stringResource(R.string.key_dialog_linux),
                    fontSize = 14.sp,
                    color = Pal.subtext,
                )
                SelectionContainer {
                    Text(
                        key,
                        style = MonoSmall,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Pal.crust)
                            .padding(12.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { App.instance.copyToClipboard(key); onDismiss() }) {
                Icon(AsIcons.Copy, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.copy_key))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close), color = Pal.subtext) } },
    )
}
