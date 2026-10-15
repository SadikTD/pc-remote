package com.pcremote.modes

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardBackspace
import androidx.compose.material.icons.automirrored.rounded.KeyboardReturn
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.limelight.R
import com.limelight.nvstream.input.KeyboardPacket
import com.pcremote.agent.AgentState
import com.pcremote.agent.Msg
import com.pcremote.settings.SettingsModel
import com.pcremote.stream.KeyEvt
import com.pcremote.stream.KeyboardBarModel
import com.pcremote.stream.Mod
import com.pcremote.stream.ModState
import com.pcremote.stream.Shortcut
import com.pcremote.ui.theme.LocalMidnight
import com.pcremote.ui.theme.MidnightShapes
import com.pcremote.ui.theme.rememberHaptics
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Moonlight-style key events → agent `key` messages. */
fun KeyEvt.toMsg() = Msg.Key(keyMap.toInt() and 0xFF, direction == KeyboardPacket.KEY_DOWN)

private const val SENTINEL = "​​" // keeps backspace observable in an empty field

@Composable
fun RemoteScreen(pcId: String, onBack: () -> Unit, onPower: () -> Unit) {
    val session = rememberAgentSession(pcId)
    ModeScaffold(R.string.pcr_mode_remote, session, onBack, onPower) { state ->
        val send: (Msg) -> Unit = { if (state is AgentState.Online) session?.send(it) }
        RemoteBody(send)
    }
}

@Composable
private fun ColumnScope.RemoteBody(send: (Msg) -> Unit) {
    val c = LocalMidnight.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val settings = remember { SettingsModel.get(ctx) }
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val model = remember { TrackpadModel(settings.pointerSpeed, settings.naturalScroll) }
    val bar = remember { KeyboardBarModel() }
    var barTick by remember { mutableIntStateOf(0) }
    var keyboard by remember { mutableStateOf(false) }

    fun emit(msgs: List<Msg>) {
        msgs.forEach(send)
        if (msgs.any { it is Msg.MouseButton && it.down }) haptics.click()
    }

    // Trackpad surface + scroll strip
    Row(Modifier.weight(1f).fillMaxWidth().padding(16.dp, 8.dp)) {
        Box(
            Modifier.weight(1f).fillMaxHeight().clip(MidnightShapes.card).background(c.surface)
                .border(1.dp, c.border, MidnightShapes.card)
                .pointerInput(Unit) {
                    var holdJob: Job? = null
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent()
                            val t = e.changes.firstOrNull()?.uptimeMillis ?: 0L
                            for (ch in e.changes) {
                                val id = ch.id.value.toInt()
                                when {
                                    ch.pressed && !ch.previousPressed -> {
                                        emit(model.down(id, ch.position.x, ch.position.y, t))
                                        holdJob?.cancel()
                                        holdJob = scope.launch { delay(TrackpadModel.HOLD_MS); emit(model.hold(id, t + TrackpadModel.HOLD_MS)) }
                                    }
                                    ch.pressed && ch.positionChanged() -> {
                                        val p: Offset = ch.position
                                        val out = model.move(id, p.x, p.y, t)
                                        if (ch.position != ch.previousPosition) holdJob?.let { j ->
                                            if ((ch.position - ch.previousPosition).getDistance() > 2f) j.cancel()
                                        }
                                        emit(out)
                                    }
                                    !ch.pressed && ch.previousPressed -> { holdJob?.cancel(); emit(model.up(id, t)) }
                                }
                                ch.consume()
                            }
                            if (e.type == PointerEventType.Exit) emit(model.cancel())
                        }
                    }
                },
        ) {
            DotGrid()
            Text(stringResource(R.string.pcr_remote_hint), color = c.textDim.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp))
        }
        Spacer(Modifier.width(10.dp))
        // Scroll strip
        Box(
            Modifier.width(44.dp).fillMaxHeight().clip(MidnightShapes.pill).background(c.surfaceHigh)
                .pointerInput(Unit) {
                    var acc = 0f
                    detectVerticalDragGestures(onDragEnd = { acc = 0f }) { _, dy ->
                        acc += dy
                        while (kotlin.math.abs(acc) >= 24f) {
                            val notch = if (acc > 0) -120 else 120
                            send(Msg.MouseScroll(if (settings.naturalScroll) -notch else notch))
                            haptics.tick()
                            acc -= if (acc > 0) 24f else -24f
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.UnfoldMore, null, tint = c.textDim) }
    }

    // Click buttons
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(56.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ClickPad(Modifier.weight(1f), "left", send)
        ClickPad(Modifier.weight(1f), "right", send)
    }
    Spacer(Modifier.height(10.dp))

    // Keys row
    @Suppress("UNUSED_EXPRESSION") barTick
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        KeyCap(Icons.Rounded.Keyboard, active = keyboard) { keyboard = !keyboard }
        for (m in Mod.entries) {
            KeyCap(null, m.name.lowercase().replaceFirstChar { it.uppercase() }, state = bar.state(m)) {
                bar.tap(m, System.currentTimeMillis()); barTick++
            }
        }
        KeyCap(null, "Esc") { bar.key(0x1B).forEach { send(it.toMsg()) }; barTick++ }
        KeyCap(null, "Tab") { bar.key(0x09).forEach { send(it.toMsg()) }; barTick++ }
        KeyCap(Icons.Rounded.SwapHoriz) { Shortcut.ALT_TAB.events().forEach { send(it.toMsg()) } }
        KeyCap(Icons.Rounded.Window) { Shortcut.WIN.events().forEach { send(it.toMsg()) } }
        KeyCap(Icons.Rounded.KeyboardArrowLeft) { bar.key(0x25).forEach { send(it.toMsg()) }; barTick++ }
        KeyCap(Icons.Rounded.KeyboardArrowUp) { bar.key(0x26).forEach { send(it.toMsg()) }; barTick++ }
        KeyCap(Icons.Rounded.KeyboardArrowDown) { bar.key(0x28).forEach { send(it.toMsg()) }; barTick++ }
        KeyCap(Icons.Rounded.KeyboardArrowRight) { bar.key(0x27).forEach { send(it.toMsg()) }; barTick++ }
    }
    Spacer(Modifier.height(12.dp))
    AnimatedVisibility(keyboard) { TypingField(send, bar) { barTick++ } }
}

