package com.pcremote.modes

import com.pcremote.agent.Msg
import com.pcremote.agent.Wire
import com.pcremote.home.HomeAssistant
import com.pcremote.update.Updater
import org.junit.Assert.*
import org.junit.Test

class V3Test {
    @Test fun `rate smooths bytes per second`() {
        var t = 0L
        val r = Rate { t }
        t = 1; r.update(0)
        t = 1_000_000_001; assertEquals(1_000_000.0, r.update(1_000_000), 1.0)
        t = 2_000_000_001; val s = r.update(3_000_000) // 2 MB/s now: smoothed toward it
        assertTrue(s > 1_000_000 && s < 2_000_000)
        t = 2_100_000_001; assertEquals(s, r.update(9_000_000), 0.0) // under 250 ms: unchanged
    }

    @Test fun `eta only while moving`() {
        val t = Transfer("i", "a", true, size = 10_000, done = 4_000, status = Transfer.Status.ACTIVE, speed = 1_000.0)
        assertEquals(6L, t.eta)
        assertNull(t.copy(status = Transfer.Status.PAUSED).eta)
        assertEquals(0.4f, t.progress, 0.001f)
    }

    @Test fun `downloaded paths stay inside the folder`() {
        assertEquals(listOf("Trip", "a.jpg"), TransferHub.safeParts("Trip/a.jpg"))
        assertEquals(listOf("x"), TransferHub.safeParts("../../x"))
        assertEquals(listOf("C_", "y"), TransferHub.safeParts("C:\\y"))
        assertEquals(listOf("file"), TransferHub.safeParts("/./"))
    }

    @Test fun `formats sizes and times`() {
        assertEquals("512 B", Format.bytes(512))
        assertEquals("1.5 MB", Format.bytes(1_572_864))
        assertEquals("1m 5s", Format.eta(65))
    }

    @Test fun `release json picks the apk`() {
        val r = Updater.parse("""{"tag_name":"v3.1.0","assets":[{"name":"PC-Remote-Host-3.1.0-setup.exe","browser_download_url":"x"},{"name":"PC-Remote-3.1.0.apk","browser_download_url":"https://a/apk"}]}""")
        assertEquals("3.1.0", r?.version); assertEquals("https://a/apk", r?.apkUrl)
        assertNull(Updater.parse("""{"tag_name":"v1","assets":[]}"""))
        assertTrue(Updater.isNewer("3.0.1", "3.0.0")); assertTrue(Updater.isNewer("3.1", "3.0.9"))
        assertFalse(Updater.isNewer("3.0.0", "3.0.0")); assertFalse(Updater.isNewer("2.9", "3.0.0"))
    }

    @Test fun `home assistant wake request`() {
        assertEquals("https://ha.example.com", HomeAssistant.normalize(" ha.example.com/ "))
        assertEquals("http://192.168.0.2:8123", HomeAssistant.normalize("http://192.168.0.2:8123"))
        val r = HomeAssistant.request("https://ha.x", "tok", "AA:BB:CC:DD:EE:FF")
        assertEquals("https://ha.x/api/services/wake_on_lan/send_magic_packet", r.url.toString())
        assertEquals("Bearer tok", r.header("Authorization"))
        val body = okio.Buffer().also { r.body!!.writeTo(it) }.readUtf8()
        assertEquals("""{"mac":"AA:BB:CC:DD:EE:FF"}""", body)
    }

    @Test fun `webhook link wakes without a token`() {
        val kv = object : com.pcremote.agent.KeyValue {
            val m = mutableMapOf<String, String>()
            override fun get(key: String) = m[key]
            override fun put(key: String, value: String?) { if (value == null) m.remove(key) else m[key] = value }
        }
        val box = object : com.pcremote.agent.SecretBox { override fun seal(plain: String) = plain; override fun open(sealed: String) = sealed }
        val ha = HomeAssistant(kv, box)
        ha.url = "ha.x/api/webhook/abc"
        assertTrue(ha.isWebhook); assertTrue(ha.configured)
        ha.url = "ha.x"
        assertFalse(ha.configured) // an address needs a token
        val r = HomeAssistant.webhook("https://ha.x/api/webhook/abc")
        assertEquals("POST", r.method); assertNull(r.header("Authorization"))
    }

    @Test fun `v3 messages on the wire`() {
        assertEquals("""{"t":"unlock","secret":"1234"}""", Wire.encode(Msg.Unlock("1234")))
        assertEquals(Msg.Lock(true), Wire.decode("""{"t":"lock","locked":true}"""))
        val w = Wire.decode("""{"t":"welcome","v":1,"pc":{"id":"p","name":"n"},"caps":["unlock"],"locked":true}""") as Msg.Welcome
        assertTrue(w.locked)
        val l = Wire.decode("""{"t":"fs.list","path":"C:\\","entries":[{"name":"a","path":"C:\\a","dir":true,"size":0,"mtime":0}]}""") as Msg.FsList
        assertEquals("C:\\a", l.entries.single().path)
        assertEquals("""{"t":"fs.get","path":"C:\\a"}""", Wire.encode(Msg.FsGet("C:\\a")))
    }
}
