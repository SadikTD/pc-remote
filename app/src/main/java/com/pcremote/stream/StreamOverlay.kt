package com.pcremote.stream

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardTab
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.limelight.R
import com.pcremote.ui.components.GhostButton
import com.pcremote.ui.components.PrimaryButton
import com.pcremote.ui.components.StatusDot
import com.pcremote.ui.theme.LocalMidnight
import com.pcremote.ui.theme.MidnightShapes
import com.pcremote.ui.theme.rememberHaptics

private const val VK_TAB = 0x09
private const val VK_ESC = 0x1B
private const val VK_LEFT = 0x25
private const val VK_UP = 0x26
private const val VK_RIGHT = 0x27
private const val VK_DOWN = 0x28
private const val VK_DELETE = 0x2E
private const val VK_HOME = 0x24
private const val VK_END = 0x23
private const val VK_PGUP = 0x21
private const val VK_PGDN = 0x22
private const val VK_F1 = 0x70

@Composable
private fun glass() = LocalMidnight.current.surface.copy(alpha = 0.86f)

@Composable
internal fun StreamOverlay(state: ToolbarState, a: ToolbarActions) {
    var panel by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(state.connecting != null, enter = fadeIn(), exit = fadeOut()) {
            Connecting(state.pcName, state.connecting.orEmpty(), a::disconnect)
        }
        if (!state.connected) return@Box
        StatsPill(state, Modifier.align(Alignment.TopStart).padding(12.dp))
        if (state.offerLowerQuality) LowerQualityChip(a, Modifier.align(Alignment.TopCenter).padding(top = 12.dp))
        AnimatedVisibility(state.keyboardOpen, Modifier.align(Alignment.TopCenter).padding(top = 56.dp),
            enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut()) {
            KeyboardBar(state, a)
        }
        EdgeHandle(Modifier.align(Alignment.CenterEnd)) { panel = true }
        // Scrim + side panel
        AnimatedVisibility(panel, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))
                .clickable(remember { MutableInteractionSource() }, null) { panel = false })
        }
        AnimatedVisibility(panel, Modifier.align(Alignment.CenterEnd),
            enter = slideInHorizontally { it } + fadeIn(), exit = slideOutHorizontally { it } + fadeOut()) {
            EdgePanel(state, a) { panel = false }
        }
        if (!state.coachShown) CoachMarks(Modifier.align(Alignment.Center)) { state.coachShown = true; a.coachDone() }
    }
}

@Composable
private fun StatsPill(state: ToolbarState, modifier: Modifier) {
    val c = LocalMidnight.current
    val (col, label) = when (state.link) {
        LinkState.GOOD -> c.ok to R.string.pcr_link_good
        LinkState.POOR -> c.warn to R.string.pcr_link_poor
        LinkState.RECONNECTING -> c.bad to R.string.pcr_link_reconnecting
    }
    Column(modifier) {
        Row(
            Modifier.clip(MidnightShapes.pill).background(glass()).clickable { state.showPerf = !state.showPerf }
                .padding(start = 4.dp, end = 12.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(col, live = state.link == LinkState.GOOD, size = 7.dp)
            Text(stringResource(label), color = c.text, style = MaterialTheme.typography.labelMedium)
        }
        AnimatedVisibility(state.showPerf && state.perfText.isNotEmpty()) {
            Text(state.perfText, color = c.text, fontSize = 11.sp, lineHeight = 14.sp,
                modifier = Modifier.padding(top = 6.dp).clip(MidnightShapes.control).background(glass()).padding(10.dp))
        }
    }
}

@Composable
private fun LowerQualityChip(a: ToolbarActions, modifier: Modifier) {
    val c = LocalMidnight.current
    Row(
        modifier.clip(MidnightShapes.pill).background(glass()).border(1.dp, c.warn.copy(alpha = 0.6f), MidnightShapes.pill)
            .clickable(onClick = a::lowerQuality).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.NetworkCheck, null, tint = c.warn, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.pcr_tb_lower_quality), color = c.text, style = MaterialTheme.typography.labelLarge)
    }
}

/** A slim handle on the right edge: tap it or swipe left from it to open the panel. */
@Composable
private fun EdgeHandle(modifier: Modifier, open: () -> Unit) {
    val c = LocalMidnight.current
    val haptics = rememberHaptics()
    Box(
        modifier.width(28.dp).height(120.dp)
            .pointerInput(Unit) { detectHorizontalDragGestures { _, drag -> if (drag < -12f) { haptics.tick(); open() } } }
            .clickable(remember { MutableInteractionSource() }, null) { haptics.tick(); open() },
        contentAlignment = Alignment.CenterEnd,
    ) {
        Box(Modifier.padding(end = 6.dp).width(5.dp).height(56.dp).clip(MidnightShapes.pill).background(c.text.copy(alpha = 0.45f)))
    }
}

