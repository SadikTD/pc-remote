package com.pcremote.home

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pcremote.R
import com.pcremote.agent.AgentConnection
import com.pcremote.modes.rememberUnlocker
import com.pcremote.nav.Mode
import com.pcremote.ui.components.*
import com.pcremote.ui.theme.LocalMidnight
import com.pcremote.ui.theme.MidnightShapes
import com.pcremote.ui.theme.rememberHaptics

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

/** One line under each mode: what you do with it. */
fun Mode.hint(): Int = when (this) {
    Mode.SCREEN -> R.string.pcr_hint_screen
    Mode.REMOTE -> R.string.pcr_hint_remote
    Mode.MEDIA -> R.string.pcr_hint_media
    Mode.SLIDES -> R.string.pcr_hint_slides
    Mode.PAD -> R.string.pcr_hint_pad
    Mode.FILES -> R.string.pcr_hint_files
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
    val connection: (PcCardUi) -> AgentConnection? = { null },
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(cards: List<PcCardUi>, thumbs: Map<String, ImageBitmap>, implemented: Set<Mode>, a: HomeActions) {
    val c = LocalMidnight.current
    var sheetFor by remember { mutableStateOf<PcCardUi?>(null) }
    val pager = rememberPagerState { cards.size }
    val shown = cards.getOrNull(pager.currentPage)

    Box(Modifier.fillMaxSize().background(c.bg)) {
        Aurora(screen = shown?.let { thumbs[it.id] }?.takeIf { shown.online })
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(20.dp, 12.dp, 16.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.pcr_app_name), color = c.text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                GlassIconButton(Icons.Rounded.Add, stringResource(R.string.pcr_add_pc), a.addPc)
                Spacer(Modifier.width(10.dp))
                GlassIconButton(Icons.Rounded.Settings, stringResource(R.string.pcr_settings), a.settings)
            }
            if (cards.isEmpty()) {
                EmptyState(a.addPc)
            } else {
                if (cards.size > 1) Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) { PagerDots(cards.size, pager.currentPage) }
                HorizontalPager(pager, Modifier.fillMaxSize(), key = { cards[it].id }, pageSpacing = 16.dp) { i ->
                    val card = cards[i]
                    PcPage(card, thumbs[card.id], implemented, a, onMore = { sheetFor = card })
                }
            }
        }
    }
    sheetFor?.let { card -> PcSheet(card, a, onDismiss = { sheetFor = null }) }
}

@Composable
private fun EmptyState(addPc: () -> Unit) {
    val c = LocalMidnight.current
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Monitor(null, online = false, Modifier.fillMaxWidth())
        Spacer(Modifier.height(32.dp))
        Text(stringResource(R.string.pcr_no_pcs_title), color = c.text, style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.pcr_no_pcs_body), color = c.textDim, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(28.dp))
        PrimaryButton(stringResource(R.string.pcr_ob_scan), addPc, icon = Icons.Rounded.QrCodeScanner, modifier = Modifier.fillMaxWidth())
    }
}

