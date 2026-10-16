package com.pcremote.stream2

import android.util.Base64
import android.view.Surface
import com.pcremote.agent.Msg
import com.pcremote.prs.AudioPacket
import com.pcremote.prs.AudioPlayer
import com.pcremote.prs.Crypt
import com.pcremote.prs.Cursor
import com.pcremote.prs.Frame
import com.pcremote.prs.Receiver
import com.pcremote.prs.ReceiverListener
import com.pcremote.prs.Stats
import com.pcremote.prs.UdpTransport
import com.pcremote.prs.VideoDecoder
import kotlinx.coroutines.flow.StateFlow
import kotlin.concurrent.thread

/** The real chain: UDP → [Receiver] → [VideoDecoder] on `surface`, plus Opus audio. */
class PrsPipeline(
    ready: Msg.StreamReady,
    host: String,
    private val surface: Surface,
    private val onDecoderFailed: () -> Unit,
    private val onEnded: () -> Unit,
) : Pipeline {
    private val audio = AudioPlayer()
    @Volatile private lateinit var decoder: VideoDecoder
    private val receiver: Receiver

    init {
        val listener = object : ReceiverListener {
            override fun onFrame(f: Frame) = decoder.submit(f)
            override fun onAudio(a: AudioPacket) = audio.play(a)
            override fun onBye() = onEnded()
        }
        receiver = Receiver(UdpTransport(host, ready.port), Crypt(Base64.decode(ready.key, Base64.DEFAULT), ready.session.toInt()), listener)
        decoder = newDecoder(ready)
        thread(name = "pcr-rx", priority = Thread.MAX_PRIORITY) { receiver.run() }
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
        decoder.release()
        audio.release()
        thread(name = "pcr-rx-stop") { receiver.stop() } // BYE may need a DNS lookup: off the main thread
    }
}
