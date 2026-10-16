package com.pcremote.modes

import android.content.Context
import android.content.pm.ActivityInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.limelight.R
import com.pcremote.agent.AgentState
import com.pcremote.agent.Msg
import com.pcremote.settings.SettingsModel
import com.pcremote.settings.prefsKv
import com.pcremote.ui.theme.LocalMidnight
import com.pcremote.ui.theme.MidnightShapes
import com.pcremote.ui.theme.rememberHaptics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.toMutableStateList
import kotlin.math.atan2
import kotlin.math.hypot

private fun layoutKey(id: String) = "pcremote_pad_$id"

@Composable
fun PadScreen(pcId: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val session = rememberAgentSession(pcId)
    val activity = ctx as? android.app.Activity
    DisposableEffect(Unit) {
        val prev = activity?.requestedOrientation
        // Fixed landscape so gyro axes always mean the same thing
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        onDispose { activity?.requestedOrientation = prev ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
    KeepScreenOn()
    BackHandler(onBack = onBack)
    val state by (session?.conn?.state ?: remember { kotlinx.coroutines.flow.MutableStateFlow(AgentState.Closed) }).collectAsState()
    val online = state is AgentState.Online
    val sensors = remember { ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    val hasGyro = remember { sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null }
    val hasTilt = remember { sensors.getDefaultSensor(Sensor.TYPE_GRAVITY) != null || sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null }
    val scope = rememberCoroutineScope()
    val kv = remember { prefsKv(ctx) }
    val layouts = remember {
        PadLayouts.available(hasGyro, hasTilt).map { l -> kv.get(layoutKey(l.id))?.let(PadLayouts::fromJson) ?: l }.toMutableStateList()
    }
    var current by remember { mutableStateOf(layouts.first()) }
    var editing by remember { mutableStateOf(false) }
    var pad by remember { mutableStateOf(PadState()) }
    val sender = remember { PadSender(8) }
    val sens = remember { SettingsModel.get(ctx).gyroSensitivity }

    fun update(f: (PadState) -> PadState) {
        pad = f(pad)
        sender.offer(pad, SystemClock.uptimeMillis())?.let { session?.send(it) }
    }
    LaunchedEffect(online) {
        while (online) { sender.flush(SystemClock.uptimeMillis())?.let { session?.send(it) }; delay(8) }
    }
    DisposableEffect(session) { onDispose { session?.send(Msg.PadOff(0)) } }
    Rumble(session)
    val gyroUse = current.gyro
    GyroInput(gyroUse, sens) { x, y -> update { if (gyroUse == GyroUse.STEER) it.stick(true, x, it.ly / -32768f) else it.stick(false, x, y) } }

    val c = LocalMidnight.current
    Box(Modifier.fillMaxSize().background(c.bg).safeDrawingPadding()) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(16.dp)) {
            val h = maxHeight; val w = maxWidth
            current.elements.forEachIndexed { i, el -> key(current.id, i) {
                val sizeDp = h * el.size
                val pos = Modifier.offset(w * el.x - sizeDp / 2, h * el.y - sizeDp / 2).size(sizeDp).alpha(current.opacity)
                if (editing) {
                    EditHandle(pos, el, w, h) { nx, ny ->
                        current = current.copy(elements = current.elements.toMutableList().also { it[i] = el.copy(x = nx, y = ny) })
                    }
                } else when (el.kind) {
                    ElementKind.BUTTON -> PadKey(pos, el.button!!.label) { down -> update { if (down) it.press(el.button) else it.release(el.button) } }
                    ElementKind.STICK_L, ElementKind.STICK_R -> Stick(pos, sizeDp) { x, y -> update { it.stick(el.kind == ElementKind.STICK_L, x, y) } }
                    ElementKind.DPAD -> DPad(pos) { dirs -> update { s ->
                        var n = s; for (d in listOf(PadButton.UP, PadButton.DOWN, PadButton.LEFT, PadButton.RIGHT)) n = if (d in dirs) n.press(d) else n.release(d); n
                    } }
                    ElementKind.TRIGGER_L, ElementKind.TRIGGER_R -> PadKey(pos, if (el.kind == ElementKind.TRIGGER_L) "LT" else "RT") { down ->
                        update { it.trigger(el.kind == ElementKind.TRIGGER_L, if (down) 1f else 0f) }
                    }
                }
            } }
        }
        // Top bar: back, layouts, edit
        Row(Modifier.align(Alignment.TopCenter).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Chip(icon = true, onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = c.text, modifier = Modifier.size(18.dp)) }
            layouts.forEach { l ->
                Chip(selected = l.id == current.id, onClick = { current = l }) {
                    Text(stringResource(layoutName(l.id)), color = if (l.id == current.id) c.onAccent else c.text, style = MaterialTheme.typography.labelMedium)
                }
            }
            Chip(icon = true, selected = editing, onClick = {
                if (editing) {
                    kv.put(layoutKey(current.id), PadLayouts.toJson(current))
                    val i = layouts.indexOfFirst { it.id == current.id }
                    if (i >= 0) layouts[i] = current // keep the edit when switching layouts
                }
                editing = !editing
            }) { Icon(if (editing) Icons.Rounded.Check else Icons.Rounded.Edit, null, tint = if (editing) c.onAccent else c.text, modifier = Modifier.size(18.dp)) }
            when {
                state is AgentState.Failed -> Chip(onClick = { session?.let { s -> scope.launch { s.conn.hello(s.pc.token, Build.MODEL) } } }) {
                    Text(stringResource(if ((state as AgentState.Failed).reason == com.pcremote.agent.FailReason.REVOKED) R.string.pcr_conn_revoked else R.string.pcr_pad_retry),
                        color = c.bad, style = MaterialTheme.typography.labelMedium)
                }
                !online -> Text(stringResource(R.string.pcr_conn_connecting), color = c.warn, style = MaterialTheme.typography.labelMedium)
                !hasGyro -> Text(stringResource(R.string.pcr_pad_no_gyro), color = c.textDim, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun layoutName(id: String) = when (id) {
    "racing" -> R.string.pcr_pad_racing; "shooter" -> R.string.pcr_pad_shooter; "minimal" -> R.string.pcr_pad_minimal; else -> R.string.pcr_pad_xbox
}

@Composable
private fun Chip(selected: Boolean = false, icon: Boolean = false, onClick: () -> Unit, content: @Composable () -> Unit) {
    val c = LocalMidnight.current
    Box(Modifier.clip(MidnightShapes.pill).background(if (selected) c.accent else c.surfaceHigh).clickable(onClick = onClick)
        .padding(horizontal = if (icon) 10.dp else 14.dp, vertical = 8.dp), contentAlignment = Alignment.Center) { content() }
}

/** A press-and-hold control (face buttons, bumpers, triggers). */
@Composable
private fun PadKey(modifier: Modifier, label: String, onChange: (Boolean) -> Unit) {
    val c = LocalMidnight.current
    val haptics = rememberHaptics()
    var down by remember { mutableStateOf(false) }
    Box(
        modifier.clip(CircleShape).background(if (down) c.accent else c.surfaceHigh).border(1.dp, c.border, CircleShape)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val ch = awaitPointerEvent().changes.first()
                        if (ch.pressed != down) { down = ch.pressed; if (down) haptics.tick(); onChange(down) }
                        ch.consume()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (down) c.onAccent else c.text, style = MaterialTheme.typography.titleMedium) }
}

@Composable
private fun Stick(modifier: Modifier, size: Dp, onMove: (Float, Float) -> Unit) {
    val c = LocalMidnight.current
    val radius = with(LocalDensity.current) { (size / 2).toPx() }
    var knob by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier.clip(CircleShape).background(c.surface).border(2.dp, c.border, CircleShape)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { p -> knob = clampToCircle(p - Offset(radius, radius), radius); onMove(knob.x / radius, knob.y / radius) },
                    onDragEnd = { knob = Offset.Zero; onMove(0f, 0f) },
                    onDragCancel = { knob = Offset.Zero; onMove(0f, 0f) },
                ) { ch, _ -> knob = clampToCircle(ch.position - Offset(radius, radius), radius); onMove(knob.x / radius, knob.y / radius) }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.offset { androidx.compose.ui.unit.IntOffset(knob.x.toInt(), knob.y.toInt()) }.fillMaxSize(0.42f)
            .clip(CircleShape).background(c.accent.copy(alpha = 0.85f)))
    }
}

private fun clampToCircle(p: Offset, r: Float): Offset {
    val d = hypot(p.x, p.y)
    return if (d <= r) p else p * (r / d)
}

@Composable
private fun DPad(modifier: Modifier, onDirs: (Set<PadButton>) -> Unit) {
    val c = LocalMidnight.current
    var dirs by remember { mutableStateOf(emptySet<PadButton>()) }
    Box(
        modifier.clip(RoundedCornerShape(28)).background(c.surfaceHigh).pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val ch = awaitPointerEvent().changes.first()
                    val new = if (!ch.pressed) emptySet() else {
                        val p = ch.position - Offset(size.width / 2f, size.height / 2f)
                        if (hypot(p.x, p.y) < size.width * 0.12f) emptySet() else {
                            val a = Math.toDegrees(atan2(p.y.toDouble(), p.x.toDouble()))
                            buildSet {
                                if (a > -157.5 && a < -22.5) add(PadButton.UP)
                                if (a > 22.5 && a < 157.5) add(PadButton.DOWN)
                                if (a > 112.5 || a < -112.5) add(PadButton.LEFT)
                                if (a > -67.5 && a < 67.5) add(PadButton.RIGHT)
                            }
                        }
                    }
                    if (new != dirs) { dirs = new; onDirs(new) }
                    ch.consume()
                }
            }
        },
    ) {
        listOf(PadButton.UP to Alignment.TopCenter, PadButton.DOWN to Alignment.BottomCenter,
            PadButton.LEFT to Alignment.CenterStart, PadButton.RIGHT to Alignment.CenterEnd).forEach { (b, al) ->
            Text(b.label, color = if (b in dirs) c.accent else c.textDim, style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.align(al).padding(10.dp))
        }
    }
}

