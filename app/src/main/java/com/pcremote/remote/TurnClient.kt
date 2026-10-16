package com.pcremote.remote

import android.util.Log
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * RFC 8656 TURN over UDP, long-term credentials, ChannelData for the media. One channel per
 * peer address. Refreshes the allocation every 5 min and permissions/channels every 4 min.
 */
class TurnClient(private val sock: PeerSocket, val server: InetSocketAddress, private val user: String, private val pass: String) {
    private var realm = ""
    private var nonce = ByteArray(0)
    private var key: ByteArray? = null
    private val channels = ConcurrentHashMap<InetSocketAddress, Int>()
    private val peers = ConcurrentHashMap<Int, InetSocketAddress>()
    private var nextChannel = 0x4000
    /** Peers whose packets came through the relay (ChannelData or Data indication). */
    private val viaRelay = ConcurrentHashMap.newKeySet<InetSocketAddress>()
    private val timer = Executors.newSingleThreadScheduledExecutor { Thread(it, "pcr-turn").apply { isDaemon = true } }

    /** Signed request with the current realm/nonce; retries once on 438 (stale nonce) or the first 401. */
    private fun request(build: () -> StunMsg): StunMsg? {
        repeat(2) {
            val m = build()
            if (key != null) m.add(StunMsg.USERNAME, user.toByteArray()).add(StunMsg.REALM, realm.toByteArray()).add(StunMsg.NONCE, nonce)
            val r = sock.transact(m, server, key) ?: return null
            if (r.isSuccess) return r
            val code = r.errorCode
            if ((code == 401 || code == 438) && r.get(StunMsg.NONCE) != null) {
                r.get(StunMsg.REALM)?.let { realm = String(it) }
                nonce = r.get(StunMsg.NONCE)!!
                key = StunMsg.longTermKey(user, realm, pass)
            } else {
                val reason = r.get(StunMsg.ERROR_CODE)?.let { String(it, 4, maxOf(0, it.size - 4)) }.orEmpty()
                Log.w(TAG, "TURN ${"%04x".format(m.type)} error $code $reason"); return null
            }
        }
        return null
    }

    fun allocate(): InetSocketAddress? {
        val r = request { StunMsg(StunMsg.ALLOCATE).add(StunMsg.REQUESTED_TRANSPORT, byteArrayOf(17, 0, 0, 0)) } ?: return null
        timer.scheduleWithFixedDelay({ request { StunMsg(StunMsg.REFRESH).add(StunMsg.LIFETIME, ByteBuffer.allocate(4).putInt(600).array()) } }, 5, 5, TimeUnit.MINUTES)
        timer.scheduleWithFixedDelay({ permit(channels.keys.toList()) }, 4, 4, TimeUnit.MINUTES)
        return r.get(StunMsg.XOR_RELAYED_ADDRESS)?.let(StunMsg::readXorAddr)
    }

    /** CreatePermission for each public peer IP and a ChannelBind per peer address (relays refuse private ones). */
    fun permit(addrs: List<InetSocketAddress>) {
        val v4 = addrs.filter(::isPublicV4).distinct()
        if (v4.isEmpty()) return
        val ok = request { StunMsg(StunMsg.CREATE_PERMISSION).apply { v4.forEach { add(StunMsg.XOR_PEER_ADDRESS, StunMsg.xorAddr(it)) } } } != null
        Log.i(TAG, "permission for $v4: ${if (ok) "ok" else "failed"}")
        for (a in v4) {
            val ch = channels[a] ?: synchronized(this) { channels.getOrPut(a) { nextChannel++ } }
            peers[ch] = a
            val bound = request { StunMsg(StunMsg.CHANNEL_BIND).add(StunMsg.CHANNEL_NUMBER, ByteBuffer.allocate(4).putShort(ch.toShort()).putShort(0).array()).add(StunMsg.XOR_PEER_ADDRESS, StunMsg.xorAddr(a)) } != null
            Log.i(TAG, "channel ${"%04x".format(ch)} → $a: ${if (bound) "ok" else "failed"}")
        }
    }

    fun isRelayed(a: InetSocketAddress) = a in viaRelay || channels.containsKey(a)

    /** ChannelData when the peer has a channel, else a Send indication (permission by IP is enough). */
    fun send(b: ByteArray, to: InetSocketAddress) {
        val ch = channels[to]
        if (ch != null) return sock.sendRaw(channelData(ch, b), server)
        if (!isPublicV4(to)) return
        sock.sendRaw(StunMsg(SEND_INDICATION).add(StunMsg.XOR_PEER_ADDRESS, StunMsg.xorAddr(to)).add(DATA, b).encode(), server)
    }

    /** A Data indication → (payload, peer); binds a channel for that peer in the background. */
    fun onIndication(m: StunMsg): Pair<ByteArray, InetSocketAddress>? {
        if (m.type != DATA_INDICATION) return null
        val peer = m.get(StunMsg.XOR_PEER_ADDRESS)?.let(StunMsg::readXorAddr) ?: return null
        val data = m.get(DATA) ?: return null
        if (peer !in viaRelay) Log.i(TAG, "first relayed packet from $peer (indication)")
        if (viaRelay.add(peer) && !channels.containsKey(peer)) runCatching { timer.execute { permit(listOf(peer)) } }
        return data to peer
    }

    /** ChannelData → (payload, peer address), or null for an unknown channel. */
    fun unwrap(b: ByteArray, n: Int): Pair<ByteArray, InetSocketAddress>? {
        if (n < 4) return null
        val bb = ByteBuffer.wrap(b, 0, n)
        val ch = bb.getShort(0).toInt() and 0xFFFF
        val len = bb.getShort(2).toInt() and 0xFFFF
        if (4 + len > n) return null
        val peer = peers[ch] ?: return null
        if (viaRelay.add(peer)) Log.i(TAG, "first relayed packet from $peer (channel)")
        return b.copyOfRange(4, 4 + len) to peer
    }

    fun close() {
        timer.shutdownNow()
        if (key != null) Thread { request { StunMsg(StunMsg.REFRESH).add(StunMsg.LIFETIME, ByteArray(4)) } }.start()
    }

    companion object {
        private const val TAG = "PcrTurn"
        const val SEND_INDICATION = 0x0016
        const val DATA_INDICATION = 0x0017
        const val DATA = 0x0013

        fun isPublicV4(a: InetSocketAddress): Boolean {
            val ip = a.address
            val b = ip.address
            if (b.size != 4 || ip.isSiteLocalAddress || ip.isLoopbackAddress || ip.isLinkLocalAddress || ip.isAnyLocalAddress || ip.isMulticastAddress) return false
            return !(b[0].toInt() and 0xFF == 100 && (b[1].toInt() and 0xC0) == 64) // 100.64.0.0/10 carrier NAT
        }

        fun channelData(ch: Int, b: ByteArray): ByteArray {
            val pad = (4 - b.size % 4) % 4
            return ByteBuffer.allocate(4 + b.size + pad).putShort(ch.toShort()).putShort(b.size.toShort()).put(b).array()
        }
    }
}
