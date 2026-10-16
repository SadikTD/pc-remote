package com.pcremote.modes

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pcremote.R
import com.pcremote.agent.FsEntry
import com.pcremote.ui.components.GhostButton
import com.pcremote.ui.components.PrimaryButton
import com.pcremote.ui.components.glass
import com.pcremote.ui.theme.LocalMidnight
import com.pcremote.ui.theme.MidnightShapes
import java.text.DateFormat
import java.util.Date

@Composable
fun FilesScreen(pcId: String, onBack: () -> Unit, showPower: Boolean = true, initial: List<Uri> = emptyList()) {
    val session = rememberAgentSession(pcId)
    val ctx = LocalContext.current
    val hub = remember(pcId) { TransferHub.get(ctx, pcId) }
    // Queue shared files once, even if the screen is recreated
    var queuedInitial by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(hub) { if (!queuedInitial && initial.isNotEmpty()) { hub.send(initial); queuedInitial = true } }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) hub.send(uris) }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(hub::sendTree) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    ModeScaffold(R.string.pcr_mode_files, session, onBack, showPower) { st ->
        val canBrowse = st is com.pcremote.agent.AgentState.Online && "fsbrowse" in st.caps
        if (canBrowse) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).glass(MidnightShapes.pill).padding(4.dp)) {
                listOf(R.string.pcr_files_tab_transfers, R.string.pcr_files_tab_browse).forEachIndexed { i, label ->
                    val c = LocalMidnight.current
                    Box(Modifier.weight(1f).clip(MidnightShapes.pill).background(if (tab == i) c.accent else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable { tab = i }.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(label), color = if (tab == i) c.onAccent else c.text, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
        if (tab == 1 && canBrowse) Browser(hub) else Transfers(hub, onFiles = { files.launch(arrayOf("*/*")) }, onFolder = { folder.launch(null) })
    }
}

@Composable
private fun Transfers(hub: TransferHub, onFiles: () -> Unit, onFolder: () -> Unit) {
    val c = LocalMidnight.current
    val list by hub.transfers.collectAsState()
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Column(Modifier.fillMaxWidth().glass().padding(22.dp)) {
                Icon(Icons.Rounded.SwapVert, null, tint = c.accent, modifier = Modifier.size(32.dp))
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.pcr_files_send), color = c.text, style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.pcr_files_send_desc), color = c.textDim, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton(stringResource(R.string.pcr_files_pick), onFiles, Modifier.weight(1f), icon = Icons.Rounded.UploadFile)
                    GhostButton(stringResource(R.string.pcr_files_pick_folder), onFolder, icon = Icons.Rounded.DriveFolderUpload)
                }
            }
        }
        if (list.isNotEmpty()) item {
            Row(Modifier.padding(start = 6.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.pcr_files_recent), color = c.textDim, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (list.any { it.finished }) TextButton(hub::clearFinished) { Text(stringResource(R.string.pcr_files_clear), color = c.accent) }
            }
        }
        items(list, key = { it.id }) { t -> TransferRow(t, hub) }
    }
}

@Composable
private fun TransferRow(t: Transfer, hub: TransferHub) {
    val c = LocalMidnight.current
    val p by animateFloatAsState(t.progress, label = "p")
    val color = when (t.status) {
        Transfer.Status.FAILED -> c.bad
        Transfer.Status.DONE -> c.ok
        Transfer.Status.PAUSED -> c.warn
        else -> c.accent
    }
    Column(Modifier.fillMaxWidth().glass(MidnightShapes.control).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).clip(MidnightShapes.small).background(color.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Icon(
                    when (t.status) {
                        Transfer.Status.DONE -> Icons.Rounded.Check
                        Transfer.Status.FAILED -> Icons.Rounded.ErrorOutline
                        else -> if (t.outgoing) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward
                    }, null, tint = color, modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t.name.substringAfterLast('/'), color = c.text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(detail(t), color = c.textDim, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            if (t.outgoing && t.status == Transfer.Status.ACTIVE) IconButton({ hub.pause(t.id) }) { Icon(Icons.Rounded.Pause, stringResource(R.string.pcr_files_pause), tint = c.text) }
            if (t.outgoing && (t.status == Transfer.Status.PAUSED || t.status == Transfer.Status.FAILED)) IconButton({ hub.resume(t.id) }) { Icon(Icons.Rounded.PlayArrow, stringResource(R.string.pcr_files_resume), tint = c.text) }
            if (!t.finished || t.status == Transfer.Status.FAILED) IconButton({ hub.cancel(t.id) }) { Icon(Icons.Rounded.Close, stringResource(R.string.pcr_cancel), tint = c.textDim) }
        }
        if (!t.finished) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(progress = { p }, Modifier.fillMaxWidth().height(4.dp).clip(MidnightShapes.pill), color = color, trackColor = c.surfaceHigh, drawStopIndicator = {})
        }
    }
}

