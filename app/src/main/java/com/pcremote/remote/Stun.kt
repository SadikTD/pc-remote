package com.pcremote.remote

import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** RFC 5389 / RFC 8656 message codec: just what STUN binding and a TURN client need. */
class StunMsg(val type: Int, val txid: ByteArray = ByteArray(12).also { rnd.nextBytes(it) }) {
    val attrs = ArrayList<Pair<Int, ByteArray>>()

    fun add(t: Int, v: ByteArray) = apply { attrs += t to v }
    fun get(t: Int): ByteArray? = attrs.firstOrNull { it.first == t }?.second

    val method get() = type and 0x3EEF
    val isSuccess get() = type and 0x0110 == 0x0100
    val isError get() = type and 0x0110 == 0x0110
    val errorCode: Int? get() = get(ERROR_CODE)?.let { if (it.size >= 4) (it[2].toInt() and 7) * 100 + (it[3].toInt() and 0xFF) else null }

    /** Encodes; with [key], appends MESSAGE-INTEGRITY (HMAC-SHA1, RFC 5389 §15.4). */
    fun encode(key: ByteArray? = null): ByteArray {
        val body = java.io.ByteArrayOutputStream()
        for ((t, v) in attrs) body.write(tlv(t, v))
        var b = body.toByteArray()
        if (key != null) b += tlv(MESSAGE_INTEGRITY, integrity(header(b.size) + b, key))
        return header(b.size) + b
    }

    private fun header(len: Int) = ByteBuffer.allocate(20).putShort(type.toShort()).putShort(len.toShort()).putInt(COOKIE).put(txid).array()

    companion object {
        const val COOKIE = 0x2112A442
        const val BINDING = 0x0001; const val ALLOCATE = 0x0003; const val REFRESH = 0x0004
        const val CREATE_PERMISSION = 0x0008; const val CHANNEL_BIND = 0x0009
        const val MAPPED_ADDRESS = 0x0001; const val USERNAME = 0x0006; const val MESSAGE_INTEGRITY = 0x0008
        const val ERROR_CODE = 0x0009; const val CHANNEL_NUMBER = 0x000C; const val LIFETIME = 0x000D
        const val XOR_PEER_ADDRESS = 0x0012; const val REALM = 0x0014; const val NONCE = 0x0015
        const val XOR_RELAYED_ADDRESS = 0x0016; const val REQUESTED_TRANSPORT = 0x0019; const val XOR_MAPPED_ADDRESS = 0x0020
        private val rnd = SecureRandom()

        /** HMAC-SHA1 over `msg` (header + attributes before MESSAGE-INTEGRITY), its length counting the MI attribute. */
        fun integrity(msg: ByteArray, key: ByteArray): ByteArray {
            val m = msg.copyOf()
            ByteBuffer.wrap(m).putShort(2, (msg.size - 20 + 24).toShort())
            return Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(key, "HmacSHA1")) }.doFinal(m)
        }

        private fun tlv(t: Int, v: ByteArray): ByteArray {
            val pad = (4 - v.size % 4) % 4
            return ByteBuffer.allocate(4 + v.size + pad).putShort(t.toShort()).putShort(v.size.toShort()).put(v).array()
        }

        /** True if `b` looks like STUN (first two bits 0, magic cookie in place). */
        fun isStun(b: ByteArray, len: Int) = len >= 20 && (b[0].toInt() and 0xC0) == 0 && ByteBuffer.wrap(b, 4, 4).int == COOKIE

        fun decode(b: ByteArray, len: Int = b.size): StunMsg? {
            if (!isStun(b, len)) return null
            val bb = ByteBuffer.wrap(b, 0, len)
            val type = bb.getShort(0).toInt() and 0xFFFF
            val mlen = bb.getShort(2).toInt() and 0xFFFF
            if (20 + mlen > len) return null
            val m = StunMsg(type, b.copyOfRange(8, 20))
            var at = 20
            while (at + 4 <= 20 + mlen) {
                val t = bb.getShort(at).toInt() and 0xFFFF
                val l = bb.getShort(at + 2).toInt() and 0xFFFF
                if (at + 4 + l > 20 + mlen) return null
                m.attrs += t to b.copyOfRange(at + 4, at + 4 + l)
                at += 4 + l + (4 - l % 4) % 4
            }
            return m
        }

        fun xorAddr(a: InetSocketAddress): ByteArray {
            val ip = a.address.address.also { require(it.size == 4) { "IPv4 only" } }
            val c = ByteBuffer.allocate(4).putInt(COOKIE).array()
            return ByteBuffer.allocate(8).put(0).put(1).putShort((a.port xor (COOKIE ushr 16)).toShort())
                .put(ByteArray(4) { (ip[it].toInt() xor c[it].toInt()).toByte() }).array()
        }

        fun readXorAddr(v: ByteArray): InetSocketAddress? {
            if (v.size < 8 || v[1].toInt() != 1) return null
            val port = (ByteBuffer.wrap(v).getShort(2).toInt() and 0xFFFF) xor (COOKIE ushr 16)
            val c = ByteBuffer.allocate(4).putInt(COOKIE).array()
            return InetSocketAddress(InetAddress.getByAddress(ByteArray(4) { (v[4 + it].toInt() xor c[it].toInt()).toByte() }), port)
        }

        fun readAddr(v: ByteArray): InetSocketAddress? {
            if (v.size < 8 || v[1].toInt() != 1) return null
            return InetSocketAddress(InetAddress.getByAddress(v.copyOfRange(4, 8)), ByteBuffer.wrap(v).getShort(2).toInt() and 0xFFFF)
        }

        /** The reflexive address in a Binding success. */
        fun mapped(m: StunMsg): InetSocketAddress? = m.get(XOR_MAPPED_ADDRESS)?.let(::readXorAddr) ?: m.get(MAPPED_ADDRESS)?.let(::readAddr)

        /** Long-term credential key: MD5(username ":" realm ":" password). */
        fun longTermKey(user: String, realm: String, pass: String): ByteArray =
            MessageDigest.getInstance("MD5").digest("$user:$realm:$pass".toByteArray())

        /** "ip:port" for IPv4, or null. */
        fun hostPort(a: InetSocketAddress): String? = (a.address as? Inet4Address)?.let { "${it.hostAddress}:${a.port}" }

        fun parseHostPort(s: String): InetSocketAddress? {
            val i = s.lastIndexOf(':')
            if (i <= 0) return null
            val port = s.substring(i + 1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            val host = s.substring(0, i).trim('[', ']')
            if (!host.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))) return null // literals only: never a DNS lookup here
            return runCatching { InetSocketAddress(InetAddress.getByName(host), port) }.getOrNull()
        }
    }
}
