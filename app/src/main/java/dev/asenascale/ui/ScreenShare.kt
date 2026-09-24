package dev.asenascale.ui

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.asenascale.R
import dev.asenascale.data.Host
import dev.asenascale.screen.ScreenClient
import dev.asenascale.screen.ScreenView

/** The PC's screen, live and controllable, over the AsenaScale PC app. */
@Composable
fun ScreenShare(host: Host, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var monitor by remember { mutableIntStateOf(0) }
    var sharp by remember { mutableStateOf(false) }
    var keyboard by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf(true) }
    // A new stream per monitor keeps the protocol simple and the switch instant.
    val client = remember(monitor) { ScreenClient(host, monitor) }
    DisposableEffect(client) { onDispose { client.close() } }
    val view = remember { ScreenView(context) }
    val info by client.info.collectAsState()
    val error by client.error.collectAsState()
    val rate by client.rate.collectAsState()
    val accent = Pal.mauve

    LaunchedEffect(client) {
        view.client = client
        view.accent = accent.toArgb()
    }
    LaunchedEffect(client, sharp) {
        if (sharp) client.quality(75, 1920) else client.quality(50, 1280)
    }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(4000)
        hint = false
    }

    Dialog(
        onDismissRequest = { view.toggleKeyboard(false); onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(androidx.compose.ui.graphics.Color.Black)
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
                if (info == null && error == null) {
                    CircularProgressIndicator(
                        Modifier.align(Alignment.Center).size(28.dp),
                        strokeWidth = 2.5.dp,
                        color = Pal.mauve,
                    )
                }
                error?.let {
                    Text(
                        it,
                        color = Pal.red,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp)
                            .background(Pal.mantle, RoundedCornerShape(14.dp))
                            .padding(14.dp),
                    )
                }
                if (hint && info != null) {
                    Text(
                        stringResource(R.string.screen_hint),
                        color = Pal.text,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(12.dp)
                            .background(Pal.mantle.copy(alpha = 0.9f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
            if (keyboard) ScreenKeys(client)
            // Toolbar: close, monitors, keyboard, quality, fit, data meter.
            Row(
                Modifier.fillMaxWidth().background(Pal.mantle).padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { view.toggleKeyboard(false); onDismiss() }) {
                    Icon(AsIcons.Close, stringResource(R.string.close), tint = Pal.subtext)
                }
                val count = info?.monitors ?: 1
                if (count > 1) {
                    for (i in 0 until count) {
                        Chip((i + 1).toString(), active = i == monitor) { if (i != monitor) monitor = i }
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    Formatter.formatShortFileSize(context, rate) + "/s",
                    fontSize = 11.sp,
                    fontFamily = Mono,
                    color = Pal.overlay0,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
                Chip(stringResource(if (sharp) R.string.quality_high else R.string.quality_low), active = sharp) { sharp = !sharp }
                IconButton(onClick = { view.resetZoom() }) {
                    Icon(AsIcons.Focus, stringResource(R.string.fit), tint = Pal.subtext)
                }
                IconButton(onClick = {
                    keyboard = !keyboard
                    view.toggleKeyboard(keyboard)
                }) {
                    Icon(AsIcons.Keyboard, stringResource(R.string.keyboard), tint = if (keyboard) Pal.mauve else Pal.subtext)
                }
            }
        }
    }
}

@Composable
private fun Chip(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 12.sp,
        fontFamily = Mono,
        color = if (active) Pal.mauve else Pal.subtext,
        modifier = Modifier
            .padding(horizontal = 3.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) Pal.mauve.copy(alpha = 0.14f) else Pal.surface0.copy(alpha = 0.5f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/** Keys a phone keyboard lacks, for the PC. */
@Composable
private fun ScreenKeys(client: ScreenClient) {
    val keys = listOf(
        "esc" to "esc", "tab" to "tab", "enter" to "enter",
        "←" to "left", "↑" to "up", "↓" to "down", "→" to "right",
        "ctrl+c" to "ctrl+c", "ctrl+v" to "ctrl+v", "ctrl+z" to "ctrl+z", "ctrl+a" to "ctrl+a",
        "alt+tab" to "alt+tab", "win" to "win", "del" to "del", "home" to "home", "end" to "end",
    )
    Row(
        Modifier.fillMaxWidth().background(Pal.mantle).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for ((label, combo) in keys) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Pal.surface0.copy(alpha = 0.6f))
                    .clickable { client.key(combo) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(label, fontSize = 13.sp, fontFamily = Mono, color = Pal.text, maxLines = 1, softWrap = false)
            }
        }
        Spacer(Modifier.width(4.dp))
    }
    Spacer(Modifier.height(0.dp))
}
