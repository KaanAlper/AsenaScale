package dev.asenascale.ui

import android.content.Intent
import android.text.format.DateFormat
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.asenascale.App
import dev.asenascale.R
import dev.asenascale.files.PcEntry
import dev.asenascale.files.PcFiles
import dev.asenascale.files.PcListing
import dev.asenascale.files.Transfers
import dev.asenascale.ssh.SshConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The PC's files, browsed from the phone. Tap a folder to open it; a file
 * offers download to the phone, its path for the terminal (for Claude), or
 * a copy of the path. [pick] mode: tapping a file hands its path back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesSheet(conn: SshConnection, pick: Boolean, onInsertPath: (String) -> Unit, onDismiss: () -> Unit) {
    val app = App.instance
    val context = LocalContext.current
    val host = conn.host
    var path by rememberSaveable { mutableStateOf(app.lastPcFolder[host.id].orEmpty()) }
    var listing by remember { mutableStateOf<PcListing?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(path, reload) {
        error = null
        runCatching { withContext(Dispatchers.IO) { PcFiles.list(conn, path) } }
            .onSuccess {
                listing = it
                app.lastPcFolder[host.id] = it.path
            }
            .onFailure { error = it.message }
    }
    val here = listing?.path.orEmpty()
    fun open(p: String) {
        listing = null
        path = p
    }
    BackHandler(enabled = here.isNotEmpty()) { open(PcFiles.parent(here)) }

    val pickUpload = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        for (u in uris) app.transfers.upload(host, u, here, onDone = { reload++ })
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.fillMaxHeight(0.92f).navigationBarsPadding()) {
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { open(PcFiles.parent(here)) }, enabled = here.isNotEmpty()) {
                    Icon(AsIcons.Back, stringResource(R.string.back), tint = if (here.isNotEmpty()) Pal.subtext else Pal.surface1)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        if (here.isEmpty()) stringResource(R.string.files_title) else PcFiles.name(here),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (here.isNotEmpty()) {
                        Text(here, style = MonoSmall, color = Pal.overlay0, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (here.isNotEmpty()) {
                    IconButton(onClick = { pickUpload.launch("*/*") }) {
                        Icon(AsIcons.Upload, stringResource(R.string.upload_here), tint = Pal.subtext)
                    }
                }
                IconButton(onClick = { reload++ }) { Icon(AsIcons.Refresh, stringResource(R.string.refresh), tint = Pal.subtext) }
            }

            TransferList(hostId = host.id)

            error?.let {
                Text(
                    it,
                    fontSize = 13.sp,
                    color = Pal.red,
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Pal.red.copy(alpha = 0.08f))
                        .padding(12.dp),
                )
            }
            val l = listing
            if (l == null && error == null) {
                Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = Pal.mauve)
                }
            }
            if (l != null) {
                if (l.entries.isEmpty()) {
                    Text(
                        stringResource(R.string.empty_folder),
                        color = Pal.overlay0,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(24.dp),
                    )
                }
                LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp)) {
                    items(l.entries, key = { it.path }) { e ->
                        EntryRow(
                            e,
                            place = l.path.isEmpty(),
                            pick = pick,
                            onOpen = { open(e.path) },
                            onPick = { onInsertPath(e.path); onDismiss() },
                            onDownload = {
                                app.transfers.download(host, e)
                                Toast.makeText(context, context.getString(R.string.downloading, e.name), Toast.LENGTH_SHORT).show()
                            },
                            onInsert = { onInsertPath(e.path); onDismiss() },
                            onCopy = {
                                app.copyToClipboard(e.path)
                                Toast.makeText(context, context.getString(R.string.copied), Toast.LENGTH_SHORT).show()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryRow(
    e: PcEntry,
    place: Boolean,
    pick: Boolean,
    onOpen: () -> Unit,
    onPick: () -> Unit,
    onDownload: () -> Unit,
    onInsert: () -> Unit,
    onCopy: () -> Unit,
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable {
                    when {
                        e.dir -> onOpen()
                        pick -> onPick()
                        else -> menu = true
                    }
                }
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                when {
                    place -> if (e.name.length <= 3) AsIcons.Monitor else AsIcons.Folder
                    e.dir -> AsIcons.Folder
                    else -> AsIcons.File
                },
                null,
                tint = if (e.dir) Pal.mauve else Pal.subtext,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (place) PcFiles.name(e.path) else e.name,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val sub = when {
                    place -> e.path
                    e.dir -> date(context, e.mtime)
                    else -> Formatter.formatShortFileSize(context, e.size) + "  ·  " + date(context, e.mtime)
                }
                if (sub.isNotEmpty()) Text(sub, style = MonoSmall, color = Pal.overlay0, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (e.dir) Icon(AsIcons.Chevron, null, tint = Pal.overlay0, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(
            expanded = menu,
            onDismissRequest = { menu = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.download_to_phone)) },
                leadingIcon = { Icon(AsIcons.Download, null) },
                onClick = { menu = false; onDownload() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.insert_path)) },
                leadingIcon = { Icon(AsIcons.Terminal, null) },
                onClick = { menu = false; onInsert() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.copy_path)) },
                leadingIcon = { Icon(AsIcons.Copy, null) },
                onClick = { menu = false; onCopy() },
            )
        }
    }
}

