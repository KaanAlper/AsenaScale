package dev.mobileclaude.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.rememberCoroutineScope
import dev.mobileclaude.files.Uploads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import dev.mobileclaude.App
import dev.mobileclaude.data.AuthMode
import dev.mobileclaude.ssh.ConnState
import dev.mobileclaude.ssh.Keys
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
                uploading = if (uris.size > 1) "Gönderiliyor ${i + 1}/${uris.size}…" else "Gönderiliyor…"
                runCatching { withContext(Dispatchers.IO) { Uploads.send(context, conn, uri) } }
                    .onSuccess { paths += it }
                    .onFailure { Toast.makeText(context, "Gönderilemedi: ${it.message}", Toast.LENGTH_LONG).show() }
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
            Box {
                IconButton(onClick = { attachMenu = true }, enabled = state == ConnState.Connected && uploading == null) {
                    Icon(Icons.Outlined.AttachFile, "PC'ye gönder", tint = Mocha.subtext)
                }
                DropdownMenu(
                    expanded = attachMenu,
                    onDismissRequest = { attachMenu = false },
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    DropdownMenuItem(
                        text = { Text("Fotoğraf / video") },
                        leadingIcon = { Icon(Icons.Outlined.Image, null) },
                        onClick = {
                            attachMenu = false
                            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Dosya") },
                        leadingIcon = { Icon(Icons.Outlined.Description, null) },
                        onClick = { attachMenu = false; pickFiles.launch("*/*") },
                    )
                }
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
                    DropdownMenuItem(text = { Text("Metin seç") }, onClick = {
                        menu = false
                        selectText = term.copyAllText()
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

        uploading?.let {
            Column(Modifier.fillMaxWidth()) {
                LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = Mocha.mauve, trackColor = Mocha.surface0)
                Text(it, fontSize = 12.sp, color = Mocha.subtext, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(factory = { term }, modifier = Modifier.fillMaxSize())

            Fade(state == ConnState.Connecting, Modifier.align(Alignment.Center)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp, color = Mocha.mauve)
                    Spacer(Modifier.height(12.dp))
                    Text("${host.address} bağlanıyor…", fontFamily = Mono, fontSize = 12.sp, color = Mocha.overlay0)
                    hint?.let {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            it,
                            fontSize = 14.sp,
                            color = Mocha.text,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .padding(horizontal = 32.dp)
                                .background(Mocha.mantle, RoundedCornerShape(14.dp))
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
                    val reason = closed?.reason.orEmpty()
                    if ((reason.startsWith("Kimlik") && host.auth == AuthMode.KEY) || "kurulum komutu" in reason) {
                        val windows = app.tailnet.peerFor(host.address)?.isWindows == true
                        TextButton(onClick = {
                            app.copyToClipboard(if (windows) Keys.windowsSetupScript(context) else Keys.publicKey(context))
                        }) {
                            Text(
                                if (windows) "Windows kurulum komutunu kopyala" else "SSH anahtarını kopyala (authorized_keys için)",
                                color = Mocha.mauve,
                                fontSize = 13.sp,
                            )
                        }
                    }
                    Button(onClick = { reconnect() }, shape = RoundedCornerShape(12.dp)) { Text("Yeniden bağlan") }
                }
            }
        }

        ExtraKeys(term)
    }

    if (shots) ScreenshotSheet(conn, onDismiss = { shots = false })
    selectText?.let { SelectTextDialog(it, onDismiss = { selectText = null }) }
}

/** Top-level so it isn't captured by the enclosing ColumnScope overload. */
@Composable
private fun Fade(visible: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    AnimatedVisibility(visible, modifier, enter = fadeIn(), exit = fadeOut()) { content() }
}
