package com.pcremote.modes

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.pcremote.agent.AgentState
import com.pcremote.agent.Msg

/** Two-way text clipboard sync without echo loops. */
class ClipSync {
    private var last: String? = null

    /** The phone's clipboard changed; returns the message to send, if any. */
    fun onLocalChange(text: String): Msg.Clip? {
        if (text == last || text.toByteArray().size > MAX_TEXT) return null
        last = text
        return Msg.Clip("text/plain", text)
    }

    /** The PC sent its clipboard; returns the text to put on the phone, if any. */
    fun onRemote(m: Msg.Clip): String? {
        if (m.mime != "text/plain" || m.data == last) return null
        last = m.data
        return m.data
    }

    companion object { const val MAX_TEXT = 1_000_000 }
}

/**
 * Keeps the clipboards in sync while a mode screen is open. Android only lets apps read the
 * clipboard while they're in the foreground, which is exactly when this runs.
 */
@Composable
fun ClipboardSync(session: AgentSession?, state: AgentState) {
    val ctx = LocalContext.current
    val cm = remember { ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
    val sync = remember { ClipSync() }
    val online = state is AgentState.Online && "clip" in state.caps
    LaunchedEffect(online) {
        if (!online) return@LaunchedEffect
        session?.send(Msg.ClipSub(true))
        // Only copies made while a mode is open go to the PC: reading the existing clipboard on open
        // would overwrite what the user just copied on the PC (and Android 12+ shows a paste notice)
        session?.conn?.incoming?.collect { m ->
            if (m is Msg.Clip) sync.onRemote(m)?.let { cm.setPrimaryClip(ClipData.newPlainText("PC", it)) }
        }
    }
    DisposableEffect(online) {
        val l = ClipboardManager.OnPrimaryClipChangedListener {
            cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()?.let { sync.onLocalChange(it)?.let { m -> session?.send(m) } }
        }
        if (online) cm.addPrimaryClipChangedListener(l)
        onDispose { cm.removePrimaryClipChangedListener(l) }
    }
}
