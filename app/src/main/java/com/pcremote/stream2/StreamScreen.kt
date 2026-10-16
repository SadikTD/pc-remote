package com.pcremote.stream2

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.BitmapFactory
import android.util.Base64
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import com.pcremote.stream.ZoomPan
import com.pcremote.ui.theme.MidnightShapes
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.pcremote.R
import com.pcremote.agent.AgentState
import com.pcremote.agent.Msg
import com.pcremote.modes.KeepScreenOn
import com.pcremote.modes.TrackpadModel
import com.pcremote.modes.TypingField
import com.pcremote.modes.rememberAgentSession
import com.pcremote.modes.toMsg
import com.pcremote.prs.CodecPicker
import com.pcremote.prs.Cursor
import com.pcremote.settings.SettingsModel
import com.pcremote.stream.KeyEvt
import com.pcremote.stream.LinkState
import com.pcremote.stream.Shortcut
import com.pcremote.stream.StreamOverlay
import com.pcremote.stream.ToolbarActions
import com.pcremote.stream.ToolbarState
import com.pcremote.ui.components.GhostButton
import com.pcremote.ui.components.PrimaryButton
import com.pcremote.ui.theme.LocalMidnight
import com.pcremote.ui.theme.rememberHaptics
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/** Screen mode on our own engine: fullscreen video, trackpad or direct touch, keyboard, PC cursor. */
@Composable
fun StreamScreen(pcId: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val session = rememberAgentSession(pcId) ?: run { LaunchedEffect(Unit) { onBack() }; return }
    val settings = remember { SettingsModel.get(ctx) }
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val agent by session.conn.state.collectAsState()
    val toolbar = remember { ToolbarState().apply { pcName = session.pc.name; connecting = session.pc.name; coachShown = coachSeen(ctx) } }

    Immersive()
    KeepScreenOn()
    BackHandler(onBack = onBack)

    var surface by remember { mutableStateOf<SurfaceHolder?>(null) }
    var cursorShape by remember { mutableStateOf<Triple<ImageBitmap, Int, Int>?>(null) }
    var pipe by remember { mutableStateOf<PrsPipeline?>(null) }
    var attempt by remember { mutableIntStateOf(0) }

    // one socket per stream.start: STUN runs on it before asking, so its candidates go in the request
    val nextSock = remember { java.util.concurrent.atomic.AtomicReference<com.pcremote.remote.PeerSocket?>() }
    val controller = remember(attempt) {
        StreamController(scope, session::send, session.conn.incoming, { ready, failed ->
            val sock = nextSock.getAndSet(null) ?: com.pcremote.remote.PeerSocket()
            PrsPipeline(ready, session.conn.connectedHost ?: session.pc.allHosts.first(), sock, surface!!.surface, session.pc.relay,
                { m -> session.send(m) }, failed, onEnded = { scope.launch { onBack() } }).also { pipe = it }
        }, CodecPicker.supported(), prepare = {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val s = com.pcremote.remote.PeerSocket(com.pcremote.remote.PeerSocket.CLOUDFLARE_STUN)
                nextSock.getAndSet(s)?.close()
                s.candidates()
            }
        })
    }
    val ui by controller.ui.collectAsState()

    // start once the agent is online and the surface exists; stop when either goes away
    val ready = agent is AgentState.Online && surface != null
    DisposableEffect(controller, ready) {
        if (ready) controller.start(request(ctx).copy(display = savedDisplay(ctx, pcId)))
        onDispose { controller.stop(); pipe = null; nextSock.getAndSet(null)?.close() }
    }
    val zoom = remember { ZoomPan() }
    LaunchedEffect(session) {
        session.conn.incoming.collect { m ->
            if (m is Msg.StreamCand) pipe?.addCandidate(m.c)
            if (m is Msg.StreamDisplays) {
                if (m.current != toolbar.display) zoom.reset()
                toolbar.displays = m.list; toolbar.display = m.current
            }
            if (m is Msg.CursorShape) runCatching {
                val b = Base64.decode(m.png, Base64.DEFAULT)
                cursorShape = Triple(BitmapFactory.decodeByteArray(b, 0, b.size).asImageBitmap(), m.hx, m.hy)
            }
        }
    }

    // stats → toolbar
    val stats by remember(pipe) { pipe?.stats ?: MutableStateFlow(com.pcremote.prs.Stats()) }.collectAsState()
    val streaming = ui as? StreamUi.Streaming
    toolbar.connected = streaming != null
    toolbar.connecting = if (streaming == null && ui is StreamUi.Connecting) session.pc.name else null
    toolbar.offerLowerQuality = false
    toolbar.link = if (stats.lossPct > 5f || stats.rttMs > 80) LinkState.POOR else LinkState.GOOD
    toolbar.perfText = streaming?.let {
        stringResource(R.string.pcr_s2_perf, it.codec.uppercase(), it.w, it.h, stats.fps, stats.kbps / 1000f,
            stats.rttMs / 2 + stats.decodeMs, stats.rttMs, stats.lossPct) // network one-way + decode/render
    }.orEmpty()

    // latest controller: a retry builds a new one, and handlers created earlier must reach it
    val currentController by rememberUpdatedState(controller)
    val send: (Msg) -> Unit = { currentController.sendInput(it) }
    val trackpad = remember { TrackpadModel(settings.pointerSpeed, settings.naturalScroll) }
    val direct = remember { DirectTouchModel(settings.naturalScroll) }
    val actions = remember {
        object : ToolbarActions {
            override fun shortcut(s: Shortcut) = s.events().forEach { send(it.toMsg()) }
            override fun keyboard() { toolbar.keyboardOpen = !toolbar.keyboardOpen }
            override fun setTrackpad(on: Boolean) { toolbar.trackpad = on }
            override fun lowerQuality() {}
            override fun disconnect() = onBack()
            override fun keys(events: List<KeyEvt>) = events.forEach { send(it.toMsg()) }
            override fun coachDone() = markCoachSeen(ctx)
            override fun selectDisplay(id: String) {
                currentController.selectDisplay(id); saveDisplay(ctx, pcId, id); zoom.reset()
            }
        }
    }

    val c = LocalMidnight.current
    var box by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(box) { zoom.setContentSize(box.width.toFloat(), box.height.toFloat()) }
    Box(Modifier.fillMaxSize().background(Color.Black).onSizeChanged { box = it }) {
        val pic = streaming?.let { fit(box, it.w, it.h) }
        // the video surface, letterboxed to the stream's aspect
        AndroidView(
            factory = { context ->
                SurfaceView(context).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(h: SurfaceHolder) { surface = h }
                        override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
                        override fun surfaceDestroyed(h: SurfaceHolder) { surface = null }
                    })
                }
            },
            // letterboxed to the stream's aspect, then zoomed (placed in the layout phase: no recomposition per pinch frame)
            modifier = if (pic != null) Modifier.layout { m, c ->
                val z = zoom.scale
                val pl = m.measure(Constraints.fixed((pic.w * z).roundToInt(), (pic.h * z).roundToInt()))
                layout(c.maxWidth, c.maxHeight) { pl.place((pic.left * z + zoom.tx).roundToInt(), (pic.top * z + zoom.ty).roundToInt()) }
            } else Modifier.fillMaxSize(),
        )
        // the PC's pointer (desktop capture leaves it out; the host sends it separately)
        if (streaming != null && pic != null) {
            val cur by remember(pipe) { pipe?.cursor ?: MutableStateFlow(Cursor(0, 0, false)) }.collectAsState()
            val shape = cursorShape
            val sx = pic.w.toFloat() / streaming.w; val sy = pic.h.toFloat() / streaming.h
            // zoomed trackpad: the view follows the pointer
            LaunchedEffect(cur, toolbar.trackpad) {
                if (toolbar.trackpad && zoom.isZoomed && cur.visible)
                    zoom.follow(pic.left + cur.x * sx, pic.top + cur.y * sy, minOf(box.width, box.height) * 0.12f)
            }
            Canvas(Modifier.fillMaxSize()) {
                if (!cur.visible) return@Canvas
                withTransform({ translate(zoom.tx, zoom.ty); scale(zoom.scale, zoom.scale, Offset.Zero) }) {
                if (shape != null) {
                    val (img, hx, hy) = shape
                    drawImage(img, dstOffset = IntOffset((pic.left + (cur.x - hx) * sx).toInt(), (pic.top + (cur.y - hy) * sy).toInt()),
                        dstSize = IntSize(maxOf(1, (img.width * sx).toInt()), maxOf(1, (img.height * sy).toInt())))
                } else {
                    drawCircle(Color.White, 6.dp.toPx() / zoom.scale, Offset(pic.left + cur.x * sx, pic.top + cur.y * sy))
                }
                }
            }
        }
        // touch input over the whole screen
        if (streaming != null && pic != null) {
            Box(Modifier.fillMaxSize().pointerInput(toolbar.trackpad, pic) {
                kotlinx.coroutines.coroutineScope {
                    val gestures = this
                    var hold: Job? = null
                    var view = false // this gesture zooms/pans the view instead of driving the PC
                    var span0 = 0f // finger distance when the second finger landed
                    val pinchStart = 40.dp.toPx()
                    fun emit(ms: List<Msg>) { ms.forEach(send); if (ms.any { it is Msg.MouseButton && it.down }) haptics.click() }
                    try {
                        awaitPointerEventScope {
                            while (true) {
                                val e = awaitPointerEvent()
                                val t = e.changes.firstOrNull()?.uptimeMillis ?: 0L
                                val down = e.changes.filter { it.pressed }
                                if (down.size < 2) span0 = 0f
                                if (down.size == 2 && !view) {
                                    val span = (down[0].position - down[1].position).getDistance()
                                    if (span0 == 0f) span0 = span
                                    // fingers spreading or pinching zoom; a zoomed direct-touch view pans with two fingers
                                    if (abs(span - span0) > pinchStart || (!toolbar.trackpad && zoom.isZoomed)) {
                                        view = true; hold?.cancel(); emit(trackpad.cancel() + direct.cancel())
                                    }
                                }
                                if (view) {
                                    if (down.size >= 2) {
                                        val (a, b) = down
                                        val prevSpan = (a.previousPosition - b.previousPosition).getDistance()
                                        val mid = (a.position + b.position) / 2f; val prevMid = (a.previousPosition + b.previousPosition) / 2f
                                        if (prevSpan > 0f) zoom.pinch((a.position - b.position).getDistance() / prevSpan, mid.x, mid.y)
                                        zoom.pan(mid.x - prevMid.x, mid.y - prevMid.y)
                                    }
                                    e.changes.forEach { it.consume() }
                                    if (down.isEmpty()) view = false
                                    continue
                                }
                                for (ch in e.changes) {
                                    val id = ch.id.value.toInt()
                                    val p = ch.position
                                    val fx = (zoom.toStreamX(p.x) - pic.left) / pic.w; val fy = (zoom.toStreamY(p.y) - pic.top) / pic.h
                                    when {
                                        ch.pressed && !ch.previousPressed -> {
                                            hold?.cancel()
                                            if (toolbar.trackpad) {
                                                emit(trackpad.down(id, p.x, p.y, t))
                                                hold = gestures.launch { delay(TrackpadModel.HOLD_MS); emit(trackpad.hold(id, t + TrackpadModel.HOLD_MS)) }
                                            } else {
                                                emit(direct.down(fx, fy, p.x, p.y, t))
                                                hold = gestures.launch { delay(DirectTouchModel.LONG_MS); emit(direct.longPress()) }
                                            }
                                        }
                                        ch.pressed && ch.positionChanged() -> {
                                            if ((p - ch.previousPosition).getDistance() > 2f) hold?.cancel()
                                            emit(if (toolbar.trackpad) trackpad.move(id, p.x, p.y, t) else direct.move(fx, fy, p.x, p.y, p.y - ch.previousPosition.y))
                                        }
                                        !ch.pressed && ch.previousPressed -> { hold?.cancel(); emit(if (toolbar.trackpad) trackpad.up(id, t) else direct.up(t)) }
                                    }
                                    ch.consume()
                                }
                            }
                        }
                    } finally { hold?.cancel(); emit(trackpad.cancel() + direct.cancel()) }
                }
            })
        }
        StreamOverlay(toolbar, actions)
        // On the lock or sign-in screen: one tap and a fingerprint types the PIN
        val locked by session.conn.locked.collectAsState()
        val unlock = com.pcremote.modes.rememberUnlocker(pcId, session::send, session.conn.incoming)
        val canUnlock = (agent as? com.pcremote.agent.AgentState.Online)?.caps?.contains("unlock") == true
        androidx.compose.animation.AnimatedVisibility(locked && canUnlock && streaming != null, Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp)) {
            com.pcremote.ui.components.PrimaryButton(androidx.compose.ui.res.stringResource(R.string.pcr_unlock_fp), unlock,
                icon = Icons.Rounded.Fingerprint)
        }
        if (zoom.isZoomed) ZoomChip(zoom.scale, Modifier.align(Alignment.TopCenter).padding(top = 12.dp), zoom::reset)
        if (toolbar.keyboardOpen && streaming != null) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(c.surface.copy(alpha = 0.9f))) {
                TypingField(send, toolbar.bar) { toolbar.barTick++ }
            }
        }
        (ui as? StreamUi.Failed)?.let { f ->
            Failure(f, retry = { attempt++ }, back = onBack)
        }
    }
}

