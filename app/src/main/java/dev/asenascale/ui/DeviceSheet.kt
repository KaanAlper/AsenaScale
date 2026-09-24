package dev.asenascale.ui

import androidx.compose.foundation.clickable
import dev.asenascale.R
import androidx.compose.ui.res.stringResource
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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.asenascale.App
import dev.asenascale.tailnet.Peer
import java.time.Duration
import java.time.Instant

/** Everything about one tailnet device: addresses, link, traffic. Tap a value to copy it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceSheet(peer: Peer, saved: Boolean, onAction: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(if (peer.online) Pal.green else Pal.overlay0)
                Spacer(Modifier.width(10.dp))
                Text(peer.shortName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    if (peer.online) stringResource(R.string.online) else lastSeen(peer.lastSeen),
                    fontSize = 13.sp,
                    color = if (peer.online) Pal.green else Pal.overlay0,
                )
            }
            Spacer(Modifier.height(12.dp))
            Field("IPv4", peer.ipv4)
            Field("IPv6", peer.ipv6)
            Field("MagicDNS", peer.dnsName.ifEmpty { null })
            Field(stringResource(R.string.device_system), peer.os.ifEmpty { null }, copy = false)
            Field(
                stringResource(R.string.device_link),
                when {
                    !peer.online -> null
                    peer.direct.isNotEmpty() -> stringResource(R.string.link_direct, peer.direct)
                    peer.relay.isNotEmpty() -> stringResource(R.string.link_relay, peer.relay)
                    else -> stringResource(R.string.link_idle)
                },
                copy = false,
            )
            if (peer.rxBytes > 0 || peer.txBytes > 0) {
                Field(stringResource(R.string.device_data), stringResource(R.string.data_rx_tx, bytes(peer.rxBytes), bytes(peer.txBytes)), copy = false)
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = onAction, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Text(if (saved) stringResource(R.string.open_terminal) else stringResource(R.string.add_as_computer))
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun Field(label: String, value: String?, copy: Boolean = true) {
    if (value == null) return
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = copy) { App.instance.copyToClipboard(value) }
            .padding(vertical = 9.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 13.sp, color = Pal.overlay0, modifier = Modifier.width(96.dp))
        Text(value, style = MonoSmall.copy(fontSize = 13.sp), modifier = Modifier.weight(1f))
        if (copy) Icon(AsIcons.Copy, stringResource(R.string.copy), tint = Pal.overlay0, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun lastSeen(iso: String): String {
    val t = runCatching { Instant.parse(iso) }.getOrNull() ?: return stringResource(R.string.offline)
    val d = Duration.between(t, Instant.now())
    return when {
        d.toMinutes() < 1 -> stringResource(R.string.seen_just_now)
        d.toHours() < 1 -> stringResource(R.string.seen_minutes, d.toMinutes().toInt())
        d.toDays() < 1 -> stringResource(R.string.seen_hours, d.toHours().toInt())
        else -> stringResource(R.string.seen_days, d.toDays().toInt())
    }
}

private fun bytes(n: Long): String = when {
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> "%.1f KB".format(n / 1024.0)
    n < 1024L * 1024 * 1024 -> "%.1f MB".format(n / 1024.0 / 1024)
    else -> "%.2f GB".format(n / 1024.0 / 1024 / 1024)
}
