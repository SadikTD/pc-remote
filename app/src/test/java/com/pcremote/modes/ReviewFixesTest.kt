package com.pcremote.modes

import com.pcremote.agent.KeyValue
import com.pcremote.agent.Msg
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.UUID

class ReviewFixesTest {
    private val id = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff")
    private val data = ByteArray(2_000_000) { it.toByte() }

    @Test fun `uploads wait while the socket backlog is high`() = runTest {
        var backlog = 10L * 1024 * 1024
        var sent = 0
        val s = FileSender(id, "a", data.size.toLong(), { ByteArrayInputStream(data) }, {}, { sent++; true }, backlog = { backlog })
        val job = async { s.run { 0L } }
        runCurrent()
        assertEquals(0, sent)
        backlog = 0
        advanceTimeBy(50); runCurrent()
        job.await()
        assertTrue(sent > 0)
    }

    @Test fun `uploads abort when the socket refuses a frame`() = runTest {
        val msgs = mutableListOf<Msg>()
        val s = FileSender(id, "a", data.size.toLong(), { ByteArrayInputStream(data) }, { msgs += it }, { false })
        val r = runCatching { s.run { 0L } }
        assertTrue(r.isFailure)
        assertFalse(msgs.any { it is Msg.FileEnd })
    }

    @Test fun `pending uploads persist with their id for resume`() {
        val kv = object : KeyValue {
            val m = mutableMapOf<String, String>()
            override fun get(key: String) = m[key]
            override fun put(key: String, value: String?) { if (value == null) m.remove(key) else m[key] = value }
        }
        PendingUploads(kv, "pc1").add(PendingUpload(id.toString(), "content://x/1", "a.jpg", 10))
        assertEquals(listOf(PendingUpload(id.toString(), "content://x/1", "a.jpg", 10)), PendingUploads(kv, "pc1").all())
        assertTrue(PendingUploads(kv, "pc2").all().isEmpty())
        PendingUploads(kv, "pc1").remove(id.toString())
        assertTrue(PendingUploads(kv, "pc1").all().isEmpty())
    }

    @Test fun `dial ignores jumps across the bottom gap`() {
        assertEquals(40, VolumeDial.next(prev = 50, deg = VolumeDial.volumeToAngle(40)))
        assertNull(VolumeDial.next(prev = 2, deg = 170f)) // 2 -> 100 is a wrap, not a turn
    }

    @Test fun `slides ignores key repeat, media keeps it`() {
        val got = mutableListOf<Boolean>()
        val r = VolumeKeyRouter()
        r.activate(onKey = { got += it }, allowRepeat = false)
        assertTrue(r.onVolumeKey(up = true, repeat = true)) // still consumed
        r.onVolumeKey(up = true, repeat = false)
        assertEquals(listOf(true), got)
        val sent = mutableListOf<Msg>()
        r.activateVolume { sent += it }
        r.onVolumeKey(up = true, repeat = true)
        assertEquals(1, sent.size)
    }
}
