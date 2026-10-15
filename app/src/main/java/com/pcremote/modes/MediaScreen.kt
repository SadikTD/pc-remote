package com.pcremote.modes

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.limelight.R
import com.pcremote.agent.AgentState
import com.pcremote.agent.Msg
import com.pcremote.ui.components.Aurora
import com.pcremote.ui.theme.LocalMidnight
import com.pcremote.ui.theme.MidnightShapes
import com.pcremote.ui.theme.NumberStyle
import com.pcremote.ui.theme.rememberHaptics
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun MediaScreen(pcId: String, onBack: () -> Unit, onPower: () -> Unit) {
    val session = rememberAgentSession(pcId)
    var media by remember { mutableStateOf(Msg.Media()) }
    ModeScaffold(R.string.pcr_mode_media, session, onBack, onPower) { state ->
        val online = state is AgentState.Online
        val send: (Msg) -> Unit = remember(session) { { m: Msg -> session?.send(m); Unit } }
        LaunchedEffect(online) { if (online) session?.send(Msg.MediaSub(true)) }
        LaunchedEffect(session) { session?.conn?.incoming?.collect { if (it is Msg.Media) media = it } }
        DisposableEffect(Unit) {
            VolumeKeys.activateVolume(send)
            onDispose { VolumeKeys.deactivate() }
        }
        MediaBody(media, send)
    }
}

@Composable
private fun ColumnScope.MediaBody(m: Msg.Media, send: (Msg) -> Unit) {
    val c = LocalMidnight.current
    Box(Modifier.weight(1f).fillMaxWidth()) {
        Aurora(intensity = if (m.playing) 0.8f else 0.35f)
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly) {
            // Now playing
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (m.app.isNotEmpty()) {
                    Text(m.app.uppercase(), color = c.accent, style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.clip(MidnightShapes.pill).background(c.accent.copy(alpha = 0.15f)).padding(horizontal = 12.dp, vertical = 4.dp))
                    Spacer(Modifier.height(16.dp))
                }
                AnimatedContent(m.title to m.artist, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "np") { (title, artist) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(title.ifEmpty { stringResource(R.string.pcr_media_nothing) }, color = c.text,
                            style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (artist.isNotEmpty()) Text(artist, color = c.textDim, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    }
                }
            }
            // Transport
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                RoundButton(Icons.Rounded.SkipPrevious, 64) { send(Msg.MediaCmd("prev")) }
                RoundButton(if (m.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, 88, primary = true) { send(Msg.MediaCmd("play_pause")) }
                RoundButton(Icons.Rounded.SkipNext, 64) { send(Msg.MediaCmd("next")) }
            }
            // Volume
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                RoundButton(Icons.Rounded.VolumeDown, 52) { send(Msg.MediaCmd("vol_down")) }
                VolumeDialView(m.vol, m.muted) { send(Msg.MediaCmd("vol_set", it)) }
                RoundButton(if (m.muted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp, 52) { send(Msg.MediaCmd("mute")) }
            }
        }
    }
}

@Composable
private fun RoundButton(icon: ImageVector, size: Int, primary: Boolean = false, onClick: () -> Unit) {
    val c = LocalMidnight.current
    val haptics = rememberHaptics()
    Box(
        Modifier.size(size.dp).clip(CircleShape)
            .background(if (primary) Brush.linearGradient(listOf(c.accent, c.accent2)) else Brush.linearGradient(listOf(c.surfaceHigh, c.surfaceHigh)))
            .clickable { haptics.click(); onClick() },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = if (primary) c.onAccent else c.text, modifier = Modifier.size((size * 0.45f).dp)) }
}

/** Rotary volume control: drag around the ring; ticks every 5 %. */
@Composable
private fun VolumeDialView(volume: Int, muted: Boolean, onSet: (Int) -> Unit) {
    val c = LocalMidnight.current
    val haptics = rememberHaptics()
    var local by remember { mutableIntStateOf(volume) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(volume) { if (!dragging) local = volume }
    Box(Modifier.size(180.dp), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectDragGestures(onDragStart = { dragging = true }, onDragEnd = { dragging = false }) { change, _ ->
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val p = change.position - center
                    val deg = Math.toDegrees(atan2(p.x.toDouble(), -p.y.toDouble())).toFloat()
                    val v = VolumeDial.next(local, deg) ?: return@detectDragGestures // ignore wraps across the gap
                    if (v != local) {
                        if (v / 5 != local / 5) haptics.tick()
                        local = v
                        onSet(v)
                    }
                }
            },
        ) {
            val stroke = 14.dp.toPx()
            val inset = stroke / 2 + 6.dp.toPx()
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            val start = 90f + 45f // canvas 0° is 3 o'clock; dial starts at -135° from top
            drawArc(c.surfaceHigh, start, VolumeDial.SWEEP, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(Brush.sweepGradient(listOf(c.accent, c.accent2, c.accent)), start, VolumeDial.SWEEP * local / 100f, false,
                Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            val a = Math.toRadians((VolumeDial.volumeToAngle(local) - 90f).toDouble())
            val r = arcSize.width / 2
            drawCircle(c.text, stroke * 0.7f, Offset(size.width / 2 + (r * cos(a)).toFloat(), size.height / 2 + (r * sin(a)).toFloat()))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (muted) "—" else "$local", color = c.text, style = NumberStyle)
            Text(stringResource(R.string.pcr_media_volume), color = c.textDim, style = MaterialTheme.typography.labelSmall)
        }
    }
}
