package com.pcremote.stream2

import android.util.Base64
import android.util.Log
import android.view.Surface
import com.pcremote.agent.Msg
import com.pcremote.agent.RELAY_HOST
import com.pcremote.agent.RelayInfo
import com.pcremote.prs.AudioPacket
import com.pcremote.prs.AudioPlayer
import com.pcremote.prs.Crypt
import com.pcremote.prs.Cursor
import com.pcremote.prs.Frame
import com.pcremote.prs.Receiver
import com.pcremote.prs.ReceiverListener
import com.pcremote.prs.Stats
import com.pcremote.prs.VideoDecoder
import com.pcremote.remote.PeerSocket
import com.pcremote.remote.StunMsg
import com.pcremote.remote.TurnCreds
import kotlinx.coroutines.flow.StateFlow
import java.net.InetSocketAddress
import kotlin.concurrent.thread

/**
 * The real chain: [PeerSocket] → [Receiver] → [VideoDecoder] on `surface`, plus Opus audio.
 * Sends to every host candidate until one answers; after 2.5 s of silence, relays through TURN.
 */
class PrsPipeline(
    ready: Msg.StreamReady,
    agentHost: String,
    private val sock: PeerSocket,
    private val surface: Surface,
    relay: RelayInfo?,
    sendAgent: (Msg) -> Unit,
    private val onDecoderFailed: () -> Unit,
    private val onEnded: () -> Unit,
) : Pipeline {
    private val audio = AudioPlayer()
    @Volatile private lateinit var decoder: VideoDecoder
    private val receiver: Receiver
    @Volatile private var closed = false

    init {
        val direct = agentHost.takeIf { it != RELAY_HOST }?.let { StunMsg.parseHostPort("$it:${ready.port}") }
        sock.setTargets(listOfNotNull(direct) + ready.cands.mapNotNull(StunMsg::parseHostPort))
        val listener = object : ReceiverListener {
            override fun onFrame(f: Frame) = decoder.submit(f)
            override fun onAudio(a: AudioPacket) = audio.play(a)
            override fun onBye() = onEnded()
        }
        receiver = Receiver(sock, Crypt(Base64.decode(ready.key, Base64.DEFAULT), ready.session.toInt()), listener)
        decoder = newDecoder(ready)
        thread(name = "pcr-rx", priority = Thread.MAX_PRIORITY) { receiver.run() }
        if (relay != null) thread(name = "pcr-turn-start") {
            Thread.sleep(2_500)
            if (closed || sock.confirmed()) return@thread
            val t = TurnCreds.fetch(relay) ?: run { Log.i(TAG, "no direct path and no TURN relay configured"); return@thread }
            val server = runCatching { InetSocketAddress(t.host, t.port) }.getOrNull() ?: return@thread
            val relayed = sock.useTurn(server, t.user, t.pass) ?: run { Log.w(TAG, "TURN allocation failed"); return@thread }
            StunMsg.hostPort(relayed)?.let { if (!closed) { Log.i(TAG, "relaying via TURN at $it"); sendAgent(Msg.StreamCand(it)) } }
        }
    }

    private fun newDecoder(r: Msg.StreamReady) = VideoDecoder(surface, r.codec, r.w, r.h, r.fps, { onDecoderFailed() }, receiver::requestIdr)
        .also { d -> d.onRendered = { receiver.decodeMs = d.decodeMs } }

    override val stats: StateFlow<Stats> get() = receiver.stats
    val cursor: StateFlow<Cursor> get() = receiver.cursor

    override fun sendInput(m: Msg) = receiver.sendInput(m)

    override fun reconfigure(ready: Msg.StreamReady) {
        decoder.release() // frees the surface before the new decoder configures on the next keyframe
        decoder = newDecoder(ready)
        receiver.requestIdr()
    }

    override fun close() {
        closed = true
        decoder.release()
        audio.release()
        thread(name = "pcr-rx-stop") { receiver.stop() }
    }

    private companion object { const val TAG = "PcrPipeline" }
}
