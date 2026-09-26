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
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
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
        kotlinx.coroutines.delay(6000)
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
                // While a modifier is held: a round button (bottom right) lets them all go.
                if (client.mods.values.any { it != ScreenClient.Mod.OFF }) {
                    ReleaseButton(Modifier.align(Alignment.BottomEnd).padding(10.dp)) { client.releaseMods() }
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

/**
 * Keys a phone keyboard lacks, in two rows. Modifiers: tap = for the next
 * key (win, then r = Win+R), long press = held until tapped again, tap
 * while armed = the key alone (win = Start menu). F opens F1-F12.
 */
@Composable
private fun ScreenKeys(client: ScreenClient) {
    val row1 = listOf("esc", "tab", "ctrl", "alt", "shift", "win", "F", "home", "end", "pgup", "pgdn", "del")
    val row2 = listOf("←" to "left", "↑" to "up", "↓" to "down", "→" to "right", "enter" to "enter", "⌫" to "backspace",
        "ctrl+c" to "ctrl+c", "ctrl+v" to "ctrl+v", "ctrl+z" to "ctrl+z", "alt+tab" to "alt+tab", "alt+f4" to "alt+f4")
    Column(
        Modifier.fillMaxWidth().background(Pal.mantle).padding(horizontal = 4.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (k in row1) {
                val mod = client.mods[k]
                when {
                    mod != null -> ScreenKey(
                        k,
                        state = mod,
                        onTap = { client.tapMod(k) },
                        onLong = { client.lockMod(k) },
                    )
                    k == "F" -> FKeys(client)
                    else -> ScreenKey(k, onTap = { client.key(k) })
                }
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((label, combo) in row2) {
                // Ready-made shortcuts ignore the sticky modifiers; the rest use them.
                ScreenKey(label, repeat = combo in setOf("left", "up", "down", "right", "backspace"), onTap = {
                    if ('+' in combo) client.sendRaw(combo) else client.key(combo)
                })
            }
        }
    }
}

/** Round "let go" button shown while modifiers are held. */
@Composable
fun ReleaseButton(modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .size(42.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(Pal.mauve.copy(alpha = 0.9f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(AsIcons.Undo, stringResource(R.string.release_keys), tint = Pal.base, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun FKeys(client: ScreenClient) {
    var open by remember { mutableStateOf(false) }
    Box {
        ScreenKey("F1–12", onTap = { open = true })
        androidx.compose.material3.DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.heightIn(max = 280.dp),
        ) {
            for (n in 1..12) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("F$n", fontFamily = Mono) },
                    onClick = {
                        open = false
                        client.key("f$n")
                    },
                )
            }
        }
    }
}

@Composable
private fun ScreenKey(
    label: String,
    state: ScreenClient.Mod = ScreenClient.Mod.OFF,
    repeat: Boolean = false,
    onTap: () -> Unit,
    onLong: (() -> Unit)? = null,
) {
    val view = androidx.compose.ui.platform.LocalView.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val tap by androidx.compose.runtime.rememberUpdatedState(onTap)
    val long by androidx.compose.runtime.rememberUpdatedState(onLong)
    var pressed by remember { mutableStateOf(false) }
    Box(
        Modifier
            .height(38.dp)
            .widthIn(min = 44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    state == ScreenClient.Mod.LOCKED -> Pal.mauve.copy(alpha = 0.45f)
                    state == ScreenClient.Mod.ONCE -> Pal.mauve.copy(alpha = 0.2f)
                    pressed -> Pal.surface1
                    else -> Pal.surface0.copy(alpha = 0.6f)
                },
            )
            .pointerInput(repeat) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                    var longFired = false
                    val job = scope.launch {
                        delay(420)
                        if (long != null) {
                            longFired = true
                            view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                            long?.invoke()
                        } else if (repeat) {
                            longFired = true
                            while (true) {
                                tap()
                                delay(50)
                            }
                        }
                    }
                    val up = waitForUpOrCancellation()
                    job.cancel()
                    if (up != null && !longFired) tap()
                    pressed = false
                }
            }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 13.sp,
            fontFamily = Mono,
            color = if (state != ScreenClient.Mod.OFF) Pal.mauve else Pal.text,
            maxLines = 1,
            softWrap = false,
        )
    }
}
