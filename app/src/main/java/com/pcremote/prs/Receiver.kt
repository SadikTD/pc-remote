package com.pcremote.prs

import com.pcremote.agent.Msg
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer

/** Where PRS datagrams go: plain UDP now, a TURN relay later. */
interface Transport {
    fun send(b: ByteArray)
    /** Bytes received into `buf`, or -1 on timeout. */
    fun receive(buf: ByteArray, timeoutMs: Int): Int
    fun close()
}

class UdpTransport(host: InetAddress, port: Int) : Transport {
    private val peer = InetSocketAddress(host, port)
    private val sock = DatagramSocket().apply { receiveBufferSize = 4 shl 20 }
    private val pkt = DatagramPacket(ByteArray(0), 0)

    override fun send(b: ByteArray) { runCatching { sock.send(DatagramPacket(b, b.size, peer)) } }

    override fun receive(buf: ByteArray, timeoutMs: Int): Int {
        sock.soTimeout = maxOf(1, timeoutMs)
        pkt.setData(buf)
        while (true) {
            try { sock.receive(pkt) } catch (_: SocketTimeoutException) { return -1 }
            if (pkt.address == peer.address && pkt.port == peer.port) return pkt.length
        }
    }

    override fun close() = sock.close()
}

data class AudioPacket(val id: Int, val captureUs: Long, val opus: ByteArray)
data class Cursor(val x: Int, val y: Int, val visible: Boolean)
data class Stats(val rttMs: Int = 0, val lossPct: Float = 0f, val kbps: Int = 0, val fps: Int = 0, val dropped: Int = 0, val jitterMs: Int = 0)

interface ReceiverListener {
    fun onFrame(f: Frame)
    fun onAudio(a: AudioPacket) {}
    fun onBye() {}
}

/**
 * The phone end of a PRS session: PROBE handshake and keepalive, frame assembly with FEC, REPORT every
 * 100 ms, IDR requests, input. [run] blocks on its own thread; everything else is safe from any thread.
 */
