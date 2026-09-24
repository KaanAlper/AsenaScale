package dev.asenascale.ui

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
                    if (peer.online) "çevrimiçi" else lastSeen(peer.lastSeen),
                    fontSize = 13.sp,
                    color = if (peer.online) Pal.green else Pal.overlay0,
                )
            }
            Spacer(Modifier.height(12.dp))
            Field("IPv4", peer.ipv4)
            Field("IPv6", peer.ipv6)
            Field("MagicDNS", peer.dnsName.ifEmpty { null })
            Field("Sistem", peer.os.ifEmpty { null }, copy = false)
            Field(
                "Bağlantı",
                when {
                    !peer.online -> null
                    peer.direct.isNotEmpty() -> "Doğrudan  ${peer.direct}"
                    peer.relay.isNotEmpty() -> "Röle üzerinden (${peer.relay})"
                    else -> "Henüz trafik yok"
                },
                copy = false,
            )
            if (peer.rxBytes > 0 || peer.txBytes > 0) {
                Field("Veri", "alınan ${bytes(peer.rxBytes)}  ·  gönderilen ${bytes(peer.txBytes)}", copy = false)
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = onAction, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Text(if (saved) "Terminal aç" else "Bilgisayar olarak ekle")
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
        if (copy) Icon(AsIcons.Copy, "Kopyala", tint = Pal.overlay0, modifier = Modifier.size(14.dp))
    }
}

private fun lastSeen(iso: String): String {
    val t = runCatching { Instant.parse(iso) }.getOrNull() ?: return "çevrimdışı"
    val d = Duration.between(t, Instant.now())
    return when {
        d.toMinutes() < 1 -> "az önce görüldü"
        d.toHours() < 1 -> "${d.toMinutes()} dk önce görüldü"
        d.toDays() < 1 -> "${d.toHours()} sa önce görüldü"
        else -> "${d.toDays()} gün önce görüldü"
    }
}

private fun bytes(n: Long): String = when {
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> "%.1f KB".format(n / 1024.0)
    n < 1024L * 1024 * 1024 -> "%.1f MB".format(n / 1024.0 / 1024)
    else -> "%.2f GB".format(n / 1024.0 / 1024 / 1024)
}
