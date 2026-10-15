package com.pcremote.modes

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.limelight.R
import com.pcremote.agent.AgentState
import com.pcremote.agent.Msg
import com.pcremote.settings.SettingsModel
import com.pcremote.ui.theme.LocalMidnight
import com.pcremote.ui.theme.MidnightShapes
import com.pcremote.ui.theme.NumberStyle
import com.pcremote.ui.theme.rememberHaptics
import kotlinx.coroutines.delay

private val TARGETS_MIN = listOf(0, 5, 10, 15, 20, 30, 45, 60)

@Composable
fun SlidesScreen(pcId: String, onBack: () -> Unit, onPower: () -> Unit) {
    val session = rememberAgentSession(pcId)
    ModeScaffold(R.string.pcr_mode_slides, session, onBack, onPower) { state ->
        val send: (List<Msg>) -> Unit = remember(session) { { msgs: List<Msg> -> msgs.forEach { session?.send(it) } } }
        val haptics = rememberHaptics()
        DisposableEffect(Unit) {
            // Volume down = next slide, volume up = previous, like a clicker
            VolumeKeys.activate(onKey = { up -> haptics.click(); send(if (up) Slides.prev() else Slides.next()) })
            onDispose { VolumeKeys.deactivate() }
        }
        SlidesBody(send)
    }
}

@Composable
private fun ColumnScope.SlidesBody(send: (List<Msg>) -> Unit) {
    val c = LocalMidnight.current
    val ctx = LocalContext.current
    val haptics = rememberHaptics()
    var startedAt by remember { mutableLongStateOf(0L) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var targetIdx by remember { mutableIntStateOf(0) }
    var black by remember { mutableStateOf(false) }
    val warnings = remember(targetIdx) { TimerWarnings(TARGETS_MIN[targetIdx] * 60_000L) }

    LaunchedEffect(startedAt) {
        while (startedAt > 0) {
            elapsed = System.currentTimeMillis() - startedAt
            warnings.tick(elapsed)?.let { haptics.heavy() }
            delay(500)
        }
    }

    // Timer
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).clickable { if (startedAt == 0L) startedAt = System.currentTimeMillis() else { startedAt = 0L; elapsed = 0L } }) {
            val over = TARGETS_MIN[targetIdx] > 0 && elapsed >= TARGETS_MIN[targetIdx] * 60_000L
            Text("%02d:%02d".format(elapsed / 60_000, elapsed / 1000 % 60), style = NumberStyle, color = if (over) c.bad else c.text)
            Text(stringResource(if (startedAt == 0L) R.string.pcr_slides_tap_start else R.string.pcr_slides_tap_reset),
                color = c.textDim, style = MaterialTheme.typography.bodySmall)
        }
        Pill(if (TARGETS_MIN[targetIdx] == 0) stringResource(R.string.pcr_slides_no_target)
             else stringResource(R.string.pcr_slides_target, TARGETS_MIN[targetIdx]), Icons.Rounded.Timer) {
            targetIdx = (targetIdx + 1) % TARGETS_MIN.size
        }
    }

    // Prev / Next zones
    Row(Modifier.weight(1f).fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Zone(Modifier.weight(1f), Icons.AutoMirrored.Rounded.ArrowBack, R.string.pcr_slides_prev, primary = false) { haptics.click(); send(Slides.prev()) }
        Zone(Modifier.weight(2f), Icons.AutoMirrored.Rounded.ArrowForward, R.string.pcr_slides_next, primary = true) { haptics.click(); send(Slides.next()) }
    }

    // Tools
    Row(Modifier.fillMaxWidth().padding(16.dp, 0.dp, 16.dp, 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Pill(stringResource(R.string.pcr_slides_black), if (black) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
            Modifier.weight(1f), active = black) { black = !black; send(Slides.black()) }
        val gyro = remember { (ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager).getDefaultSensor(Sensor.TYPE_GYROSCOPE) }
        if (gyro != null) LaserButton(Modifier.weight(1f), gyro, send)
    }
}

/** Hold to move the PC pointer with the phone's gyro. */
@Composable
private fun LaserButton(modifier: Modifier, gyro: Sensor, send: (List<Msg>) -> Unit) {
    val c = LocalMidnight.current
    val ctx = LocalContext.current
    val sens = remember { SettingsModel.get(ctx).gyroSensitivity }
    var held by remember { mutableStateOf(false) }
    DisposableEffect(held) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        var last = 0L
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val dt = if (last == 0L) 16L else (e.timestamp - last) / 1_000_000
                last = e.timestamp
                Laser.map(e.values[0], e.values[2], dt, sens)?.let { send(listOf(it)) }
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        if (held) sm.registerListener(l, gyro, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm.unregisterListener(l) }
    }
    Row(
        modifier.height(56.dp).clip(MidnightShapes.control)
            .background(if (held) Brush.linearGradient(listOf(c.accent, c.accent2)) else Brush.linearGradient(listOf(c.surfaceHigh, c.surfaceHigh)))
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val ch = awaitPointerEvent().changes.first()
                        held = ch.pressed
                        ch.consume()
                    }
                }
            },
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Highlight, null, tint = if (held) c.onAccent else c.text)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.pcr_slides_laser), color = if (held) c.onAccent else c.text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun Zone(modifier: Modifier, icon: ImageVector, label: Int, primary: Boolean, onClick: () -> Unit) {
    val c = LocalMidnight.current
    Column(
        modifier.fillMaxHeight().clip(MidnightShapes.card)
            .background(if (primary) Brush.verticalGradient(listOf(c.accent.copy(alpha = 0.35f), c.surface)) else Brush.verticalGradient(listOf(c.surface, c.surface)))
            .border(1.dp, c.border, MidnightShapes.card).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = c.text, modifier = Modifier.size(if (primary) 56.dp else 40.dp))
        Spacer(Modifier.height(10.dp))
        Text(stringResource(label), color = c.textDim, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun Pill(text: String, icon: ImageVector, modifier: Modifier = Modifier, active: Boolean = false, onClick: () -> Unit) {
    val c = LocalMidnight.current
    val haptics = rememberHaptics()
    Row(
        modifier.height(56.dp).clip(MidnightShapes.control).background(if (active) c.accent else c.surfaceHigh)
            .clickable { haptics.tick(); onClick() }.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (active) c.onAccent else c.text)
        Spacer(Modifier.width(8.dp))
        Text(text, color = if (active) c.onAccent else c.text, style = MaterialTheme.typography.labelLarge)
    }
}
