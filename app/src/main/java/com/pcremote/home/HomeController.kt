package com.pcremote.home

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.pcremote.agent.AgentConnection
import com.pcremote.agent.AgentState
import com.pcremote.agent.Msg
import com.pcremote.agent.PairedPc
import com.pcremote.agent.PcStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Live data for Home: paired PCs, with agent connections that are
 * open only while Home is visible ([start]/[stop]), so nothing runs in the background.
 */
class HomeController(private val ctx: Context, private val scope: CoroutineScope) {
    val store = PcStore.get(ctx)
    /** Same-network discovery: a paired PC that turns up (or changes address) connects with no taps. */
    private val lan = com.pcremote.discovery.Lan(ctx) { found -> scope.launch { onLan(found) } }
    private val paired = MutableStateFlow<List<PairedPc>>(emptyList())
    private val live = MutableStateFlow<Map<String, AgentState>>(emptyMap())
    private val streaming = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    private val locked = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    private val _thumbs = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())
    val thumbs: StateFlow<Map<String, ImageBitmap>> = _thumbs
    private val conns = mutableMapOf<String, AgentConnection>()
    private val jobs = mutableListOf<Job>()

    val cards: StateFlow<List<PcCardUi>> = combine(paired, live, streaming, locked) { agents, l, s, lk ->
        HomeModel.cards(agents, l, s, lk)
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun refreshStore() { scope.launch(kotlinx.coroutines.Dispatchers.IO) { paired.value = store.all() } }

    fun connection(id: String): AgentConnection? = conns[id]

    private suspend fun onLan(found: com.pcremote.discovery.LanPc) {
        val pc = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { store.all() }.firstOrNull { it.id == found.id } ?: return
        val hosts = com.pcremote.discovery.Lan.prefer(found.host, pc.hosts) ?: return
        val updated = pc.copy(hosts = hosts)
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { store.upsert(updated) }
        paired.value = paired.value.map { if (it.id == pc.id) updated else it }
        // reconnect at the new address unless already connected
        if (live.value[pc.id] !is AgentState.Online && jobs.isNotEmpty()) { conns.remove(pc.id)?.close(); open(updated) }
    }

    fun start() {
        lan.start()
        // Keystore decryption happens off the main thread
        scope.launch {
            val pcs = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { store.all() }
            paired.value = pcs
            for (pc in pcs) if (pc.id !in conns) open(pc)
        }
    }

    private fun open(pc: PairedPc) {
        val c = AgentConnection.forPc(pc, scope)
        conns[pc.id] = c
        jobs += scope.launch {
            c.state.collect { st ->
                live.value = live.value + (pc.id to st)
                if (st is AgentState.Online && "thumb" in st.caps) c.send(Msg.ThumbSub(true))
                if (st is AgentState.Online && "net" in st.caps) c.send(Msg.NetReq)
            }
        }
        jobs += scope.launch {
            c.incoming.collect { m ->
                if (m is Msg.Status) streaming.value = streaming.value + (pc.id to m.streaming)
                if (m is Msg.FileOffer) com.pcremote.modes.TransferHub.get(ctx, pc.id).offer(m) // received in the background
                if (m is Msg.Remote) com.pcremote.agent.RelayInfo.b64url(m.ticket)?.let { t ->
                    val relay = com.pcremote.agent.RelayInfo(m.url, pc.id, m.dev, t)
                    val cur = store.all().firstOrNull { it.id == pc.id }
                    val same = cur?.relay?.let { it.url == relay.url && it.dev == relay.dev && it.ticket contentEquals t } == true
                    if (cur != null && !same) store.upsert(cur.copy(relay = relay))
                }
                if (m is Msg.Net) {
                    val pub = m.public?.takeIf { m.anywhere } // only remember it while access from anywhere is on
                    store.all().firstOrNull { it.id == pc.id }?.takeIf { it.publicHost != pub }?.let { store.upsert(it.copy(publicHost = pub)) }
                }
            }
        }
        jobs += scope.launch { c.locked.collect { locked.value = locked.value + (pc.id to it) } }
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
        lan.stop()
        jobs.forEach { it.cancel() }; jobs.clear()
        conns.values.forEach { it.close() }; conns.clear()
        live.value = emptyMap()
    }

    fun remove(card: PcCardUi) {
        conns.remove(card.id)?.close(); store.remove(card.id); refreshStore()
    }

    fun rename(id: String, name: String) {
        store.all().firstOrNull { it.id == id }?.let { store.upsert(it.copy(name = name)); refreshStore() }
    }

    /**
     * Wakes the card's PC: magic packets on this network, and through Home Assistant (from anywhere)
     * when it is set up. Blocking; null when the PC's MAC is unknown.
     */
    fun wake(card: PcCardUi): Boolean? {
        val pc = paired.value.firstOrNull { it.id == card.id }?.takeIf { it.macs.isNotEmpty() } ?: return null
        val lan = WakeOnLan.send(pc.macs, pc.hosts)
        val ha = HomeAssistant.get(ctx)
        return if (ha.configured) ha.wake(pc.macs).isSuccess || lan else lan
    }

}
