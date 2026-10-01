package dev.asenascale.ui

import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.asenascale.term.TerminalCanvasView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private sealed interface XKey {
    val label: String

    data class Code(override val label: String, val keyCode: Int, val shift: Boolean = false, val repeat: Boolean = false) : XKey
    data class Chars(override val label: String, val text: String = label) : XKey
    data class Mod(override val label: String, val ctrl: Boolean) : XKey
    /** Opens F1-F12. */
    data object Fn : XKey {
        override val label = "F1–12"
    }
}

private val mainRow = listOf(
    XKey.Code("esc", KeyEvent.KEYCODE_ESCAPE),
    XKey.Code("tab", KeyEvent.KEYCODE_TAB),
    XKey.Mod("ctrl", ctrl = true),
    XKey.Mod("alt", ctrl = false),
    XKey.Code("⇧tab", KeyEvent.KEYCODE_TAB, shift = true),
    XKey.Code("←", KeyEvent.KEYCODE_DPAD_LEFT, repeat = true),
    XKey.Code("↓", KeyEvent.KEYCODE_DPAD_DOWN, repeat = true),
    XKey.Code("↑", KeyEvent.KEYCODE_DPAD_UP, repeat = true),
    XKey.Code("→", KeyEvent.KEYCODE_DPAD_RIGHT, repeat = true),
)

private val fKeys = listOf(
    KeyEvent.KEYCODE_F1, KeyEvent.KEYCODE_F2, KeyEvent.KEYCODE_F3, KeyEvent.KEYCODE_F4,
    KeyEvent.KEYCODE_F5, KeyEvent.KEYCODE_F6, KeyEvent.KEYCODE_F7, KeyEvent.KEYCODE_F8,
    KeyEvent.KEYCODE_F9, KeyEvent.KEYCODE_F10, KeyEvent.KEYCODE_F11, KeyEvent.KEYCODE_F12,
)

private val symbolRow: List<XKey> =
    listOf<XKey>(XKey.Fn) + listOf("/", "\\", "|", "-", "_", "~", "*", "=", "+", "\"", "'", "`", "$", "&", ";", ":",
        "{", "}", "[", "]", "(", ")", "<", ">", "#", "!", "?", "%", "^", "@")
        .map { XKey.Chars(it) } + listOf(
        XKey.Code("home", KeyEvent.KEYCODE_MOVE_HOME),
        XKey.Code("end", KeyEvent.KEYCODE_MOVE_END),
        XKey.Code("pgup", KeyEvent.KEYCODE_PAGE_UP, repeat = true),
        XKey.Code("pgdn", KeyEvent.KEYCODE_PAGE_DOWN, repeat = true),
    )

/** Two quiet rows above the keyboard: control keys, then coding symbols. */
@Composable
fun ExtraKeys(term: TerminalCanvasView, modifier: Modifier = Modifier) {
    var ctrl by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }
    // Long press locks a modifier until it's tapped again.
    var fMenu by remember { mutableStateOf(false) }
    term.onModifiersConsumed = {
        if (term.ctrlLock) term.ctrlDown = true else ctrl = false
        if (term.altLock) term.altDown = true else alt = false
    }
    fun lock(k: XKey.Mod) {
        if (k.ctrl) {
            term.ctrlLock = !term.ctrlLock; ctrl = term.ctrlLock; term.ctrlDown = ctrl
        } else {
            term.altLock = !term.altLock; alt = term.altLock; term.altDown = alt
        }
    }

    fun press(k: XKey) {
        when (k) {
            is XKey.Mod -> if (k.ctrl) {
                ctrl = !ctrl; term.ctrlLock = false; term.ctrlDown = ctrl
            } else {
                alt = !alt; term.altLock = false; term.altDown = alt
            }
            XKey.Fn -> fMenu = true
            is XKey.Code -> term.sendKey(k.keyCode, k.shift)
            is XKey.Chars -> term.typeText(k.text)
        }
    }

    Column(
        modifier
            .fillMaxWidth()
            .background(Pal.mantle)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            mainRow.forEach { k ->
                val active = (k is XKey.Mod) && (if (k.ctrl) ctrl else alt)
                val locked = (k is XKey.Mod) && (if (k.ctrl) term.ctrlLock else term.altLock)
                Key(k, active, Modifier.weight(1f), locked = locked, onLongPress = (k as? XKey.Mod)?.let { m -> { lock(m) } }) { press(k) }
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            symbolRow.forEach { k ->
                if (k == XKey.Fn) {
                    Box {
                        Key(k, false, Modifier.widthIn(min = 38.dp), textPadding = 8.dp) { press(k) }
                        DropdownMenu(
                            expanded = fMenu,
                            onDismissRequest = { fMenu = false },
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.heightIn(max = 280.dp),
                        ) {
                            fKeys.forEachIndexed { i, code ->
                                DropdownMenuItem(
                                    text = { Text("F${i + 1}", fontFamily = Mono) },
                                    onClick = { fMenu = false; term.sendKey(code) },
                                )
                            }
                        }
                    }
                } else {
                    Key(k, false, Modifier.widthIn(min = 38.dp), textPadding = 8.dp) { press(k) }
                }
            }
        }
    }
}

@Composable
private fun Key(
    k: XKey,
    active: Boolean,
    modifier: Modifier,
    textPadding: Dp = 2.dp,
    locked: Boolean = false,
    onLongPress: (() -> Unit)? = null,
    onPress: () -> Unit,
) {
    val view = LocalView.current
    var pressed by remember { mutableStateOf(false) }
    val press by rememberUpdatedState(onPress)
    val long by rememberUpdatedState(onLongPress)
    val repeat = k is XKey.Code && k.repeat
    val scope = rememberCoroutineScope()

    Box(
        modifier
            .height(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    locked -> Pal.mauve.copy(alpha = 0.45f)
                    active -> Pal.mauve.copy(alpha = 0.22f)
                    pressed -> Pal.surface1
                    else -> Pal.surface0.copy(alpha = 0.55f)
                },
            )
            .pointerInput(repeat) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    if (long != null) {
                        // Modifier: tap toggles, holding locks.
                        var held = false
                        val timer = scope.launch {
                            delay(420)
                            held = true
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            long?.invoke()
                        }
                        val up = waitForUpOrCancellation()
                        timer.cancel()
                        if (up != null && !held) press()
                        pressed = false
                        return@awaitEachGesture
                    }
                    press()
                    // Hold to repeat, like a real keyboard.
                    val repeater = if (repeat) {
                        scope.launch {
                            delay(380)
                            while (true) {
                                press()
                                delay(45)
                            }
                        }
                    } else null
                    waitForUpOrCancellation()
                    repeater?.cancel()
                    pressed = false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            k.label,
            fontFamily = Mono,
            fontSize = if (k.label.length > 2) 11.sp else 15.sp,
            color = if (active) Pal.mauve else Pal.text,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            modifier = Modifier.padding(horizontal = textPadding),
        )
    }
}
