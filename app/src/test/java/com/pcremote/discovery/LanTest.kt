package com.pcremote.discovery

import com.pcremote.agent.HostOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanTest {
    @Test fun parsesTxt() {
        assertEquals(LanPc("abc", "DESKTOP-1", "192.168.0.5", 2), Lan.parse(mapOf("id" to "abc", "v" to "2", "name" to "DESKTOP-1"), "192.168.0.5"))
        assertEquals(1, Lan.parse(mapOf("id" to "abc"), "10.0.0.2")!!.v)
        assertNull(Lan.parse(mapOf("v" to "2"), "10.0.0.2"))
        assertNull(Lan.parse(mapOf("id" to "abc"), null))
        assertNull(Lan.parse(mapOf("id" to "abc"), "fe80::1%wlan0"))
    }

    @Test fun discoveredAddressFirstThenStoredThenPublic() {
        val hosts = Lan.prefer("192.168.0.9", listOf("192.168.0.5", "192.168.0.9"))!!
        assertEquals(listOf("192.168.0.9", "192.168.0.5"), hosts)
        assertEquals(listOf("192.168.0.9", "192.168.0.5", "203.0.113.7"), HostOrder.order(hosts, "203.0.113.7"))
        assertNull("already first", Lan.prefer("192.168.0.9", hosts))
    }
}