/** Invisible field that forwards soft-keyboard typing to the PC. */
@Composable
private fun TypingField(send: (Msg) -> Unit, bar: KeyboardBarModel, onBarChanged: () -> Unit) {
    val c = LocalMidnight.current
    val focus = remember { FocusRequester() }
    val kb = LocalSoftwareKeyboardController.current
    var value by remember { mutableStateOf(TextFieldValue(SENTINEL, TextRange(SENTINEL.length))) }
    LaunchedEffect(Unit) { focus.requestFocus(); kb?.show() }
    fun key(vk: Int) { bar.key(vk).forEach { send(it.toMsg()) }; onBarChanged() }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            value = value,
            onValueChange = { nv ->
                val old = value.text; val new = nv.text
                when {
                    new.length < old.length -> repeat(old.length - new.length) { key(0x08) }
                    new.startsWith(old) -> {
                        val added = new.substring(old.length).replace("​", "")
                        if (added.isNotEmpty()) {
                            if (bar.anyActive()) added.forEach { ch -> charVk(ch)?.let(::key) ?: send(Msg.Text(ch.toString())) }
                            else send(Msg.Text(added))
                        }
                    }
                }
                value = TextFieldValue(SENTINEL, TextRange(SENTINEL.length))
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Send, autoCorrectEnabled = false),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { key(0x0D) }),
            modifier = Modifier.weight(1f).focusRequester(focus).height(1.dp),
        )
        KeyCap(Icons.AutoMirrored.Rounded.KeyboardBackspace) { key(0x08) }
        Spacer(Modifier.width(8.dp))
        KeyCap(Icons.AutoMirrored.Rounded.KeyboardReturn) { key(0x0D) }
    }
    Text(stringResource(R.string.pcr_remote_typing), color = c.textDim, style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 16.dp))
}

/** Letters/digits as virtual keys so sticky Ctrl/Alt combine with them (Ctrl+C, Alt+F4…). */
private fun charVk(ch: Char): Int? = when (ch) {
    in 'a'..'z' -> ch.uppercaseChar().code
    in 'A'..'Z', in '0'..'9' -> ch.code
    ' ' -> 0x20
    else -> null
}

@Composable
private fun ClickPad(modifier: Modifier, button: String, send: (Msg) -> Unit) {
    val c = LocalMidnight.current
    val haptics = rememberHaptics()
    Box(
        modifier.fillMaxHeight().clip(MidnightShapes.control).background(c.surfaceHigh)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        val ch = e.changes.first()
                        if (ch.pressed && !ch.previousPressed) { haptics.click(); send(Msg.MouseButton(button, true)) }
                        if (!ch.pressed && ch.previousPressed) send(Msg.MouseButton(button, false))
                        ch.consume()
                    }
                }
            },
    )
}

@Composable
private fun KeyCap(icon: ImageVector?, text: String? = null, active: Boolean = false, state: ModState = ModState.OFF, onClick: () -> Unit) {
    val c = LocalMidnight.current
    val haptics = rememberHaptics()
    val bg = when {
        state == ModState.LOCKED || active -> c.accent
        state == ModState.ONE_SHOT -> c.accent.copy(alpha = 0.3f)
        else -> c.surfaceHigh
    }
    val fg = if (state == ModState.LOCKED || active) c.onAccent else c.text
    Box(
        Modifier.height(44.dp).widthIn(min = 48.dp).clip(MidnightShapes.control).background(bg)
            .clickable { haptics.tick(); onClick() }.padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) Icon(icon, text, tint = fg, modifier = Modifier.size(20.dp))
        else Text(text ?: "", color = fg, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun DotGrid() {
    val c = LocalMidnight.current
    Canvas(Modifier.fillMaxSize()) {
        val step = 28.dp.toPx()
        var y = step
        while (y < size.height) {
            var x = step
            while (x < size.width) { drawCircle(c.border, 1.5.dp.toPx(), Offset(x, y)); x += step }
            y += step
        }
    }
}
