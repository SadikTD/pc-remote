package com.pcremote.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class HostOrderTest {
    @Test fun `LAN hosts first, then the public address, no duplicates`() {
        assertEquals(listOf("192.168.0.2", "10.0.0.3", "203.0.113.7"), HostOrder.order(listOf("192.168.0.2", "10.0.0.3"), "203.0.113.7"))
        assertEquals(listOf("192.168.0.2"), HostOrder.order(listOf("192.168.0.2"), null))
        assertEquals(listOf("192.168.0.2"), HostOrder.order(listOf("192.168.0.2"), "192.168.0.2"))
    }

    @Test fun `public address survives a store round trip`() {
        val kv = object : KeyValue {
            val m = mutableMapOf<String, String>()
            override fun get(key: String) = m[key]
            override fun put(key: String, value: String?) { if (value == null) m.remove(key) else m[key] = value }
        }
        val box = object : SecretBox { override fun seal(plain: String) = plain.reversed(); override fun open(sealed: String) = sealed.reversed() }
        val s = PcStore(kv, box)
        s.upsert(PairedPc("a", "A", listOf("192.168.0.2"), 47999, "f".repeat(64), "t", null, emptyList(), publicHost = "203.0.113.7"))
        assertEquals("203.0.113.7", s.all().single().publicHost)
    }
}
