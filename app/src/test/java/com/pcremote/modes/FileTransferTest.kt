package com.pcremote.modes

import com.pcremote.agent.Msg
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID

class FileTransferTest {
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private val data = ByteArray(600_000) { (it * 31 % 251).toByte() }
    private val id = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff")

    @Test fun `chunk framing matches the protocol layout`() {
        val c = FileFraming.chunk(id, 0x0102030405L, byteArrayOf(9, 8, 7), 3)
        assertEquals(1 + 16 + 8 + 3, c.size)
        assertEquals(1.toByte(), c[0])
        assertEquals(0x00.toByte(), c[1]); assertEquals(0xff.toByte(), c[16])
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 2, 3, 4, 5), c.copyOfRange(17, 25))
        val p = FileFraming.parse(c)!!
        assertEquals(id, p.id); assertEquals(0x0102030405L, p.offset); assertArrayEquals(byteArrayOf(9, 8, 7), p.data)
        assertEquals(null, FileFraming.parse(byteArrayOf(1, 2, 3)))
    }

    @Test fun resumesFromServerOffset() = runTest {
        val sent = mutableListOf<Msg>(); val bins = mutableListOf<ByteArray>()
        val s = FileSender(id, "a.bin", data.size.toLong(), { ByteArrayInputStream(data) }, { sent += it }, { bins += it; true })
        s.run { 300_000L }
        assertEquals(Msg.FileBegin(id.toString(), "a.bin", data.size.toLong()), sent.first())
        val first = FileFraming.parse(bins.first())!!
        assertEquals(300_000L, first.offset)
        assertEquals(data.size - 300_000, bins.sumOf { FileFraming.parse(it)!!.data.size })
        assertTrue(bins.all { FileFraming.parse(it)!!.data.size <= FileFraming.MAX_CHUNK })
        assertEquals(Msg.FileEnd(id.toString(), sha(data)), sent.last())
    }

    @Test fun `streamed hash equals whole-file hash from zero`() = runTest {
        val sent = mutableListOf<Msg>()
        FileSender(id, "a.bin", data.size.toLong(), { ByteArrayInputStream(data) }, { sent += it }, { true }).run { 0L }
        assertEquals(sha(data), (sent.last() as Msg.FileEnd).sha256)
    }

    @Test fun `receiver writes chunks and verifies the hash`() {
        val out = ByteArrayOutputStream()
        val r = FileReceiver(id, data.size.toLong(), out)
        var off = 0
        while (off < data.size) {
            val n = minOf(FileFraming.MAX_CHUNK, data.size - off)
            r.onChunk(FileFraming.parse(FileFraming.chunk(id, off.toLong(), data.copyOfRange(off, off + n), n))!!)
            off += n
        }
        assertTrue(r.finish(sha(data)))
        assertArrayEquals(data, out.toByteArray())
        assertEquals(1f, r.progress, 0.0001f)
    }

    @Test fun `receiver rejects a bad hash`() {
        val r = FileReceiver(id, 3, ByteArrayOutputStream())
        r.onChunk(FileFraming.Chunk(id, 0, byteArrayOf(1, 2, 3)))
        assertEquals(false, r.finish("00"))
    }
}
