package dev.mobileclaude.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.mobileclaude.App

private val urlRegex = Regex("""https?://[^\s<>"'`]+[^\s<>"'`.,;:!?)\]]""")

/** Terminal text with native Android selection handles; URLs are tappable. */
@Composable
fun SelectTextDialog(text: String, onDismiss: () -> Unit) {
    val annotated = remember(text) { linkify(text) }
    val scroll = rememberScrollState()
    LaunchedEffect(Unit) { scroll.scrollTo(scroll.maxValue) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Mocha.base).statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Kapat", tint = Mocha.subtext) }
                Text("Metin seç", color = Mocha.subtext, fontSize = 15.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { App.instance.copyToClipboard(text); onDismiss() }) {
                    Icon(Icons.Outlined.ContentCopy, null, tint = Mocha.mauve)
                    Text("  Tümünü kopyala", color = Mocha.mauve)
                }
            }
            Box(Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 12.dp, vertical = 8.dp)) {
                SelectionContainer {
                    Text(annotated, fontFamily = Mono, fontSize = 12.5.sp, lineHeight = 17.sp, color = Mocha.text)
                }
            }
        }
    }
}

private fun linkify(text: String): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (m in urlRegex.findAll(text)) {
        append(text, last, m.range.first)
        pushLink(
            LinkAnnotation.Url(
                m.value,
                TextLinkStyles(SpanStyle(color = Mocha.mauve, textDecoration = TextDecoration.Underline)),
            ),
        )
        append(m.value)
        pop()
        last = m.range.last + 1
    }
    append(text, last, text.length)
}