@Composable
private fun Failure(f: StreamUi.Failed, retry: () -> Unit, back: () -> Unit) {
    val c = LocalMidnight.current
    val msg = when (f.code) {
        "no_encoder" -> R.string.pcr_s2_err_no_encoder
        "capture_failed" -> R.string.pcr_s2_err_capture
        "busy" -> R.string.pcr_s2_err_busy
        "no_decoder" -> R.string.pcr_s2_err_no_decoder
        else -> R.string.pcr_s2_err_timeout
    }
    Box(Modifier.fillMaxSize().background(c.bg.copy(alpha = 0.96f)), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 420.dp).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.pcr_s2_err_title), color = c.text, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(msg), color = c.textDim, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GhostButton(stringResource(R.string.pcr_s2_back), onClick = back)
                PrimaryButton(stringResource(R.string.pcr_s2_retry), onClick = retry)
            }
        }
    }
}

private data class Pic(val left: Int, val top: Int, val w: Int, val h: Int)

private fun fit(box: IntSize, w: Int, h: Int): Pic? {
    if (box.width == 0 || box.height == 0 || w == 0 || h == 0) return null
    val s = minOf(box.width.toFloat() / w, box.height.toFloat() / h)
    val pw = (w * s).toInt(); val ph = (h * s).toInt()
    return Pic((box.width - pw) / 2, (box.height - ph) / 2, pw, ph)
}

