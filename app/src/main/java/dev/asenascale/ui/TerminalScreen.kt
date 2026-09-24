package dev.asenascale.ui

import android.net.Uri
import dev.asenascale.ssh.SshConnection
import dev.asenascale.R
import androidx.compose.ui.res.stringResource
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.rememberCoroutineScope
import dev.asenascale.files.Uploads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import dev.asenascale.data.Tools
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.asenascale.App
import dev.asenascale.data.AuthMode
import dev.asenascale.ssh.ConnState
import dev.asenascale.ssh.Keys
import dev.asenascale.term.TerminalCanvasView

@Composable
fun TerminalScreen(hostId: String, toolId: String, onSwitch: (toolId: String) -> Unit, onBack: () -> Unit) {
    val app = App.instance
    val context = LocalContext.current
    val host = remember(hostId) { app.hosts.get(hostId) }
    if (host == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val tool = remember(hostId, toolId) { if (toolId.isEmpty()) Tools.default(host) else Tools.find(host, toolId) }
    // The connection can be swapped under us (automatic reconnect); follow it.
    val first = remember(hostId, tool.id) { app.sessions.connect(host, tool) }
    val open by app.sessions.open.collectAsState()
    val conn = open[first.key] ?: first
    val tabs = open.values.filter { it.host.id == hostId }.sortedBy { it.tool.name }
    var launcher by remember { mutableStateOf(false) }
    val state by conn.state.collectAsState()
    val title by conn.title.collectAsState()
    val term = remember { TerminalCanvasView(context) }
    var menu by remember { mutableStateOf(false) }
    var shots by remember { mutableStateOf(false) }

    val dark = LocalPalette.current.dark
    LaunchedEffect(conn, dark) {
        term.connection = conn
        term.setDark(dark)
    }
    LaunchedEffect(state) { if (state is ConnState.Connected) term.showKeyboard() }
    DisposableEffect(Unit) { onDispose { term.connection = null } }

    fun reconnect() = app.sessions.reconnect(conn.key)

    var selectText by remember { mutableStateOf<String?>(null) }
    term.onLongPress = { selectText = term.copyAllText() }

    // Files and photos from the phone go to the PC; their PC path is typed
    // into the terminal, ready for Claude ("bu resme bak: C:\...").
    val scope = rememberCoroutineScope()
    var uploading by remember { mutableStateOf<String?>(null) }
    var attachMenu by remember { mutableStateOf(false) }
    fun upload(uris: List<Uri>) {
        if (uris.isEmpty() || uploading != null) return
        scope.launch {
            val paths = mutableListOf<String>()
            for ((i, uri) in uris.withIndex()) {
                uploading = if (uris.size > 1) context.getString(R.string.sending_n, i + 1, uris.size) else context.getString(R.string.sending)
                runCatching { withContext(Dispatchers.IO) { Uploads.send(context, conn, uri) } }
                    .onSuccess { paths += it }
                    .onFailure { Toast.makeText(context, context.getString(R.string.send_failed, it.message ?: ""), Toast.LENGTH_LONG).show() }
            }
            uploading = null
            if (paths.isNotEmpty()) {
                conn.sendText(paths.joinToString(" ") { Uploads.quoted(it) } + " ")
                term.showKeyboard()
            }
        }
    }
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { upload(it) }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { upload(it) }
    val hint by conn.hint.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .background(Pal.base)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        // Slim top bar: nothing that competes with the terminal.
        Row(
            Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(AsIcons.Back, stringResource(R.string.back), tint = Pal.subtext) }
            StatusDot(
                when (state) {
                    ConnState.Connected -> Pal.green
                    ConnState.Connecting -> Pal.peach
                    is ConnState.Closed -> Pal.red
                },
                size = 7,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                title,
                fontFamily = Mono,
                fontSize = 13.sp,
                color = Pal.subtext,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { shots = true }, enabled = state == ConnState.Connected) {
                Icon(AsIcons.Camera, stringResource(R.string.screenshot), tint = Pal.subtext)
            }
            Box {
                IconButton(onClick = { attachMenu = true }, enabled = state == ConnState.Connected && uploading == null) {
                    Icon(AsIcons.Attach, stringResource(R.string.send_to_pc), tint = Pal.subtext)
                }
                DropdownMenu(
                    expanded = attachMenu,
                    onDismissRequest = { attachMenu = false },
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.photo_video)) },
                        leadingIcon = { Icon(AsIcons.Image, null) },
                        onClick = {
                            attachMenu = false
                            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.file)) },
                        leadingIcon = { Icon(AsIcons.File, null) },
                        onClick = { attachMenu = false; pickFiles.launch("*/*") },
                    )
                }
            }
            IconButton(onClick = { app.clipboardText()?.let { term.paste(it) } }) {
                Icon(AsIcons.Paste, stringResource(R.string.paste), tint = Pal.subtext)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(AsIcons.More, stringResource(R.string.menu), tint = Pal.subtext) }
                DropdownMenu(
                    expanded = menu,
                    onDismissRequest = { menu = false },
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.select_text)) }, onClick = {
                        menu = false
                        selectText = term.copyAllText()
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.reconnect)) }, onClick = { menu = false; reconnect() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.leave)) }, onClick = {
                        menu = false
                        app.sessions.disconnect(conn.key)
                        onBack()
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.end_session), color = Pal.red) }, onClick = {
                        menu = false
                        app.sessions.end(conn.key)
                        onBack()
                    })
                }
            }
        }

        // Terminals open on this PC, one per tool; tap to switch, + for more.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (t in tabs) {
                val active = t.key == conn.key
                Text(
                    t.tool.name,
                    fontSize = 12.sp,
                    fontFamily = Mono,
                    color = if (active) Pal.mauve else Pal.subtext,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (active) Pal.mauve.copy(alpha = 0.14f) else Pal.surface0.copy(alpha = 0.5f))
                        .clickable { if (!active) onSwitch(t.tool.id) }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
            Icon(
                AsIcons.Plus, stringResource(R.string.new_terminal), tint = Pal.subtext,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { launcher = true }.padding(6.dp).size(16.dp),
            )
        }

        uploading?.let {
            Column(Modifier.fillMaxWidth()) {
                LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = Pal.mauve, trackColor = Pal.surface0)
                Text(it, fontSize = 12.sp, color = Pal.subtext, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(factory = { term }, modifier = Modifier.fillMaxSize())

            Fade(state == ConnState.Connecting, Modifier.align(Alignment.Center)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp, color = Pal.mauve)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (conn.attempt > 0) stringResource(R.string.reconnecting) else stringResource(R.string.connecting_to, host.address),
                        fontFamily = Mono,
                        fontSize = 12.sp,
                        color = Pal.overlay0,
                    )
                    hint?.let {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            it,
                            fontSize = 14.sp,
                            color = Pal.text,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .padding(horizontal = 32.dp)
                                .background(Pal.mantle, RoundedCornerShape(14.dp))
                                .padding(14.dp),
                        )
                    }
                }
            }

            val closed = state as? ConnState.Closed
            Fade(closed != null, Modifier.align(Alignment.Center)) {
                Column(
                    Modifier
                        .padding(24.dp)
                        .background(Pal.mantle.copy(alpha = 0.96f), RoundedCornerShape(20.dp))
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(R.string.disconnected), fontWeight = FontWeight.Medium)
                    Text(
                        closed?.reason.orEmpty(),
                        fontSize = 13.sp,
                        color = Pal.subtext,
                        textAlign = TextAlign.Center,
                    )
                    val reason = closed?.reason.orEmpty()
                    if ((conn.failure == SshConnection.Failure.AUTH && host.auth == AuthMode.KEY) || conn.failure == SshConnection.Failure.NEEDS_SETUP) {
                        val windows = app.tailnet.peerFor(host.address)?.isWindows == true
                        TextButton(onClick = {
                            app.copyToClipboard(if (windows) Keys.windowsSetupScript(context) else Keys.publicKey(context))
                        }) {
                            Text(
                                if (windows) stringResource(R.string.copy_windows_setup) else stringResource(R.string.copy_ssh_key_hint),
                                color = Pal.mauve,
                                fontSize = 13.sp,
                            )
                        }
                    }
                    Button(onClick = { reconnect() }, shape = RoundedCornerShape(12.dp)) { Text(stringResource(R.string.reconnect)) }
                }
            }
        }

        ExtraKeys(term)
    }

    if (shots) ScreenshotSheet(conn, onDismiss = { shots = false })
    selectText?.let { SelectTextDialog(it, onDismiss = { selectText = null }) }
    if (launcher) {
        LauncherSheet(
            host = host,
            onPick = { launcher = false; onSwitch(it.id) },
            onEdit = { launcher = false },
            onDismiss = { launcher = false },
        )
    }
}

/** Top-level so it isn't captured by the enclosing ColumnScope overload. */
@Composable
private fun Fade(visible: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    AnimatedVisibility(visible, modifier, enter = fadeIn(), exit = fadeOut()) { content() }
}