/** The PC's screen in a glass bezel: the live thumbnail, or a dark panel while it's away. */
@Composable
private fun Monitor(thumb: ImageBitmap?, online: Boolean, modifier: Modifier, badge: @Composable BoxScope.() -> Unit = {}) {
    val c = LocalMidnight.current
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier.aspectRatio(16f / 9f).clip(shape).background(Color.Black)
            .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.04f))), shape),
    ) {
        AnimatedContent(thumb, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "thumb") { t ->
            if (t != null) Image(t, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(c.accent.copy(alpha = 0.16f), Color.Black))), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.DesktopWindows, null, tint = c.textDim.copy(alpha = 0.5f), modifier = Modifier.size(52.dp))
            }
        }
        val dim by animateFloatAsState(if (online) 0f else 0.6f, label = "dim")
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)))
        // glass reflection along the top edge
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.45f).background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.07f), Color.Transparent))))
        badge()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PcPage(card: PcCardUi, thumb: ImageBitmap?, implemented: Set<Mode>, a: HomeActions, onMore: () -> Unit) {
    val c = LocalMidnight.current
    val haptics = rememberHaptics()
    val conn = a.connection(card)
    val unlock = rememberUnlocker(card.id, { m -> conn?.sendIfOnline(m) == true }, conn?.incoming)
    val modes = card.modes.filter { it in implemented }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp, 12.dp, 20.dp, 32.dp)) {
        Monitor(thumb, card.online && !card.locked,
            Modifier.fillMaxWidth().combinedClickable(onClick = { a.primary(card) }, onLongClick = { haptics.heavy(); onMore() })) {
            StatusChip(card, Modifier.align(Alignment.TopStart).padding(12.dp))
            if (card.locked) Icon(Icons.Rounded.Lock, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.align(Alignment.Center).size(40.dp))
        }
        Spacer(Modifier.height(22.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(card.name, color = c.text, style = MaterialTheme.typography.displaySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (card.os.isNotEmpty()) Text(card.os, color = c.textDim, style = MaterialTheme.typography.bodyMedium)
            }
            GlassIconButton(Icons.Rounded.MoreHoriz, stringResource(R.string.pcr_more), onMore)
        }
        Spacer(Modifier.height(20.dp))
        PrimaryRow(card, a, unlock)
        AnimatedVisibility(card.online && modes.isNotEmpty()) {
            Column {
                Spacer(Modifier.height(28.dp))
                modes.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { m -> ModeTile(m, Modifier.weight(1f)) { a.open(card, m) } }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusChip(card: PcCardUi, modifier: Modifier) {
    val c = LocalMidnight.current
    val (col, label) = when {
        card.locked -> c.warn to R.string.pcr_status_locked
        card.streaming -> c.accent to R.string.pcr_status_streaming
        card.online -> c.ok to R.string.pcr_status_online
        card.primary == PrimaryAction.CHECKING -> c.warn to R.string.pcr_checking
        else -> c.textDim to R.string.pcr_status_offline
    }
    Row(modifier.clip(MidnightShapes.pill).background(Color.Black.copy(alpha = 0.55f)).padding(start = 4.dp, end = 12.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        StatusDot(col, live = card.online, size = 7.dp)
        Text(stringResource(label), color = Color.White, style = MaterialTheme.typography.labelMedium)
    }
}

/** The one thing to do next, chosen from the PC's state. */
@Composable
private fun PrimaryRow(card: PcCardUi, a: HomeActions, unlock: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        when {
            card.locked && card.canUnlock -> {
                PrimaryButton(stringResource(R.string.pcr_unlock_fp), unlock, Modifier.weight(1f), icon = Icons.Rounded.Fingerprint)
                if (Mode.SCREEN in card.modes) GhostButton(stringResource(R.string.pcr_mode_screen), { a.open(card, Mode.SCREEN) }, icon = Icons.Rounded.Monitor)
            }
            card.primary == PrimaryAction.OPEN -> PrimaryButton(stringResource(R.string.pcr_open_screen), { a.primary(card) }, Modifier.weight(1f), icon = Icons.Rounded.PlayArrow)
            card.primary == PrimaryAction.WAKE -> PrimaryButton(stringResource(R.string.pcr_wake), { a.primary(card) }, Modifier.weight(1f), icon = Icons.Rounded.PowerSettingsNew)
            card.primary == PrimaryAction.OFFLINE_HELP -> GhostButton(stringResource(R.string.pcr_help), { a.primary(card) }, Modifier.weight(1f), icon = Icons.AutoMirrored.Rounded.HelpOutline)
            else -> PrimaryButton(stringResource(R.string.pcr_checking), {}, Modifier.weight(1f), enabled = false)
        }
    }
}

@Composable
private fun ModeTile(m: Mode, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalMidnight.current
    val haptics = rememberHaptics()
    Column(modifier.glass(MidnightShapes.control).clickable { haptics.tick(); onClick() }.padding(16.dp)) {
        Box(Modifier.size(38.dp).clip(MidnightShapes.small).background(c.accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(m.icon(), null, tint = c.accent, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text(stringResource(m.label()), color = c.text, style = MaterialTheme.typography.titleMedium)
        Text(stringResource(m.hint()), color = c.textDim, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                if (card.canPower) SheetRow(Icons.Rounded.PowerSettingsNew, R.string.pcr_power) { power = true }
                if (card.primary != PrimaryAction.OPEN) SheetRow(Icons.Rounded.Bolt, R.string.pcr_wake) { a.primary(card.copy(primary = PrimaryAction.WAKE)); onDismiss() }
                SheetRow(Icons.Rounded.Edit, R.string.pcr_rename) { renaming = true }
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
