package com.pcremote.prs

import com.pcremote.agent.Msg
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.random.Random

/** Host-side packetizer for tests (mirrors host `frame::packetize`). */
fun packetize(frameId: Int, keyframe: Boolean, body: ByteArray, p: Int = 15, captureUs: Long = 0): List<Pair<Header, ByteArray>> {
    val payload = ByteBuffer.allocate(16 + body.size).putInt(body.size).putLong(captureUs)
        .put(if (keyframe) 1 else 0).put(1).putShort(0).put(body).array()
    val k = maxOf(1, (payload.size + Prs.SHARD - 1) / Prs.SHARD)
    val blocks = Prs.layout(k, p)
    val n = blocks.sumOf { it.first + it.second }
    val out = ArrayList<Pair<Header, ByteArray>>()
    var d = 0
    for ((kb, mb) in blocks) {
        val shards = Array(kb + mb) { ByteArray(Prs.SHARD) }
        for (i in 0 until kb) { val from = d++ * Prs.SHARD; payload.copyInto(shards[i], 0, from, minOf(payload.size, from + Prs.SHARD)) }
        ReedSolomon.of(kb, mb).encode(shards)
        shards.forEachIndexed { j, s -> out += Header(if (j < kb) Prs.VIDEO else Prs.FEC, p, k, 0, frameId, out.size, n) to s }
    }
    return out
}

class FrameAssemblerTest {
    private val frames = ArrayList<Frame>()
    private var idrs = 0
    private val a = FrameAssembler({ frames += it }, { idrs++ })

    @Test fun dropsIncompleteFrameAndRequestsIdrOnce() {
        val f1 = packetize(1, true, ByteArray(5000) { it.toByte() }) // k=5, m=1
        f1.drop(2).forEach { (h, d) -> a.onShard(h, d, 0) } // two missing, one parity: not recoverable
        a.tick(40_000); assertEquals(0, idrs)
        a.tick(60_000); assertEquals(1, idrs); assertEquals(1, a.dropped)
        a.tick(200_000); assertEquals("one IDR per 250 ms", 1, idrs)
        // a P-frame after the loss would smear: held back
        packetize(2, false, ByteArray(100)).forEach { (h, d) -> a.onShard(h, d, 210_000) }
        assertTrue(frames.isEmpty())
        a.tick(320_000); assertEquals("asks again while still waiting", 2, idrs)
        packetize(3, true, ByteArray(100)).forEach { (h, d) -> a.onShard(h, d, 330_000) }
        assertEquals(listOf(3), frames.map { it.id })
        a.tick(700_000); assertEquals(2, idrs)
        // late shards of old frames are ignored
        f1.take(2).forEach { (h, d) -> a.onShard(h, d, 710_000) }
        assertEquals(1, frames.size)
    }

    @Test fun laterFrameOvertakesIncompleteOne() {
        packetize(1, true, ByteArray(100)).forEach { (h, d) -> a.onShard(h, d, 0) }
        packetize(2, false, ByteArray(3000)).take(1).forEach { (h, d) -> a.onShard(h, d, 1000) }
        packetize(3, false, ByteArray(100)).forEach { (h, d) -> a.onShard(h, d, 2000) }
        assertEquals(listOf(1), frames.map { it.id })
        assertEquals(1, a.dropped); assertEquals(1, idrs)
    }

    @Test fun rebuildsWithFecUnderLoss() {
        val rnd = Random(7)
        for (id in 1..40) {
            val body = ByteArray(rnd.nextInt(1, 200_000)).also(rnd::nextBytes)
            val pkts = packetize(id, id == 1, body, p = 30).shuffled(rnd)
            // lose up to ~20% of each block's shards, but never more than its parity
            val lost = HashSet<Int>()
            var at = 0
            for ((kb, mb) in Prs.layout(pkts[0].first.k, 30)) {
                (at until at + kb + mb).shuffled(rnd).take(minOf(mb, (kb + mb) / 5)).forEach { lost += it }
                at += kb + mb
            }
            pkts.filter { it.first.frag !in lost }.forEach { (h, d) -> a.onShard(h, d, id * 16_000L) }
            assertArrayEquals("frame $id", body, frames.last().data)
        }
        assertEquals(40, frames.size); assertEquals(0, a.dropped); assertEquals(0, idrs)
    }

    @Test fun ignoresGarbageShapes() {
        val (h, d) = packetize(1, true, ByteArray(10))[0]
        a.onShard(h.copy(count = 99), d, 0)
        a.onShard(h.copy(k = 0), d, 0)
        a.onShard(h, d.copyOf(10), 0)
        a.tick(100_000)
        assertEquals(0, frames.size); assertEquals(0, a.dropped)
    }
}

class ReceiverTest {
    private class FakeTransport : Transport {
        val sent = ArrayList<ByteArray>()
        override fun send(b: ByteArray) { sent += b }
        override fun receive(buf: ByteArray, timeoutMs: Int) = -1
        override fun close() {}
    }

