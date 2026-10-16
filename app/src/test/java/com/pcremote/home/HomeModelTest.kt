package com.pcremote.home

import com.pcremote.agent.AgentState
import com.pcremote.agent.FailReason
import com.pcremote.agent.PairedPc
import com.pcremote.agent.PcInfo
import com.pcremote.nav.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeModelTest {
    private fun agent(id: String, macs: List<String> = listOf("AA:BB:CC:DD:EE:FF")) =
        PairedPc(id, "Agent $id", listOf("192.168.0.5"), 47999, "f".repeat(64), "tok", macs)

    private val allCaps = setOf("stream", "input", "media", "pad", "files", "clip", "power", "thumb", "net")
    private fun online(caps: Set<String>) = AgentState.Online(PcInfo("a", "x"), caps)

    @Test fun `online pc gets every mode its caps allow, screen first`() {
        val c = HomeModel.cards(listOf(agent("a")), mapOf("a" to online(allCaps)), mapOf("a" to true)).single()
        assertEquals(listOf(Mode.SCREEN, Mode.REMOTE, Mode.MEDIA, Mode.SLIDES, Mode.PAD, Mode.FILES), c.modes)
        assertTrue(c.online); assertTrue(c.streaming); assertEquals(PrimaryAction.OPEN, c.primary)
    }

    @Test fun `caps limit modes`() {
        assertEquals(listOf(Mode.REMOTE, Mode.SLIDES), HomeModel.cards(listOf(agent("a")), mapOf("a" to online(setOf("input"))), emptyMap()).single().modes)
    }

    @Test fun `offline with mac wakes, without mac shows help, unknown is checking`() {
        val off = mapOf("a" to AgentState.Failed(FailReason.UNREACHABLE))
        assertEquals(PrimaryAction.WAKE, HomeModel.cards(listOf(agent("a")), off, emptyMap()).single().primary)
        assertEquals(PrimaryAction.OFFLINE_HELP, HomeModel.cards(listOf(agent("a", macs = emptyList())), off, emptyMap()).single().primary)
        assertEquals(PrimaryAction.CHECKING, HomeModel.cards(listOf(agent("a")), emptyMap(), emptyMap()).single().primary)
        assertTrue(HomeModel.cards(listOf(agent("a")), off, emptyMap()).single().modes.isEmpty())
    }

    @Test fun `power is offered only when the host advertises it`() {
        assertTrue(HomeModel.cards(listOf(agent("a")), mapOf("a" to online(setOf("power"))), emptyMap()).single().canPower)
        assertFalse(HomeModel.cards(listOf(agent("a")), mapOf("a" to online(setOf("input"))), emptyMap()).single().canPower)
    }

    @Test fun `wake on lan magic packet`() {
        val p = WakeOnLan.packet("AA:BB:CC:DD:EE:FF")!!
        assertEquals(102, p.size)
        assertTrue(p.take(6).all { it == (-1).toByte() })
        assertEquals(0xAA.toByte(), p[6]); assertEquals(0xFF.toByte(), p[101])
        assertNull(WakeOnLan.packet("nope"))
        assertEquals(102, WakeOnLan.packet("aa-bb-cc-dd-ee-ff")!!.size)
    }
}
