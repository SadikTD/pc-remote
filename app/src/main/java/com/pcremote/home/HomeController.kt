package com.pcremote.home

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.PairingManager
import com.pcremote.agent.AgentConnection
import com.pcremote.agent.AgentState
import com.pcremote.agent.Msg
import com.pcremote.agent.PairedPc
import com.pcremote.agent.PcStore
import com.pcremote.moonlight.Computers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Live data for Home: Moonlight computers + paired agent PCs, with agent connections that are
 * open only while Home is visible ([start]/[stop]), so nothing runs in the background.
 */
class HomeController(ctx: Context, private val scope: CoroutineScope, val computers: Computers) {
    val store = PcStore.get(ctx)
    private val paired = MutableStateFlow(store.all())
    private val live = MutableStateFlow<Map<String, AgentState>>(emptyMap())
    private val streaming = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    private val _thumbs = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())
    val thumbs: StateFlow<Map<String, ImageBitmap>> = _thumbs
    private val conns = mutableMapOf<String, AgentConnection>()
    private val jobs = mutableListOf<Job>()

    val cards: StateFlow<List<PcCardUi>> = combine(computers.pcs, paired, live, streaming) { ml, agents, l, s ->
        HomeModel.cards(ml.values.map { it.toMl() }, agents, l, s)
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun refreshStore() { paired.value = store.all() }

    fun connection(id: String): AgentConnection? = conns[id]

    fun start() {
        computers.startPolling()
        refreshStore()
        for (pc in paired.value) if (pc.id !in conns) open(pc)
    }

    private fun open(pc: PairedPc) {
        val c = AgentConnection(pc.hosts, pc.port, pc.fingerprint, scope)
        conns[pc.id] = c
        jobs += scope.launch {
            c.state.collect { st ->
                live.value = live.value + (pc.id to st)
                if (st is AgentState.Online && "thumb" in st.caps) c.send(Msg.ThumbSub(true))
            }
        }
        jobs += scope.launch {
            c.incoming.collect { m -> if (m is Msg.Status) streaming.value = streaming.value + (pc.id to m.streaming) }
        }
        jobs += scope.launch {
            c.binary.collect { b ->
                if (b.isNotEmpty() && b[0].toInt() == 2) {
                    BitmapFactory.decodeByteArray(b, 1, b.size - 1)?.let { _thumbs.value = _thumbs.value + (pc.id to it.asImageBitmap()) }
                }
            }
        }
        scope.launch { c.hello(pc.token, Build.MODEL) }
    }

    fun stop() {
        computers.stopPolling()
        jobs.forEach { it.cancel() }; jobs.clear()
        conns.values.forEach { it.close() }; conns.clear()
        live.value = emptyMap()
    }

    fun remove(card: PcCardUi) {
        if (!card.legacy) { conns.remove(card.id)?.close(); store.remove(card.id); refreshStore() }
        card.moonlightUuid?.let { computers.forget(it) }
    }

    fun rename(id: String, name: String) {
        store.all().firstOrNull { it.id == id }?.let { store.upsert(it.copy(name = name)); refreshStore() }
    }

    /** Details for Wake-on-LAN: Moonlight's if linked, else built from the agent PC's MACs and hosts. */
    fun wolTarget(card: PcCardUi): ComputerDetails? {
        card.moonlightUuid?.let { u -> computers.pcs.value[u]?.let { if (it.macAddress != null) return it } }
        val pc = paired.value.firstOrNull { it.id == card.id } ?: return null
        return ComputerDetails().apply {
            macAddress = pc.macs.firstOrNull() ?: return null
            localAddress = ComputerDetails.AddressTuple(pc.hosts.first(), 47989)
        }
    }
}

private fun ComputerDetails.toMl() = MlPc(
    uuid = uuid, name = name ?: "PC",
    online = when (state) { ComputerDetails.State.ONLINE -> true; ComputerDetails.State.OFFLINE -> false; else -> null },
    paired = pairState == PairingManager.PairState.PAIRED,
    hasMac = macAddress != null,
    address = activeAddress?.address,
)