class Receiver(
    private val transport: Transport,
    private val crypt: Crypt,
    private val listener: ReceiverListener,
    private val clock: () -> Long = { System.nanoTime() / 1000 },
) {
    private val sendLock = Any()
    private var seq = 0
    private val input = InputSender()
    private val assembler = FrameAssembler(::delivered, ::requestIdr)

    private val _cursor = MutableStateFlow(Cursor(0, 0, false))
    val cursor: StateFlow<Cursor> = _cursor
    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats

    /** Average decode time, fed by the decoder. */
    @Volatile var decodeMs = 0

    // receive-thread state
    private var lastProbeUs = Long.MIN_VALUE / 2
    private var lastVideoUs = Long.MIN_VALUE / 2
    private var lastReportUs = 0L
    private var lastStatsUs = 0L
    private var peerT = 0L
    private var rttUs = 0L
    private var bestRttUs = Long.MAX_VALUE
    @Volatile private var offsetUs = 0L // host clock − our clock
    private var maxSeq = -1
    private var periodStartSeq = -1
    private var periodReceived = 0
    private var lossPermille = 0
    private var lastFrameId = 0
    private var jitterUs = 0.0
    private var prevArrival = 0L
    private var prevCapture = 0L
    private var statFrames = 0
    private var statBytes = 0L
    @Volatile private var running = true

    fun run() {
        val buf = ByteArray(2048)
        while (running) {
            val n = runCatching { transport.receive(buf, 5) }.getOrElse { if (running) -1 else break }
            if (n > 0) onPacket(buf, n, clock())
            step(clock())
        }
    }

    fun stop() {
        if (!running) return
        running = false
        send(Header(Prs.BYE), ByteArray(0))
        transport.close()
    }

    fun requestIdr() = send(Header(Prs.IDR), ByteArray(0))

    fun sendInput(m: Msg) = send(Header(Prs.INPUT), input.payload(m))

    /** Converts a host capture timestamp to our clock (PROBE-estimated offset). */
    fun hostToLocalUs(hostUs: Long) = hostUs - offsetUs

    internal fun onPacket(buf: ByteArray, len: Int, nowUs: Long) {
        val (h, plain) = crypt.open(Crypt.HOST_TO_PHONE, buf, len) ?: return
        if (maxSeq < 0) { maxSeq = h.seq; periodStartSeq = h.seq - 1 }
        if (h.seq - maxSeq > 0) maxSeq = h.seq
        periodReceived++
        statBytes += len
        when (h.typ) {
            Prs.VIDEO, Prs.FEC -> { lastVideoUs = nowUs; assembler.onShard(h, plain, nowUs) }
            Prs.AUDIO -> if (plain.size > 8) listener.onAudio(AudioPacket(h.frameId, ByteBuffer.wrap(plain).getLong(0), plain.copyOfRange(8, plain.size)))
            Prs.CURSOR -> if (plain.size >= 5) {
                val b = ByteBuffer.wrap(plain)
                _cursor.value = Cursor(b.getShort(0).toInt(), b.getShort(2).toInt(), plain[4].toInt() != 0)
            }
            Prs.PROBE -> if (plain.size >= 16) {
                val b = ByteBuffer.wrap(plain)
                peerT = b.getLong(0)
                val echo = b.getLong(8)
                if (echo != 0L && nowUs - echo in 0..10_000_000) {
                    rttUs = nowUs - echo
                    if (rttUs <= bestRttUs) { bestRttUs = rttUs; offsetUs = peerT + rttUs / 2 - nowUs }
                }
            }
            Prs.BYE -> listener.onBye()
        }
    }

    internal fun step(nowUs: Long) {
        assembler.tick(nowUs)
        // fast probing until video flows; back to fast if it stops (network change, host restart)
        val gap = if (nowUs - lastVideoUs < 1_000_000) 1_000_000 else 200_000
        if (nowUs - lastProbeUs >= gap) {
            lastProbeUs = nowUs
            send(Header(Prs.PROBE), ByteBuffer.allocate(16).putLong(nowUs).putLong(peerT).array())
        }
        if (nowUs - lastReportUs >= 100_000) { lastReportUs = nowUs; report() }
        if (nowUs - lastStatsUs >= 1_000_000) {
            val secs = (nowUs - lastStatsUs) / 1e6
            _stats.value = Stats((rttUs / 1000).toInt(), lossPermille / 10f, (statBytes * 8 / 1000 / secs).toInt(),
                (statFrames / secs).toInt(), assembler.dropped, (jitterUs / 1000).toInt())
            lastStatsUs = nowUs; statFrames = 0; statBytes = 0
        }
    }

    private fun report() {
        if (maxSeq >= 0) {
            val expected = maxSeq - periodStartSeq
            lossPermille = if (expected <= 0) 0 else ((expected - periodReceived).coerceAtLeast(0) * 1000L / expected).toInt().coerceAtMost(1000)
            periodStartSeq = maxSeq; periodReceived = 0
        }
        send(Header(Prs.REPORT), reportBytes(lastFrameId, lossPermille, (jitterUs / 1000).toInt(), decodeMs, (rttUs / 1000).toInt()))
    }

    private fun delivered(f: Frame) {
        // RFC 3550 interarrival jitter, against host capture times
        if (prevArrival != 0L) {
            val d = (f.receivedUs - prevArrival) - (f.header.captureUs - prevCapture)
            jitterUs += (kotlin.math.abs(d) - jitterUs) / 16
        }
        prevArrival = f.receivedUs; prevCapture = f.header.captureUs
        lastFrameId = f.id; statFrames++
        listener.onFrame(f)
    }

    private fun send(h: Header, plain: ByteArray) = synchronized(sendLock) {
        transport.send(crypt.seal(Crypt.PHONE_TO_HOST, h.copy(seq = seq++), plain))
    }

    companion object {
        fun reportBytes(lastFrame: Int, lossPermille: Int, jitterMs: Int, decodeMs: Int, rttMs: Int): ByteArray =
            ByteBuffer.allocate(16).putInt(lastFrame).putShort(lossPermille.toShort())
                .putShort(jitterMs.coerceIn(0, 65535).toShort()).putShort(decodeMs.coerceIn(0, 65535).toShort())
                .putShort(rttMs.coerceIn(0, 65535).toShort()).putInt(0).array()
    }
}
