package com.pcremote.home

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.limelight.R
import com.pcremote.nav.Mode
import com.pcremote.ui.components.*
import com.pcremote.ui.theme.LocalMidnight
import com.pcremote.ui.theme.MidnightShapes
import com.pcremote.ui.theme.rememberHaptics
import java.util.Calendar

fun Mode.icon(): ImageVector = when (this) {
    Mode.SCREEN -> Icons.Rounded.Monitor
    Mode.REMOTE -> Icons.Rounded.TouchApp
    Mode.MEDIA -> Icons.Rounded.MusicNote
    Mode.SLIDES -> Icons.Rounded.Slideshow
    Mode.PAD -> Icons.Rounded.SportsEsports
    Mode.FILES -> Icons.Rounded.Folder
}

fun Mode.label(): Int = when (this) {
    Mode.SCREEN -> R.string.pcr_mode_screen
    Mode.REMOTE -> R.string.pcr_mode_remote
    Mode.MEDIA -> R.string.pcr_mode_media
    Mode.SLIDES -> R.string.pcr_mode_slides
    Mode.PAD -> R.string.pcr_mode_pad
    Mode.FILES -> R.string.pcr_mode_files
}

class HomeActions(
    val open: (PcCardUi, Mode) -> Unit,
    val primary: (PcCardUi) -> Unit,
    val addPc: () -> Unit,
    val settings: () -> Unit,
    val refresh: () -> Unit,
    val rename: (PcCardUi, String) -> Unit,
    val remove: (PcCardUi) -> Unit,
    val send: (PcCardUi, com.pcremote.agent.Msg) -> Unit = { _, _ -> },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(cards: List<PcCardUi>, thumbs: Map<String, ImageBitmap>, implemented: Set<Mode>, a: HomeActions) {
    val c = LocalMidnight.current
    var sheetFor by remember { mutableStateOf<PcCardUi?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(refreshing) { if (refreshing) { a.refresh(); kotlinx.coroutines.delay(900); refreshing = false } }

    Box(Modifier.fillMaxSize().background(c.bg)) {
        Aurora(intensity = 0.55f)
        PullToRefreshBox(refreshing, { refreshing = true }, Modifier.fillMaxSize().safeDrawingPadding()) {
            LazyColumn(contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 120.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(greeting()), color = c.textDim, style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.pcr_pc_list_title), color = c.text, style = MaterialTheme.typography.displaySmall)
                        }
                        IconButton(onClick = a.settings) { Icon(Icons.Rounded.Settings, stringResource(R.string.pcr_settings), tint = c.text) }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                if (cards.isEmpty()) item { EmptyState(a.addPc) }
                items(cards, key = { it.id }) { card ->
                    PcCard(card, thumbs[card.id], implemented, a, onLongPress = { sheetFor = card }, Modifier.animateItem())
                }
            }
        }
        if (cards.isNotEmpty()) {
            Box(Modifier.align(Alignment.BottomEnd).safeDrawingPadding().padding(24.dp)) {
                PrimaryButton(stringResource(R.string.pcr_add_pc), a.addPc, icon = Icons.Rounded.Add)
            }
        }
    }
    sheetFor?.let { card -> PcSheet(card, a, onDismiss = { sheetFor = null }) }
}

private fun greeting(): Int = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> R.string.pcr_morning
    in 12..17 -> R.string.pcr_afternoon
    else -> R.string.pcr_evening
}