private fun date(context: android.content.Context, secs: Long): String =
    if (secs <= 0) "" else DateFormat.getMediumDateFormat(context).format(java.util.Date(secs * 1000))

/** Transfers to and from this PC: progress, speed, cancel; finished downloads open on tap. */
@Composable
fun TransferList(hostId: String, max: Int = 4) {
    val app = App.instance
    val all by app.transfers.list.collectAsState()
    val items = all.filter { it.hostId == hostId }.take(max)
    if (items.isEmpty()) return
    // Speeds and percentages tick while something runs.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(items.any { it.status.value == Transfers.Status.Running }) {
        while (true) {
            delay(500)
            tick++
        }
    }
    Column(
        Modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Pal.mantle)
            .padding(vertical = 6.dp)
            .heightIn(max = 260.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.transfers), style = MonoSmall, color = Pal.overlay0, modifier = Modifier.weight(1f))
            if (items.any { it.status.value != Transfers.Status.Running }) {
                TextButton(onClick = { app.transfers.clearFinished() }) {
                    Text(stringResource(R.string.clear), fontSize = 12.sp, color = Pal.subtext)
                }
            }
        }
        for (t in items) TransferRow(t, tick)
    }
}

@Composable
private fun TransferRow(t: Transfers.Transfer, tick: Int) {
    val app = App.instance
    val context = LocalContext.current
    val done by t.done.collectAsState()
    val status by t.status.collectAsState()
    val fraction = if (t.total > 0) (done.toFloat() / t.total).coerceIn(0f, 1f) else 0f
    val now = remember(tick) { System.currentTimeMillis() }
    val secs = ((now - t.startedAt) / 1000.0).coerceAtLeast(0.5)
    val openable = !t.upload && status is Transfers.Status.Done && t.localUri != null
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = openable) {
                val intent = Intent(Intent.ACTION_VIEW).setDataAndType(t.localUri, context.contentResolver.getType(t.localUri!!))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(Intent.createChooser(intent, t.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (t.upload) AsIcons.Upload else AsIcons.Download, null, tint = Pal.subtext, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(t.name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val line = when (val s = status) {
                    Transfers.Status.Running ->
                        "${(fraction * 100).toInt()}%  ·  " +
                            Formatter.formatShortFileSize(context, done) + " / " + Formatter.formatShortFileSize(context, t.total) +
                            "  ·  " + Formatter.formatShortFileSize(context, (done / secs).toLong()) + "/s"
                    is Transfers.Status.Done -> if (t.upload) s.result else stringResource(R.string.saved_to, s.result)
                    is Transfers.Status.Failed -> stringResource(R.string.transfer_failed, s.message)
                    Transfers.Status.Cancelled -> stringResource(R.string.cancelled)
                }
                Text(
                    line,
                    style = MonoSmall,
                    color = if (status is Transfers.Status.Failed) Pal.red else Pal.overlay0,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (status == Transfers.Status.Running) {
                IconButton(onClick = { app.transfers.cancel(t) }, modifier = Modifier.size(36.dp)) {
                    Icon(AsIcons.Close, stringResource(R.string.cancel), tint = Pal.subtext, modifier = Modifier.size(16.dp))
                }
            }
        }
        if (status == Transfers.Status.Running) {
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().padding(end = 10.dp).height(3.dp).clip(RoundedCornerShape(2.dp)),
                color = Pal.mauve,
                trackColor = Pal.surface0,
                drawStopIndicator = {},
            )
        }
    }
}

/** One slim line under the terminal's top bar while files move. */
@Composable
fun TransferBar(hostId: String) {
    val app = App.instance
    val context = LocalContext.current
    val all by app.transfers.list.collectAsState()
    val running = all.filter { it.hostId == hostId && it.status.value == Transfers.Status.Running }
    if (running.isEmpty()) return
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            tick++
        }
    }
    val now = remember(tick) { System.currentTimeMillis() }
    val total = running.sumOf { it.total }
    val done = running.sumOf { it.done.value }
    val secs = ((now - running.minOf { it.startedAt }) / 1000.0).coerceAtLeast(0.5)
    val fraction = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
    Column(Modifier.fillMaxWidth()) {
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = Pal.mauve,
            trackColor = Pal.surface0,
            drawStopIndicator = {},
        )
        Text(
            (if (running.size == 1) running[0].name else context.getString(R.string.n_files, running.size)) +
                "  ·  ${(fraction * 100).toInt()}%  ·  " + Formatter.formatShortFileSize(context, (done / secs).toLong()) + "/s",
            fontSize = 12.sp,
            color = Pal.subtext,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}
