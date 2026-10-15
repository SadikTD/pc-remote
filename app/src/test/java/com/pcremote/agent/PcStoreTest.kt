package com.pcremote.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PcStoreTest {
    private class MemKv : KeyValue {
        val map = mutableMapOf<String, String>()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
    }

    /** Fake cipher: reversible, but the plaintext never appears in the output. */
    private object FakeBox : SecretBox {
        override fun seal(plain: String) = "S" + plain.reversed().map { it + 1 }.joinToString("")
        override fun open(sealed: String) = sealed.drop(1).map { it - 1 }.joinToString("").reversed()
    }

    private fun pc(id: String, token: String = "secret-token-$id", uuid: String? = null) =
        PairedPc(id, "PC $id", listOf("192.168.0.2"), 47999, "f".repeat(64), token, uuid, listOf("AA:BB"))

    @Test fun `upsert adds and replaces by id`() {
        val s = PcStore(MemKv(), FakeBox)
        s.upsert(pc("a")); s.upsert(pc("b"))
        s.upsert(pc("a").copy(name = "Renamed"))
        assertEquals(listOf("Renamed", "PC b"), s.all().map { it.name })
    }

    @Test fun `remove deletes`() {
        val s = PcStore(MemKv(), FakeBox)
        s.upsert(pc("a")); s.remove("a")
        assertTrue(s.all().isEmpty())
    }

    @Test fun `token is stored encrypted and read back`() {
        val kv = MemKv()
        PcStore(kv, FakeBox).upsert(pc("a"))
        assertFalse(kv.map.values.any { it.contains("secret-token-a") })
        assertEquals("secret-token-a", PcStore(kv, FakeBox).all().single().token)
    }

    @Test fun `lookup by moonlight uuid`() {
        val s = PcStore(MemKv(), FakeBox)
        s.upsert(pc("a", uuid = "U1"))
        assertEquals("a", s.byMoonlightUuid("U1")?.id)
        assertNull(s.byMoonlightUuid("U2"))
    }

    @Test fun `corrupt data reads as empty`() {
        val kv = MemKv().apply { map["pcremote_pcs"] = "{nope" }
        assertTrue(PcStore(kv, FakeBox).all().isEmpty())
    }
}
