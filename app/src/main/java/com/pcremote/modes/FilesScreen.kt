package com.pcremote.modes

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.limelight.R
import com.pcremote.ui.theme.LocalMidnight
import com.pcremote.ui.theme.MidnightShapes

@Composable
fun FilesScreen(pcId: String, onBack: () -> Unit, onPower: () -> Unit, initial: List<Uri> = emptyList()) {
    val session = rememberAgentSession(pcId)
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember(session) { session?.let { FilesController(ctx, it.conn, scope) } }
    LaunchedEffect(controller) { if (initial.isNotEmpty()) controller?.send(initial) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> controller?.send(uris) }
    ModeScaffold(R.string.pcr_mode_files, session, onBack, onPower) {
        FilesBody(controller?.transfers ?: emptyList()) { picker.launch(arrayOf("*/*")) }
    }
}

@Composable
fun FilesBody(transfers: List<Transfer>, onPick: () -> Unit) {
    val c = LocalMidnight.current
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(
                Modifier.fillMaxWidth().clip(MidnightShapes.card)
                    .background(Brush.linearGradient(listOf(c.accent.copy(alpha = 0.22f), c.accent2.copy(alpha = 0.12f))))
                    .border(1.dp, c.border, MidnightShapes.card).clickable(onClick = onPick).padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Rounded.UploadFile, null, tint = c.text, modifier = Modifier.size(44.dp))
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.pcr_files_send), color = c.text, style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.pcr_files_send_desc), color = c.textDim, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (transfers.isNotEmpty()) item {
            Text(stringResource(R.string.pcr_files_recent).uppercase(), color = c.textDim, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp))
        }
        items(transfers, key = { it.id }) { t -> TransferRow(t) }
    }
}

@Composable
private fun TransferRow(t: Transfer) {
    val c = LocalMidnight.current
    val p by animateFloatAsState(t.progress, label = "p")
    Row(
        Modifier.fillMaxWidth().clip(MidnightShapes.control).background(c.surface).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(progress = { p }, modifier = Modifier.size(40.dp), strokeWidth = 3.dp,
                color = when (t.status) { Transfer.Status.FAILED -> c.bad; Transfer.Status.DONE -> c.ok; else -> c.accent }, trackColor = c.surfaceHigh)
            Icon(
                when (t.status) {
                    Transfer.Status.DONE -> Icons.Rounded.Check
                    Transfer.Status.FAILED -> Icons.Rounded.ErrorOutline
                    else -> if (t.outgoing) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward
                }, null, tint = c.text, modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(t.name, color = c.text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stringResource(when (t.status) {
                Transfer.Status.DONE -> if (t.outgoing) R.string.pcr_files_sent else R.string.pcr_files_saved
                Transfer.Status.FAILED -> R.string.pcr_files_failed
                else -> if (t.outgoing) R.string.pcr_files_sending else R.string.pcr_files_receiving
            }), color = c.textDim, style = MaterialTheme.typography.bodySmall)
        }
    }
}