/**
 * Auto quality: the phone's own landscape resolution and refresh rate, up to 50 Mbps (the host adapts
 * bitrate to the network and fits the PC's aspect inside). A chosen preset caps all three.
 */
private fun request(ctx: Context): StreamRequest {
    val dm = ctx.resources.displayMetrics
    val w = maxOf(dm.widthPixels, dm.heightPixels); val h = minOf(dm.widthPixels, dm.heightPixels)
    @Suppress("DEPRECATION")
    val hz = (ctx.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.refreshRate
    if (!com.pcremote.quality.QualityPrefs.isAuto(ctx)) {
        val p = com.pcremote.quality.QualityPrefs.current(ctx)
        return StreamRequest(minOf(w, p.width), minOf(h, p.height), p.fps, p.bitrateKbps)
    }
    // On mobile data, cap the stream: it keeps a relayed 4G path inside its capacity and the data
    // plan sane (8 Mbps ≈ 3.6 GB an hour). Wi-Fi gets up to 50 Mbps; the host adapts below that.
    val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
    val cellular = cm?.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) == true
    return StreamRequest(w, h, hz.toInt().coerceIn(30, if (cellular) 60 else 120), if (cellular) 8_000 else 50_000)
}

@Composable
private fun Immersive() {
    val activity = LocalContext.current as? Activity ?: return
    DisposableEffect(activity) {
        val prev = activity.requestedOrientation
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val ctl = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctl.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            ctl.show(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = prev
        }
    }
}

