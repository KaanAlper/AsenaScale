package dev.mobileclaude.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import dev.mobileclaude.App
import dev.mobileclaude.ssh.ConnState
import dev.mobileclaude.term.TerminalCanvasView
import dev.mobileclaude.term.TermTheme

@Composable
fun TerminalScreen(hostId: String, onBack: () -> Unit) {
    val app = App.instance
    val context = LocalContext.current
    val host = remember(hostId) { app.hosts.get(hostId) }
    if (host == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    var conn by remember(hostId) { mutableStateOf(app.sessions.connect(host)) }
    val state by conn.state.collectAsState()
    val title by conn.title.collectAsState()
    val term = remember { TerminalCanvasView(context) }
    var menu by remember { mutableStateOf(false) }
    var shots by remember { mutableStateOf(false) }

    LaunchedEffect(conn) { term.connection = conn }
    LaunchedEffect(state) { if (state is ConnState.Connected) term.showKeyboard() }
    DisposableEffect(Unit) { onDispose { term.connection = null } }

    fun reconnect() {
        app.sessions.disconnect(host.id)
        conn = app.sessions.connect(app.hosts.get(host.id) ?: host)
    }

    term.onLongPress = { app.clipboardText()?.let { term.paste(it) } }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(TermTheme.BACKGROUND))
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        // Slim top bar: nothing that competes with the terminal.
        Row(
            Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Geri", tint = Mocha.subtext) }
            StatusDot(
                when (state) {
                    ConnState.Connected -> Mocha.green
                    ConnState.Connecting -> Mocha.peach
                    is ConnState.Closed -> Mocha.red
                },
                size = 7,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                title,
                fontFamily = Mono,
                fontSize = 13.sp,
                color = Mocha.subtext,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { shots = true }, enabled = state == ConnState.Connected) {
                Icon(Icons.Outlined.PhotoCamera, "Ekran görüntüsü", tint = Mocha.subtext)
            }
            IconButton(onClick = { app.clipboardText()?.let { term.paste(it) } }) {
                Icon(Icons.Outlined.ContentPaste, "Yapıştır", tint = Mocha.subtext)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Menü", tint = Mocha.subtext) }
                DropdownMenu(
                    expanded = menu,
                    onDismissRequest = { menu = false },
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    DropdownMenuItem(text = { Text("Tüm metni kopyala") }, onClick = {
                        menu = false
                        app.copyToClipboard(term.copyAllText())
                    })
                    DropdownMenuItem(text = { Text("Yeniden bağlan") }, onClick = { menu = false; reconnect() })
                    DropdownMenuItem(text = { Text("Bağlantıyı kapat", color = Mocha.red) }, onClick = {
                        menu = false
                        app.sessions.disconnect(host.id)
                        onBack()
                    })
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(factory = { term }, modifier = Modifier.fillMaxSize())

            AnimatedVisibility(state == ConnState.Connecting, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.Center)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp, color = Mocha.mauve)
                    Spacer(Modifier.height(12.dp))
                    Text("${host.address} bağlanıyor…", fontFamily = Mono, fontSize = 12.sp, color = Mocha.overlay0)
                }
            }

            val closed = state as? ConnState.Closed
            AnimatedVisibility(closed != null, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.Center)) {
                Column(
                    Modifier
                        .padding(24.dp)
                        .background(Mocha.mantle.copy(alpha = 0.96f), RoundedCornerShape(20.dp))
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Bağlantı kesildi", fontWeight = FontWeight.Medium)
                    Text(
                        closed?.reason.orEmpty(),
                        fontSize = 13.sp,
                        color = Mocha.subtext,
                        textAlign = TextAlign.Center,
                    )
                    Button(onClick = { reconnect() }, shape = RoundedCornerShape(12.dp)) { Text("Yeniden bağlan") }
                }
            }
        }

        ExtraKeys(term)
    }

    if (shots) ScreenshotSheet(conn, onDismiss = { shots = false })
}
