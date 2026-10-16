package com.pcremote.prs

import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** PRS wire format: see docs/stream-protocol.md. All integers big-endian. */
object Prs {
    const val VERSION = 1
    const val SHARD = 1152
    const val MAX_PLAIN = 1168
    const val BLOCK = 64

    const val VIDEO = 1; const val FEC = 2; const val AUDIO = 3; const val INPUT = 4; const val REPORT = 5
    const val IDR = 6; const val CURSOR = 7; const val PROBE = 8; const val BYE = 9

    /** `(kb, mb)` per FEC block for a frame of `k` data shards at FEC percentage `p`. */
    fun layout(k: Int, p: Int): List<Pair<Int, Int>> {
        val kk = maxOf(k, 1)
        return (0 until kk step BLOCK).map { start ->
            val kb = minOf(kk - start, BLOCK)
            kb to maxOf(1, (kb * p + 99) / 100)
        }
    }
}

data class Header(val typ: Int, val flags: Int = 0, val k: Int = 0, val seq: Int = 0, val frameId: Int = 0, val frag: Int = 0, val count: Int = 0) {
    fun encode(): ByteArray = ByteBuffer.allocate(16)
        .put(((Prs.VERSION shl 4) or typ).toByte()).put(flags.toByte()).putShort(k.toShort())
        .putInt(seq).putInt(frameId).putShort(frag.toShort()).putShort(count.toShort()).array()

    companion object {
        fun decode(b: ByteArray, len: Int = b.size): Header? {
            if (len < 16 || (b[0].toInt() and 0xFF) shr 4 != Prs.VERSION) return null
            val typ = b[0].toInt() and 0x0F
            if (typ !in 1..9) return null
            val bb = ByteBuffer.wrap(b, 0, 16)
            return Header(typ, b[1].toInt() and 0xFF, bb.getShort(2).toInt() and 0xFFFF, bb.getInt(4), bb.getInt(8),
                bb.getShort(12).toInt() and 0xFFFF, bb.getShort(14).toInt() and 0xFFFF)
        }
    }
}

/** AES-256-GCM per packet. Nonce = session ‖ dir ‖ 000000 ‖ seq; AAD = the 16-byte header. Not thread-safe. */
class Crypt(key: ByteArray, private val session: Int) {
    private val spec = SecretKeySpec(key.also { require(it.size == 32) }, "AES")
    private val enc = Cipher.getInstance("AES/GCM/NoPadding")
    private val dec = Cipher.getInstance("AES/GCM/NoPadding")
    private val nonce = ByteArray(12)

    private fun nonce(dir: Int, seq: Int): GCMParameterSpec {
        ByteBuffer.wrap(nonce).putInt(session).put(dir.toByte()).put(0).put(0).put(0).putInt(seq)
        return GCMParameterSpec(128, nonce)
    }

    fun seal(dir: Int, h: Header, plain: ByteArray): ByteArray {
        val hb = h.encode()
        enc.init(Cipher.ENCRYPT_MODE, spec, nonce(dir, h.seq))
        enc.updateAAD(hb)
        return hb + enc.doFinal(plain)
    }

    /** Null if malformed or authentication fails. */
    fun open(dir: Int, pkt: ByteArray, len: Int = pkt.size): Pair<Header, ByteArray>? {
        val h = Header.decode(pkt, len) ?: return null
        if (len < 32) return null
        return try {
            dec.init(Cipher.DECRYPT_MODE, spec, nonce(dir, h.seq))
            dec.updateAAD(pkt, 0, 16)
            h to dec.doFinal(pkt, 16, len - 16)
        } catch (_: java.security.GeneralSecurityException) { null }
    }

    companion object { const val HOST_TO_PHONE = 0; const val PHONE_TO_HOST = 1 }
}

data class FrameHeader(val len: Int, val captureUs: Long, val keyframe: Boolean, val codec: Int) {
    companion object {
        fun decode(b: ByteArray): FrameHeader? {
            if (b.size < 16 || b[13].toInt() !in 1..3) return null
            val bb = ByteBuffer.wrap(b)
            return FrameHeader(bb.getInt(0), bb.getLong(4), b[12].toInt() and 1 == 1, b[13].toInt())
        }
    }
}

object Frames {
    /** Rebuilds a frame from shards in layout order (`k`, `p` from its headers); null if not recoverable. */
    fun assemble(k: Int, p: Int, shards: Array<ByteArray?>): Pair<FrameHeader, ByteArray>? {
        val blocks = Prs.layout(k, p)
        if (shards.size != blocks.sumOf { it.first + it.second } || shards.any { it != null && it.size != Prs.SHARD }) return null
        val all = ByteArray(k * Prs.SHARD)
        var at = 0; var d = 0
        for ((kb, mb) in blocks) {
            val block = shards.copyOfRange(at, at + kb + mb)
            if ((0 until kb).any { block[it] == null } && !ReedSolomon.of(kb, mb).reconstructData(block)) return null
            for (i in 0 until kb) { System.arraycopy(block[i]!!, 0, all, d * Prs.SHARD, Prs.SHARD); d++ }
            at += kb + mb
        }
        val fh = FrameHeader.decode(all) ?: return null
        val end = 16L + (fh.len.toLong() and 0xFFFFFFFFL)
        if (end > all.size || (k > 1 && end <= (k - 1).toLong() * Prs.SHARD)) return null
        return fh to all.copyOfRange(16, end.toInt())
    }
}
