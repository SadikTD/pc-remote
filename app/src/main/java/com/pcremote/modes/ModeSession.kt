package com.pcremote.modes

import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.limelight.R
import com.pcremote.agent.AgentConnection
import com.pcremote.agent.AgentState
import com.pcremote.agent.FailReason
import com.pcremote.agent.Msg
import com.pcremote.agent.PairedPc
import com.pcremote.agent.PcStore
import com.pcremote.ui.components.GhostButton
import com.pcremote.ui.components.StatusDot
import com.pcremote.ui.theme.LocalMidnight
import com.pcremote.ui.theme.MidnightShapes
import kotlinx.coroutines.launch

/** A live agent connection owned by one mode screen. */
class AgentSession(val pc: PairedPc, val conn: AgentConnection) {
    fun send(m: Msg) = conn.send(m)
}

/** Opens an agent connection for [pcId] while the calling screen is on screen. */
@Composable
fun rememberAgentSession(pcId: String): AgentSession? {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pc = remember(pcId) { PcStore.get(ctx).all().firstOrNull { it.id == pcId } } ?: return null
    val session = remember(pcId) { AgentSession(pc, AgentConnection(pc.hosts, pc.port, pc.fingerprint, scope)) }
    DisposableEffect(session) {
        scope.launch { session.conn.hello(pc.token, Build.MODEL) }
        onDispose { session.conn.close() }
    }
    return session
}

/** Keeps the phone screen on while a mode is open. */
@Composable
fun KeepScreenOn() {
    val window = (LocalContext.current as? android.app.Activity)?.window ?: return
    DisposableEffect(window) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

/** Shared chrome for every mode: back, PC name, live status, power, and a connection banner. */
@Composable
fun ModeScaffold(
    title: Int,
    session: AgentSession?,
    onBack: () -> Unit,
    onPower: (() -> Unit)? = null,
    content: @Composable ColumnScope.(AgentState) -> Unit,
) {
    val c = LocalMidnight.current
    val scope = rememberCoroutineScope()
    val state by (session?.conn?.state ?: remember { kotlinx.coroutines.flow.MutableStateFlow(AgentState.Closed) }).collectAsState()
    BackHandler(onBack = onBack)
    KeepScreenOn()
    Column(Modifier.fillMaxSize().background(c.bg).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = c.text) }
            Column(Modifier.weight(1f)) {
                Text(stringResource(title), color = c.text, style = MaterialTheme.typography.titleLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val online = state is AgentState.Online
                    StatusDot(if (online) c.ok else if (state is AgentState.Failed) c.bad else c.warn, live = online, size = 7.dp)
                    Text(session?.pc?.name ?: "", color = c.textDim, style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            var power by remember { mutableStateOf(false) }
            if (power && session != null) PowerSheet(session.pc.name, { session.send(it) }) { power = false }
            if (onPower != null && state is AgentState.Online && "power" in (state as AgentState.Online).caps) IconButton({ power = true }) { Icon(Icons.Rounded.PowerSettingsNew, stringResource(R.string.pcr_power), tint = c.text) }
        }
        AnimatedVisibility(state !is AgentState.Online) {
            val msg = when ((state as? AgentState.Failed)?.reason) {
                null -> R.string.pcr_conn_connecting
                FailReason.REVOKED, FailReason.UNAUTHORIZED -> R.string.pcr_conn_revoked
                FailReason.CERT_MISMATCH -> R.string.pcr_cert_mismatch
                else -> R.string.pcr_conn_unreachable
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).background(c.surfaceHigh, MidnightShapes.control)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(msg), color = c.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                if (state is AgentState.Failed && session != null) {
                    GhostButton(stringResource(R.string.pcr_retry), {
                        scope.launch { session.conn.hello(session.pc.token, Build.MODEL) }
                    })
                }
            }
        }
        if (session == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.pcr_conn_unknown_pc), color = c.textDim)
            }
        } else {
            ClipboardSync(session, state)
            content(state)
        }
    }
}
