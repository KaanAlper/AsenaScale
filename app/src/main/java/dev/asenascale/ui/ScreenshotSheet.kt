package dev.asenascale.ui

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.asenascale.shot.ShotList
import dev.asenascale.shot.ShotTarget
import dev.asenascale.shot.Screenshots
import dev.asenascale.ssh.SshConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenshotSheet(conn: SshConnection, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<ShotList?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<ShotTarget?>(null) }
    var shown by remember { mutableStateOf<Pair<File, ImageBitmap>?>(null) }
    var last by remember { mutableStateOf<ShotTarget?>(null) }

    LaunchedEffect(Unit) {
        runCatching { withContext(Dispatchers.IO) { Screenshots.list(context, conn) } }
            .onSuccess { list = it }
            .onFailure { error = it.message }
    }

    fun capture(t: ShotTarget) {
        if (busy != null) return
        busy = t
        last = t
        error = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val f = Screenshots.take(context, conn, t)
                    f to BitmapFactory.decodeFile(f.absolutePath).asImageBitmap()
                }
            }.onSuccess { shown = it }.onFailure { error = it.message }
            busy = null
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)) {
                Text("Ekran görüntüsü", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                list?.desktop?.takeIf { it.isNotEmpty() }?.let {
                    Text(it, style = MonoSmall, color = Pal.overlay0)
                }
            }
            error?.let {
                Text(
                    it,
                    fontSize = 13.sp,
                    color = Pal.red,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Pal.red.copy(alpha = 0.08f))
                        .padding(12.dp),
                )
                Spacer(Modifier.height(8.dp))
            }
            val l = list
            if (l == null && error == null) {
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = Pal.mauve)
                }
            }
            if (l != null) {
                LazyColumn(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(l.targets, key = { it.kind + it.id }) { t ->
                        TargetRow(t, busy = busy == t, onClick = { capture(t) })
                    }
                    if (l.targets.none { it.kind == "window" }) {
                        item {
                            Text(
                                "Pencere listesi bu masaüstünde alınamadı; tüm ekran veya aktif pencere kullanılabilir.",
                                fontSize = 12.sp,
                                color = Pal.overlay0,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    shown?.let { (file, bmp) ->
        ShotViewer(
            bitmap = bmp,
            busy = busy != null,
            onClose = { shown = null },
            onRetake = { last?.let { capture(it) } },
            onSave = {
                runCatching { Screenshots.saveToGallery(context, file) }
                    .onSuccess { Toast.makeText(context, "Galeriye kaydedildi", Toast.LENGTH_SHORT).show() }
                    .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_SHORT).show() }
            },
            onShare = { Screenshots.share(context, file) },
        )
    }
}

@Composable
private fun TargetRow(t: ShotTarget, busy: Boolean, onClick: () -> Unit) {
    val icon: ImageVector = when (t.kind) {
        "screen", "output" -> AsIcons.Monitor
        "active" -> AsIcons.Focus
        else -> AsIcons.Window
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (t.kind == "window") Pal.subtext else Pal.mauve, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Text(t.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), fontSize = 15.sp)
        if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Pal.mauve)
    }
}

@Composable
private fun ShotViewer(
    bitmap: ImageBitmap,
    busy: Boolean,
    onClose: () -> Unit,
    onRetake: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
) {
    var scale by remember(bitmap) { mutableFloatStateOf(1f) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Pal.crust)) {
            Image(
                bitmap = bitmap,
                contentDescription = "Bilgisayar ekranı",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(bitmap) {
                        detectTapGestures(onDoubleTap = { tap ->
                            if (scale > 1f) {
                                scale = 1f; offset = Offset.Zero
                            } else {
                                scale = 2.5f
                                offset = (Offset(size.width / 2f, size.height / 2f) - tap) * 2.5f
                            }
                        })
                    }
                    .pointerInput(bitmap) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 8f)
                            offset = if (scale == 1f) Offset.Zero else offset + pan
                        }
                    }
                    .graphicsLayer {
                        scaleX = scale; scaleY = scale
                        translationX = offset.x; translationY = offset.y
                    },
            )
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundIcon(AsIcons.Close, "Kapat", onClose)
                Spacer(Modifier.weight(1f))
                if (busy) {
                    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Pal.mauve)
                    }
                } else {
                    RoundIcon(AsIcons.Refresh, "Yenile", onRetake)
                }
                Spacer(Modifier.width(8.dp))
                RoundIcon(AsIcons.Download, "Kaydet", onSave)
                Spacer(Modifier.width(8.dp))
                RoundIcon(AsIcons.Share, "Paylaş", onShare)
            }
        }
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, desc: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(Pal.base.copy(alpha = 0.7f)),
    ) { Icon(icon, desc, tint = Pal.text) }
}