@Composable
private fun EdgePanel(state: ToolbarState, a: ToolbarActions, close: () -> Unit) {
    val c = LocalMidnight.current
    Column(
        Modifier.fillMaxHeight().width(320.dp).clip(MidnightShapes.card).background(c.surface.copy(alpha = 0.94f))
            .border(1.dp, c.border, MidnightShapes.card).verticalScroll(rememberScrollState()).padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.pcr_controls), color = c.text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Tile(Icons.Rounded.Close, null, compact = true, onClick = close)
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile(Icons.Rounded.Keyboard, R.string.pcr_tb_keyboard, active = state.keyboardOpen, modifier = Modifier.weight(1f)) {
                state.keyboardOpen = !state.keyboardOpen; a.keyboard(); close()
            }
            Tile(if (state.trackpad) Icons.Rounded.Mouse else Icons.Rounded.TouchApp,
                if (state.trackpad) R.string.pcr_trackpad else R.string.pcr_direct_touch, modifier = Modifier.weight(1f)) {
                state.trackpad = !state.trackpad; a.setTrackpad(state.trackpad)
            }
            Tile(Icons.Rounded.Speed, R.string.pcr_tb_stats, active = state.showPerf, modifier = Modifier.weight(1f)) {
                state.showPerf = !state.showPerf
            }
        }
        Section(R.string.pcr_tb_media_keys)
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Tile(Icons.Rounded.SkipPrevious, null, compact = true) { a.shortcut(Shortcut.PREV) }
            Tile(Icons.Rounded.PlayArrow, null, compact = true) { a.shortcut(Shortcut.PLAY_PAUSE) }
            Tile(Icons.Rounded.SkipNext, null, compact = true) { a.shortcut(Shortcut.NEXT) }
            Tile(Icons.Rounded.VolumeDown, null, compact = true) { a.shortcut(Shortcut.VOL_DOWN) }
            Tile(Icons.Rounded.VolumeUp, null, compact = true) { a.shortcut(Shortcut.VOL_UP) }
            Tile(Icons.Rounded.VolumeOff, null, compact = true) { a.shortcut(Shortcut.MUTE) }
        }
        Section(R.string.pcr_shortcuts)
        val shortcuts: List<Triple<ImageVector, Int, () -> Unit>> = listOf(
            Triple(Icons.Rounded.Window, R.string.pcr_tb_windows_key) { a.shortcut(Shortcut.WIN) },
            Triple(Icons.Rounded.SwapHoriz, R.string.pcr_tb_alt_tab) { a.shortcut(Shortcut.ALT_TAB) },
            Triple(Icons.Rounded.ContentCopy, R.string.pcr_tb_copy) { a.shortcut(Shortcut.COPY) },
            Triple(Icons.Rounded.ContentPaste, R.string.pcr_tb_paste) { a.shortcut(Shortcut.PASTE) },
            Triple(Icons.Rounded.SpaceDashboard, R.string.pcr_sc_desktop) { a.keys(chord(listOf(0x5B, 0x44))) },
            Triple(Icons.Rounded.Screenshot, R.string.pcr_sc_snip) { a.keys(chord(listOf(0x5B, 0x10, 0x53))) },
            Triple(Icons.Rounded.Monitor, R.string.pcr_sc_taskmgr) { a.keys(chord(listOf(0x11, 0x10, VK_ESC))) },
            Triple(Icons.Rounded.Close, R.string.pcr_sc_close_window) { a.keys(chord(listOf(0x12, 0x73))) },
        )
        shortcuts.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                row.forEach { (icon, label, action) -> Tile(icon, label, wide = true, modifier = Modifier.weight(1f), onClick = action) }
            }
        }
        Spacer(Modifier.height(12.dp))
        GhostButton(stringResource(R.string.pcr_tb_disconnect), a::disconnect, Modifier.fillMaxWidth(), Icons.Rounded.PowerSettingsNew)
    }
}

@Composable
private fun Section(title: Int) {
    Spacer(Modifier.height(22.dp))
    Text(stringResource(title).uppercase(), color = LocalMidnight.current.textDim, style = MaterialTheme.typography.labelSmall,
        letterSpacing = 1.2.sp, modifier = Modifier.padding(bottom = 10.dp))
}