@Composable
private fun EditHandle(modifier: Modifier, el: PadElement, w: Dp, h: Dp, onMoved: (Float, Float) -> Unit) {
    val c = LocalMidnight.current
    val density = LocalDensity.current
    var x by remember { mutableFloatStateOf(el.x) }
    var y by remember { mutableFloatStateOf(el.y) }
    Box(
        modifier.clip(CircleShape).background(c.accent.copy(alpha = 0.25f)).border(2.dp, c.accent, CircleShape)
            .offset(w * (x - el.x), h * (y - el.y)) // live preview while dragging
            .pointerInput(Unit) {
                val wp = with(density) { w.toPx() }; val hp = with(density) { h.toPx() }
                detectDragGestures(onDragEnd = { onMoved(x, y) }) { ch, d ->
                    ch.consume()
                    x = (x + d.x / wp).coerceIn(0.05f, 0.95f); y = (y + d.y / hp).coerceIn(0.05f, 0.95f)
                }
            },
        contentAlignment = Alignment.Center,
    ) { Text(el.button?.label ?: el.kind.name.take(2), color = c.text) }
}

/** STEER: phone tilt (like a wheel) → left stick X. AIM: gyro rotation → right stick. */
@Composable
private fun GyroInput(use: GyroUse, sensitivity: Float, onStick: (Float, Float) -> Unit) {
    val ctx = LocalContext.current
    DisposableEffect(use) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                when (use) {
                    GyroUse.STEER -> onStick((e.values[1] / 9.81f * 1.6f * sensitivity).coerceIn(-1f, 1f), 0f)
                    GyroUse.AIM -> onStick((-e.values[0] * 0.35f * sensitivity).coerceIn(-1f, 1f), (-e.values[1] * 0.35f * sensitivity).coerceIn(-1f, 1f))
                    GyroUse.NONE -> {}
                }
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        val sensor = when (use) {
            GyroUse.STEER -> sm.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            GyroUse.AIM -> sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            GyroUse.NONE -> null
        }
        if (sensor != null) sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm.unregisterListener(l) }
    }
}

/** Forwards the PC's rumble to the phone's vibrator. */
@Composable
private fun Rumble(session: AgentSession?) {
    val ctx = LocalContext.current
    val vib: Vibrator? = remember {
        if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java)
    }
    DisposableEffect(Unit) { onDispose { vib?.cancel() } } // stop rumbling when leaving the pad
    LaunchedEffect(session) {
        session?.conn?.incoming?.collect { m ->
            if (m is Msg.Rumble && vib?.hasVibrator() == true) {
                val amp = maxOf(m.lo, m.hi)
                if (amp == 0) vib.cancel() else vib.vibrate(VibrationEffect.createOneShot(5_000, amp.coerceIn(1, 255)))
            }
        }
    }
}
