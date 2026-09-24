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

/** Pick what to run on the PC: each tool is its own lasting terminal. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LauncherSheet(host: Host, onPick: (Tool) -> Unit, onEdit: () -> Unit, onDismiss: () -> Unit) {
    val open by App.instance.sessions.open.collectAsState()
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