@Composable
private fun detail(t: Transfer): String {
    val size = Format.bytes(t.size)
    return when (t.status) {
        Transfer.Status.ACTIVE -> buildString {
            append("${Format.bytes(t.done)} / $size")
            if (t.speed > 1) append("   ${Format.bytes(t.speed.toLong())}/s")
            t.eta?.let { append("   ${stringResource(R.string.pcr_files_left, Format.eta(it))}") }
        }
        Transfer.Status.QUEUED -> stringResource(R.string.pcr_files_waiting) + "   $size"
        Transfer.Status.PAUSED -> stringResource(R.string.pcr_files_paused) + "   ${Format.bytes(t.done)} / $size"
        Transfer.Status.DONE -> stringResource(if (t.outgoing) R.string.pcr_files_sent else R.string.pcr_files_saved) + "   $size"
        Transfer.Status.FAILED -> stringResource(R.string.pcr_files_failed)
    }
}

/** The PC's folders: tap to open, the arrow downloads (a folder comes down whole). */
@Composable
private fun Browser(hub: TransferHub) {
    val c = LocalMidnight.current
    var path by rememberSaveable { mutableStateOf("") }
    val stack = remember { mutableStateListOf<String>() }
    var entries by remember { mutableStateOf<List<FsEntry>?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(path) {
        entries = null; failed = false
        val r = hub.list(path)
        if (r == null) failed = true else entries = r
    }
    BackHandler(enabled = stack.isNotEmpty()) { path = stack.removeAt(stack.lastIndex) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (stack.isNotEmpty()) IconButton({ path = stack.removeAt(stack.lastIndex) }) { Icon(Icons.Rounded.ArrowUpward, stringResource(R.string.pcr_files_up), tint = c.text) }
            Text(if (path.isEmpty()) stringResource(R.string.pcr_files_this_pc) else path, color = c.text, style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = TextOverflow.StartEllipsis, modifier = Modifier.weight(1f).padding(start = 4.dp))
        }
        when {
            failed -> Text(stringResource(R.string.pcr_files_browse_failed), color = c.textDim, modifier = Modifier.padding(24.dp))
            entries == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = c.accent) }
            entries!!.isEmpty() -> Text(stringResource(R.string.pcr_files_empty), color = c.textDim, modifier = Modifier.padding(24.dp))
            else -> LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(entries!!, key = { it.path }) { e ->
                    Row(Modifier.fillMaxWidth().glass(MidnightShapes.control).clickable(enabled = e.dir) { stack.add(path); path = e.path }.padding(start = 14.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (e.dir) Icons.Rounded.Folder else Icons.AutoMirrored.Rounded.InsertDriveFile, null, tint = if (e.dir) c.accent else c.textDim)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.name, color = c.text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (!e.dir || e.mtime > 0) Text(listOfNotNull(e.size.takeIf { !e.dir }?.let(Format::bytes),
                                e.mtime.takeIf { it > 0 }?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it * 1000)) }).joinToString("   "),
                                color = c.textDim, style = MaterialTheme.typography.bodySmall)
                        }
                        if (path.isNotEmpty()) IconButton({ hub.download(e.path) }) { Icon(Icons.Rounded.Download, stringResource(R.string.pcr_files_download), tint = c.text) }
                    }
                }
            }
        }
    }
}