@Composable
private fun EmptyState(addPc: () -> Unit) {
    val c = LocalMidnight.current
    GlassCard(Modifier.fillMaxWidth()) {
        Icon(Icons.Rounded.Devices, null, tint = c.accent, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.pcr_no_pcs_title), color = c.text, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.pcr_no_pcs_body), color = c.textDim, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(20.dp))
        PrimaryButton(stringResource(R.string.pcr_ob_scan), addPc, icon = Icons.Rounded.QrCodeScanner)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PcCard(card: PcCardUi, thumb: ImageBitmap?, implemented: Set<Mode>, a: HomeActions, onLongPress: () -> Unit, modifier: Modifier) {
    val c = LocalMidnight.current
    val haptics = rememberHaptics()
    val modes = card.modes.filter { it in implemented }
    Column(
        modifier.fillMaxWidth().clip(MidnightShapes.card).background(c.surface.copy(alpha = 0.92f))
            .combinedClickable(onClick = { a.primary(card) }, onLongClick = { haptics.heavy(); onLongPress() }),
    ) {
        // Preview: live thumbnail or a branded gradient
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(24.dp, 24.dp, 0.dp, 0.dp))) {
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(c.surfaceHigh, c.accent.copy(alpha = 0.18f)))))
            AnimatedContent(thumb, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "thumb") { t ->
                if (t != null) Image(t, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.DesktopWindows, null, tint = c.textDim.copy(alpha = 0.6f), modifier = Modifier.size(56.dp))
                }
            }
            val dim by animateFloatAsState(if (card.online) 0f else 0.55f, label = "dim")
            Box(Modifier.fillMaxSize().background(c.bg.copy(alpha = dim)))
        }
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(card.name, color = c.text, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val (col, label) = when {
                            card.streaming -> c.accent2 to R.string.pcr_status_streaming
                            card.online -> c.ok to R.string.pcr_status_online
                            card.primary == PrimaryAction.CHECKING -> c.warn to R.string.pcr_checking
                            else -> c.textDim to R.string.pcr_status_offline
                        }
                        StatusDot(col, live = card.online, size = 8.dp)
                        Spacer(Modifier.width(2.dp))
                        Text(stringResource(label), color = c.textDim, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                val (label, icon) = when (card.primary) {
                    PrimaryAction.OPEN -> R.string.pcr_open to Icons.Rounded.PlayArrow
                    PrimaryAction.WAKE -> R.string.pcr_wake to Icons.Rounded.PowerSettingsNew
                    PrimaryAction.PAIR -> R.string.pcr_pair to Icons.Rounded.Link
                    PrimaryAction.OFFLINE_HELP -> R.string.pcr_help to Icons.Rounded.HelpOutline
                    PrimaryAction.CHECKING -> R.string.pcr_checking to Icons.Rounded.HourglassEmpty
                }
                PrimaryButton(stringResource(label), { a.primary(card) }, icon = icon, enabled = card.primary != PrimaryAction.CHECKING)
            }
            if (modes.isNotEmpty() && card.online) {
                Spacer(Modifier.height(16.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    modes.forEach { m -> ModeChip(m) { a.open(card, m) } }
                }
            }
        }
    }
}

@Composable
private fun ModeChip(m: Mode, onClick: () -> Unit) {
    val c = LocalMidnight.current
    val haptics = rememberHaptics()
    Row(
        Modifier.clip(MidnightShapes.pill).background(c.surfaceHigh).clickable { haptics.tick(); onClick() }
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(m.icon(), null, tint = c.accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(m.label()), color = c.text, style = MaterialTheme.typography.labelLarge)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PcSheet(card: PcCardUi, a: HomeActions, onDismiss: () -> Unit) {
    val c = LocalMidnight.current
    var renaming by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(card.name) }
    var confirmRemove by remember { mutableStateOf(false) }
    var power by remember { mutableStateOf(false) }
    if (power) { com.pcremote.modes.PowerSheet(card.name, { a.send(card, it) }) { power = false; onDismiss() }; return }
    ModalBottomSheet(onDismiss, containerColor = c.surface) {
        Column(Modifier.padding(24.dp, 0.dp, 24.dp, 32.dp)) {
            Text(card.name, color = c.text, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            if (renaming) {
                OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                PrimaryButton(stringResource(R.string.pcr_save), { a.rename(card, name.trim().ifEmpty { card.name }); onDismiss() })
            } else {
                if (!card.legacy && card.online) SheetRow(Icons.Rounded.PowerSettingsNew, R.string.pcr_power) { power = true }
                if (!card.legacy) SheetRow(Icons.Rounded.Edit, R.string.pcr_rename) { renaming = true }
                SheetRow(Icons.Rounded.DeleteOutline, if (confirmRemove) R.string.pcr_remove_confirm else R.string.pcr_remove, danger = true) {
                    if (confirmRemove) { a.remove(card); onDismiss() } else confirmRemove = true
                }
            }
        }
    }
}

@Composable
private fun SheetRow(icon: ImageVector, label: Int, danger: Boolean = false, onClick: () -> Unit) {
    val c = LocalMidnight.current
    Row(
        Modifier.fillMaxWidth().clip(MidnightShapes.control).clickable(onClick = onClick).padding(vertical = 14.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (danger) c.bad else c.text)
        Spacer(Modifier.width(16.dp))
        Text(stringResource(label), color = if (danger) c.bad else c.text, style = MaterialTheme.typography.bodyLarge)
    }
}
