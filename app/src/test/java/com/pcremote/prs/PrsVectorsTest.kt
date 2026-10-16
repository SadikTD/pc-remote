package com.pcremote.prs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** Cross-checks against docs/prs-vectors.json, written by the host (`cargo test prs::vectors::write_vectors -- --ignored`). */
class PrsVectorsTest {
    private val v: JsonObject = Json.parseToJsonElement(File("../docs/prs-vectors.json").readText()).jsonObject
    private fun JsonObject.hex(name: String) = this[name]!!.jsonPrimitive.content.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun JsonObject.int(name: String) = this[name]!!.jsonPrimitive.int
    private val crypt = Crypt(v.hex("key"), v["session"]!!.jsonPrimitive.long.toInt())

    @Test fun header() {
        val h = v["header"]!!.jsonObject
        val bytes = h.hex("bytes")
        val want = Header(h.int("typ"), h.int("flags"), h.int("k"), h.int("seq"), h.int("frameId"), h.int("frag"), h.int("count"))
        assertEquals(want, Header.decode(bytes))
        assertArrayEquals(bytes, want.encode())
        assertNull(Header.decode(bytes.copyOf(15)))
        assertNull(Header.decode(bytes.copyOf().also { it[0] = 0x21 }))
    }

    @Test fun sealOpenBothDirections() {
        for (name in listOf("sealed", "sealedIn")) {
            val s = v[name]!!.jsonObject
            val pkt = s.hex("packet"); val plain = s.hex("plain"); val dir = s.int("dir")
            val (h, got) = crypt.open(dir, pkt)!!
            assertArrayEquals(plain, got)
            assertArrayEquals(pkt, crypt.seal(dir, h, plain))
            assertNull("wrong direction", crypt.open(1 - dir, pkt))
            for (i in listOf(0, 4, 16, pkt.size - 1)) assertNull("byte $i", crypt.open(dir, pkt.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }))
        }
    }

    private val frame = v["frame"]!!.jsonObject
    private val shards = frame["shards"]!!.jsonArray.map { it.jsonObject }
    private val headers = shards.map { Header.decode(it.hex("header"))!! }
    private val data = shards.map { it.hex("data") }

    @Test fun parityMatchesHost() {
        val k = headers[0].k; val p = headers[0].flags
        assertEquals(frame.int("fecPct"), p)
        assertEquals(listOf(64 to 20, 2 to 1), Prs.layout(k, p))
        var at = 0
        for ((kb, mb) in Prs.layout(k, p)) {
            val block = Array(kb + mb) { if (it < kb) data[at + it] else ByteArray(Prs.SHARD) }
            ReedSolomon.of(kb, mb).encode(block)
            for (j in kb until kb + mb) assertArrayEquals("shard ${at + j}", data[at + j], block[j])
            at += kb + mb
        }
        headers.forEachIndexed { i, h -> assertEquals(i, h.frag); assertEquals(data.size, h.count); assertEquals(if (i < 64 || i in 84..85) Prs.VIDEO else Prs.FEC, h.typ) }
    }

    @Test fun rebuildsFromErasures() {
        val k = headers[0].k; val p = headers[0].flags
        val payload = frame.hex("payload")
        // lose the frame-header shard plus as many more as each block's parity covers: block 0 = 0..83, block 1 = 84..86
        for (erase in listOf((0 until 20) + 84, (64 until 84) + 86, (0 until 84 step 4).take(20) + 85)) {
            val got: Array<ByteArray?> = Array(data.size) { if (it in erase) null else data[it] }
            val (fh, body) = Frames.assemble(k, p, got)!!
            assertArrayEquals(payload, body)
            assertEquals(FrameHeader(payload.size, frame["captureUs"]!!.jsonPrimitive.long, frame["keyframe"]!!.jsonPrimitive.boolean, frame.int("codec")), fh)
        }
        assertNull(Frames.assemble(k, p, Array(data.size) { if (it in 84..85) null else data[it] }))
        assertNull(Frames.assemble(k, p, Array(data.size - 1) { data[it] }))
    }

    @Test fun realPacketsRoundTripThroughCrypt() {
        val h = headers[3].copy(seq = 1234)
        val (h2, plain) = crypt.open(Crypt.HOST_TO_PHONE, crypt.seal(Crypt.HOST_TO_PHONE, h, data[3]))!!
        assertEquals(h, h2); assertArrayEquals(data[3], plain)
        assertNotNull(Frames.assemble(headers[0].k, headers[0].flags, Array(data.size) { data[it] }))
    }
}