@Composable
private fun ZoomChip(scale: Float, modifier: Modifier, reset: () -> Unit) {
    val c = LocalMidnight.current
    Row(
        modifier.clip(MidnightShapes.pill).background(c.surface.copy(alpha = 0.86f)).clickable(onClick = reset)
            .padding(start = 14.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("%.1f×".format(scale), color = c.text, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.width(6.dp))
        Icon(Icons.Rounded.Close, stringResource(R.string.pcr_zoom_reset), tint = c.textDim, modifier = Modifier.size(16.dp))
    }
}

/** The display last picked for this PC (empty = the one set on the PC). */
private fun savedDisplay(ctx: Context, pcId: String) =
    ctx.getSharedPreferences("pcr_stream2", Context.MODE_PRIVATE).getString("display_$pcId", "").orEmpty()
private fun saveDisplay(ctx: Context, pcId: String, id: String) =
    ctx.getSharedPreferences("pcr_stream2", Context.MODE_PRIVATE).edit().putString("display_$pcId", id).apply()

private fun coachSeen(ctx: Context) = ctx.getSharedPreferences("pcr_stream2", Context.MODE_PRIVATE).getBoolean("coach", false)
private fun markCoachSeen(ctx: Context) = ctx.getSharedPreferences("pcr_stream2", Context.MODE_PRIVATE).edit().putBoolean("coach", true).apply()