    private val key = ByteArray(32) { 1 }
    private val host = Crypt(key, 99)
    private val t = FakeTransport()
    private val frames = ArrayList<Frame>()
    private val r = Receiver(t, Crypt(key, 99), object : ReceiverListener { override fun onFrame(f: Frame) { frames += f } }, clock = { 0 })
    private var hostSeq = 0

    private fun fromHost(h: Header, plain: ByteArray, now: Long, seq: Int = hostSeq++) {
        val pkt = host.seal(Crypt.HOST_TO_PHONE, h.copy(seq = seq), plain)
        r.onPacket(pkt, pkt.size, now)
    }
    private fun sentOf(typ: Int) = t.sent.mapNotNull { host.open(Crypt.PHONE_TO_HOST, it) }.filter { it.first.typ == typ }

    @Test fun punchingGapsBeforeVideoAreNotLoss() {
        r.step(0)
        // the host punched to other addresses: seqs 0..49 went elsewhere, one PROBE reached us
        fromHost(Header(Prs.PROBE), ByteBuffer.allocate(16).putLong(1).putLong(0).array(), 1_000, seq = 50)
        var seq = 60
        for ((h, d) in packetize(1, true, ByteArray(3000))) fromHost(h, d, 2_000, seq++)
        r.step(100_000)
        assertEquals(0, ByteBuffer.wrap(sentOf(Prs.REPORT).last().second).getShort(4).toInt())
    }

    @Test fun reportFieldsFromCounters() {
        r.step(0)
        val pkts = packetize(7, true, ByteArray(20_000), p = 50) // 18 data + 9 parity
        // seqs 0..29 expected, 3 of them lost
        var seq = 0
        for ((h, d) in pkts) { if (seq in listOf(2, 9, 20)) seq++; fromHost(h, d, 1_000, seq++) }
        assertEquals(1, frames.size)
        r.decodeMs = 4
        r.step(100_000)
        val (_, rep) = sentOf(Prs.REPORT).last()
        val b = ByteBuffer.wrap(rep)
        assertEquals(16, rep.size)
        assertEquals(7, b.getInt(0))
        assertEquals((3 * 1000) / (pkts.size + 3), b.getShort(4).toInt())
        assertEquals(4, b.getShort(8).toInt())
        assertEquals(0, b.getInt(12))
    }

    @Test fun measuresRttFromProbeEcho() {
        r.step(1_000_000) // probe t=1_000_000
        val probe = sentOf(Prs.PROBE).last().second
        assertEquals(1_000_000L, ByteBuffer.wrap(probe).getLong(0))
        fromHost(Header(Prs.PROBE), ByteBuffer.allocate(16).putLong(50_000_000).putLong(1_000_000).array(), 1_030_000)
        assertEquals(1_030_000L - 15_000, r.hostToLocalUs(50_000_000L) + 0) // host 50 s == our 1.015 s
        r.step(1_200_000)
        assertEquals("echoes the host's t", 50_000_000L, ByteBuffer.wrap(sentOf(Prs.PROBE).last().second).getLong(8))
    }

    @Test fun keepsProbingAfterAddressChange() {
        fun probesBetween(from: Long, to: Long, stepUs: Long = 10_000): Int {
            val before = sentOf(Prs.PROBE).size
            var now = from
            while (now < to) { r.step(now); now += stepUs }
            return sentOf(Prs.PROBE).size - before
        }
        assertEquals("fast probing before video", 5, probesBetween(0, 1_000_000))
        packetize(1, true, ByteArray(10)).forEach { (h, d) -> fromHost(h, d, 1_000_000) }
        // while video flows: keepalive once a second
        var now = 1_000_000L
        var probes = 0
        while (now < 4_000_000) {
            packetize((now / 16_000).toInt(), false, ByteArray(10)).forEach { (h, d) -> fromHost(h, d, now) }
            val before = sentOf(Prs.PROBE).size; r.step(now); probes += sentOf(Prs.PROBE).size - before
            now += 16_000
        }
        assertTrue("keepalive only, got $probes", probes in 2..4)
        // video stops (phone switched networks): back to fast probing so the host learns the new address
        assertTrue(probesBetween(5_100_000, 6_100_000) >= 5)
    }

    @Test fun sendsLastThreeEvents() {
        for (dx in 1..4) r.sendInput(Msg.MouseMove(dx, 0))
        r.flush()
        val sent = sentOf(Prs.INPUT).map { Json.parseToJsonElement(String(it.second)).jsonArray }
        assertEquals(listOf(1, 2, 3, 3), sent.map { it.size })
        assertEquals(listOf(2L, 3L, 4L), sent.last().map { it.jsonObject["i"]!!.jsonPrimitive.long })
        assertEquals("mouse.move", sent.last().last().jsonObject["t"]!!.jsonPrimitive.content)
        assertEquals("packet seqs increase", sentOf(Prs.INPUT).map { it.first.seq }, sentOf(Prs.INPUT).map { it.first.seq }.sorted())
    }

    @Test fun oversizedInputShedsRedundancy() {
        val s = InputSender()
        s.payload(Msg.Text("a".repeat(500)))
        val b = s.payload(Msg.Text("b".repeat(700)))
        assertEquals(1, Json.parseToJsonElement(String(b)).jsonArray.size)
    }
}
