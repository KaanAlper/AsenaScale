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

private val symbolRow: List<XKey> =
    listOf("/", "\\", "|", "-", "_", "~", "*", "=", "+", "\"", "'", "`", "$", "&", ";", ":",
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
    term.onModifiersConsumed = { ctrl = false; alt = false }

    fun press(k: XKey) {
        when (k) {
            is XKey.Mod -> if (k.ctrl) {
                ctrl = !ctrl; term.ctrlDown = ctrl
            } else {
                alt = !alt; term.altDown = alt
            }
            is XKey.Code -> term.sendKey(k.keyCode, k.shift)
            is XKey.Chars -> term.typeText(k.text)
        }
    }

    Column(
        modifier
            .fillMaxWidth()
            .background(Mocha.mantle)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            mainRow.forEach { k ->
                val active = (k is XKey.Mod) && (if (k.ctrl) ctrl else alt)
                Key(k, active, Modifier.weight(1f)) { press(k) }
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            symbolRow.forEach { k -> Key(k, false, Modifier.widthIn(min = 38.dp), textPadding = 8.dp) { press(k) } }
        }
    }
}

@Composable
private fun Key(k: XKey, active: Boolean, modifier: Modifier, textPadding: Dp = 2.dp, onPress: () -> Unit) {
    val view = LocalView.current
    var pressed by remember { mutableStateOf(false) }
    val press by rememberUpdatedState(onPress)
    val repeat = k is XKey.Code && k.repeat
    val scope = rememberCoroutineScope()

    Box(
        modifier
            .height(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    active -> Mocha.mauve.copy(alpha = 0.22f)
                    pressed -> Mocha.surface1
                    else -> Mocha.surface0.copy(alpha = 0.55f)
                },
            )
            .pointerInput(repeat) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
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
            color = if (active) Mocha.mauve else Mocha.text,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            modifier = Modifier.padding(horizontal = textPadding),
        )
    }
}