@Composable
private fun Tile(icon: ImageVector, label: Int?, modifier: Modifier = Modifier, active: Boolean = false, compact: Boolean = false,
                 wide: Boolean = false, onClick: () -> Unit) {
    val c = LocalMidnight.current
    val haptics = rememberHaptics()
    val bg = if (active) c.accent.copy(alpha = 0.22f) else c.surfaceHigh
    if (wide) {
        Row(modifier.clip(MidnightShapes.control).background(bg).clickable { haptics.tick(); onClick() }.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = c.accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            if (label != null) Text(stringResource(label), color = c.text, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    } else {
        Column(
            modifier.clip(MidnightShapes.control).background(bg).clickable { haptics.tick(); onClick() }
                .padding(if (compact) 10.dp else 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(icon, label?.let { stringResource(it) }, tint = if (active) c.accent else c.text, modifier = Modifier.size(22.dp))
            if (label != null && !compact) {
                Spacer(Modifier.height(6.dp))
                Text(stringResource(label), color = c.textDim, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
    }
}

/** Key row with sticky modifiers (tap = once, double-tap = lock) plus navigation and F-keys. */
@Composable
private fun KeyboardBar(state: ToolbarState, a: ToolbarActions) {
    val c = LocalMidnight.current
    val haptics = rememberHaptics()
    var fn by remember { mutableStateOf(false) }
    @Suppress("UNUSED_EXPRESSION") state.barTick // recompose when modifiers change

    @Composable
    fun Cap(text: String? = null, icon: ImageVector? = null, mod: Mod? = null, onClick: () -> Unit) {
        val st = mod?.let { state.bar.state(it) } ?: ModState.OFF
        val bg = when (st) { ModState.LOCKED -> c.accent; ModState.ONE_SHOT -> c.accent.copy(alpha = 0.3f); else -> c.surfaceHigh }
        val fg = if (st == ModState.LOCKED) c.onAccent else c.text
        Box(
            Modifier.height(40.dp).widthIn(min = 44.dp).clip(MidnightShapes.control).background(bg)
                .clickable { haptics.tick(); onClick() }.padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) Icon(icon, text, tint = fg, modifier = Modifier.size(20.dp))
            else Text(text ?: "", color = fg, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
    }

    fun press(vk: Int) = a.keys(state.bar.key(vk)).also { state.barTick++ }
    fun tapMod(m: Mod) { state.bar.tap(m, System.currentTimeMillis()); state.barTick++ }

    Row(
        Modifier.clip(MidnightShapes.card).background(glass()).border(1.dp, c.border, MidnightShapes.card)
            .horizontalScroll(rememberScrollState()).padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (fn) {
            for (i in 0 until 12) Cap("F${i + 1}") { press(VK_F1 + i) }
            Cap(icon = Icons.Rounded.KeyboardReturn) { fn = false }
        } else {
            Cap("Esc") { press(VK_ESC) }
            Cap(icon = Icons.AutoMirrored.Rounded.KeyboardTab) { press(VK_TAB) }
            Cap("Ctrl", mod = Mod.CTRL) { tapMod(Mod.CTRL) }
            Cap("Alt", mod = Mod.ALT) { tapMod(Mod.ALT) }
            Cap("Shift", mod = Mod.SHIFT) { tapMod(Mod.SHIFT) }
            Cap(icon = Icons.Rounded.Window, mod = Mod.WIN) { tapMod(Mod.WIN) }
            Cap(icon = Icons.Rounded.KeyboardArrowLeft) { press(VK_LEFT) }
            Cap(icon = Icons.Rounded.KeyboardArrowUp) { press(VK_UP) }
            Cap(icon = Icons.Rounded.KeyboardArrowDown) { press(VK_DOWN) }
            Cap(icon = Icons.Rounded.KeyboardArrowRight) { press(VK_RIGHT) }
            Cap("Fn") { fn = true }
            Cap("Del") { press(VK_DELETE) }
            Cap("Home") { press(VK_HOME) }
            Cap("End") { press(VK_END) }
            Cap("PgUp") { press(VK_PGUP) }
            Cap("PgDn") { press(VK_PGDN) }
        }
    }
}

@Composable
private fun CoachMarks(modifier: Modifier, done: () -> Unit) {
    val c = LocalMidnight.current
    Column(
        modifier.padding(24.dp).widthIn(max = 420.dp).clip(MidnightShapes.card).background(c.surface.copy(alpha = 0.96f))
            .border(1.dp, c.border, MidnightShapes.card).padding(24.dp),
    ) {
        Text(stringResource(R.string.pcr_coach_title), color = c.text, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))
        listOf(
            Icons.Rounded.SwipeLeft to R.string.pcr_coach_edge,
            Icons.Rounded.TouchApp to R.string.pcr_coach_tap,
            Icons.Rounded.Pinch to R.string.pcr_coach_pinch,
        ).forEach { (icon, text) ->
            Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = c.accent, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(14.dp))
                Text(stringResource(text), color = c.textDim, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(16.dp))
        PrimaryButton(stringResource(R.string.pcr_got_it), done, Modifier.align(Alignment.End))
    }
}

/** Midnight connecting screen (replaces Moonlight's spinner dialog). */
@Composable
private fun Connecting(pcName: String?, stage: String, cancel: () -> Unit) {
    val c = LocalMidnight.current
    Box(Modifier.fillMaxSize().background(c.bg), contentAlignment = Alignment.Center) {
        com.pcremote.ui.components.Aurora(intensity = 0.7f)
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            androidx.compose.material3.CircularProgressIndicator(color = c.accent, trackColor = c.surfaceHigh, strokeWidth = 3.dp,
                modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(28.dp))
            Text(stringResource(R.string.pcr_connecting_to, pcName ?: "PC"), color = c.text, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            AnimatedContent(stage, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "stage") {
                Text(it, color = c.textDim, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(32.dp))
            GhostButton(stringResource(R.string.pcr_cancel), cancel)
        }
    }
}
