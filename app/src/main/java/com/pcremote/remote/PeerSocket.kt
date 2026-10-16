package com.pcremote.remote

import android.util.Log
import com.pcremote.prs.Transport
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * The phone's PRS socket for one stream (docs/remote-protocol.md): learns its own candidates with
 * STUN, sends to every host candidate until an authenticated packet arrives ([confirm]), then only
 * to that address, and can fall back to a TURN relay. A reader thread demuxes STUN/TURN from PRS.
 */
class PeerSocket(private val stunServer: InetSocketAddress? = null) : Transport {
    private val sock = DatagramSocket().apply { receiveBufferSize = 4 shl 20; sendBufferSize = 1 shl 20 }
    private val prs = ArrayBlockingQueue<Pair<ByteArray, InetSocketAddress>>(1024)
    private val pending = ConcurrentHashMap<String, CompletableFuture<StunMsg>>()
    @Volatile private var targets: List<InetSocketAddress> = emptyList()
    @Volatile private var peer: InetSocketAddress? = null
    @Volatile private var lastFrom: InetSocketAddress? = null
    @Volatile var turn: TurnClient? = null; private set
    @Volatile private var closed = false

    val localPort get() = sock.localPort

    private val reader = Thread({ readLoop() }, "pcr-sock").apply { priority = Thread.MAX_PRIORITY; isDaemon = true; start() }

    private fun readLoop() {
        val buf = ByteArray(2048)
        val p = DatagramPacket(buf, buf.size)
        while (!closed) {
            try { p.setData(buf); sock.receive(p) } catch (_: Exception) { if (closed) return else continue }
            val from = p.socketAddress as InetSocketAddress
            val n = p.length
            val first = buf[0].toInt() and 0xFF
            when {
                first in 0x40..0x7F && from == turn?.server -> turn?.unwrap(buf, n)?.let { (data, peerAddr) -> offer(data, peerAddr) }
                first <= 0x03 -> StunMsg.decode(buf, n)?.let { m -> pending.remove(key(m.txid))?.complete(m) }
                else -> offer(buf.copyOf(n), from)
            }
        }
    }

    private fun offer(b: ByteArray, from: InetSocketAddress) { if (!prs.offer(b to from)) { prs.poll(); prs.offer(b to from) } }

    private fun key(t: ByteArray) = t.joinToString("") { "%02x".format(it) }

    /** Sends a STUN/TURN request and waits for the matching response (retransmits at 0, 250, 750 ms). */
    fun transact(m: StunMsg, to: InetSocketAddress, key: ByteArray? = null, timeoutMs: Long = 1500): StunMsg? {
        val f = CompletableFuture<StunMsg>()
        pending[key(m.txid)] = f
        val bytes = m.encode(key)
        val start = System.currentTimeMillis()
        try {
            for (at in longArrayOf(0, 250, 750)) {
                val wait = start + at - System.currentTimeMillis()
                if (wait > 0) runCatching { return f.get(wait, TimeUnit.MILLISECONDS) }
                if (f.isDone) return f.get()
                rawSend(bytes, to)
            }
            return runCatching { f.get(maxOf(1, start + timeoutMs - System.currentTimeMillis()), TimeUnit.MILLISECONDS) }.getOrNull()
        } finally { pending.remove(key(m.txid)) }
    }

    /** This socket's candidates: LAN addresses plus the STUN-reflexive one. Blocking (≤ 1.5 s). */
    fun candidates(): List<String> {
        val port = sock.localPort
        val lan = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>().map { "${it.hostAddress}:$port" }
        }.getOrDefault(emptyList())
        val reflexive = stunServer?.let { s -> transact(StunMsg(StunMsg.BINDING), s)?.let(StunMsg::mapped)?.let(StunMsg::hostPort) }
        return (lan + listOfNotNull(reflexive)).distinct()
    }

    /** Where to send before a peer is confirmed. */
    fun setTargets(t: List<InetSocketAddress>) { targets = t.distinct() }

    fun addTarget(a: InetSocketAddress) { targets = (targets + a).distinct() }

    fun confirmed() = peer != null

    /** Starts relaying through TURN: allocate, permit the host candidates, return the relayed address. */
    fun useTurn(server: InetSocketAddress, user: String, pass: String): InetSocketAddress? {
        val t = TurnClient(this, server, user, pass)
        val relayed = t.allocate() ?: return null
        t.permit(targets)
        turn = t
        return relayed
    }

    private fun rawSend(b: ByteArray, to: InetSocketAddress) { runCatching { sock.send(DatagramPacket(b, b.size, to)) } }

    internal fun sendRaw(b: ByteArray, to: InetSocketAddress) = rawSend(b, to)

    override fun send(b: ByteArray) {
        val t = turn
        val p = peer
        when {
            p != null && t != null && t.isRelayed(p) -> t.send(b, p)
            p != null -> rawSend(b, p)
            else -> {
                for (a in targets) rawSend(b, a)
                t?.let { tc -> for (a in targets) tc.send(b, a) }
            }
        }
    }

    override fun receive(buf: ByteArray, timeoutMs: Int): Int {
        val (b, from) = prs.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS) ?: return -1
        lastFrom = from
        b.copyInto(buf)
        return b.size
    }

    /** The last packet authenticated: talk only to where it came from (follows address changes too). */
    override fun confirm() {
        val f = lastFrom ?: return
        if (f != peer) { Log.i(TAG, "peer ${if (peer == null) "found" else "moved"}: $f${if (turn?.isRelayed(f) == true) " (relay)" else ""}"); peer = f }
    }

    override fun close() {
        closed = true
        turn?.close()
        sock.close()
    }

    companion object {
        private const val TAG = "PcrPeer"
        val CLOUDFLARE_STUN get() = runCatching { InetSocketAddress("stun.cloudflare.com", 3478) }.getOrNull()
    }
}
