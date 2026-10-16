package com.pcremote.home

import com.pcremote.agent.AgentState
import com.pcremote.agent.PairedPc
import com.pcremote.agent.PcInfo
import com.pcremote.nav.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeModelTest {
    private fun ml(uuid: String, online: Boolean? = true, paired: Boolean = true, mac: Boolean = true) =
        MlPc(uuid, "ML $uuid", online, paired, mac, "192.168.0.5")

    private fun agent(id: String, ml: String? = null, macs: List<String> = listOf("AA")) =
        PairedPc(id, "Agent $id", listOf("192.168.0.5"), 47999, "f".repeat(64), "tok", ml, macs)

    private val allCaps = setOf("input", "media", "pad", "files", "clip", "power", "thumb", "net")

    @Test fun legacyPcScreenOnly() {
        val cards = HomeModel.cards(listOf(ml("U1")), emptyList(), emptyMap(), emptyMap())
        assertEquals(listOf(Mode.SCREEN), cards.single().modes)
        assertEquals(PrimaryAction.OPEN, cards.single().primary)
        assertTrue(cards.single().legacy)
    }

    @Test fun `online agent pc gets every mode its caps allow`() {
        val cards = HomeModel.cards(listOf(ml("U1")), listOf(agent("a", "U1")),
            mapOf("a" to AgentState.Online(PcInfo("a", "x"), allCaps)), emptyMap())
        val c = cards.single()
        assertEquals("a", c.id)
        assertEquals(listOf(Mode.SCREEN, Mode.REMOTE, Mode.MEDIA, Mode.SLIDES, Mode.PAD, Mode.FILES), c.modes)
        assertTrue(c.online)
    }

    @Test fun `caps limit modes`() {
        val c = HomeModel.cards(emptyList(), listOf(agent("a")),
            mapOf("a" to AgentState.Online(PcInfo("a", "x"), setOf("input"))), emptyMap()).single()
        assertEquals(listOf(Mode.REMOTE, Mode.SLIDES), c.modes)
    }

    @Test fun `offline with mac wakes, without mac shows help`() {
        val off = mapOf("a" to AgentState.Failed(com.pcremote.agent.FailReason.UNREACHABLE))
        assertEquals(PrimaryAction.WAKE, HomeModel.cards(emptyList(), listOf(agent("a")), off, emptyMap()).single().primary)
        assertEquals(PrimaryAction.OFFLINE_HELP,
            HomeModel.cards(emptyList(), listOf(agent("a", macs = emptyList())), off, emptyMap()).single().primary)
    }

    @Test fun `legacy online but unpaired asks to pair, unknown is checking`() {
        assertEquals(PrimaryAction.PAIR, HomeModel.cards(listOf(ml("U1", paired = false)), emptyList(), emptyMap(), emptyMap()).single().primary)
        assertEquals(PrimaryAction.CHECKING, HomeModel.cards(listOf(ml("U1", online = null)), emptyList(), emptyMap(), emptyMap()).single().primary)
    }

    @Test fun `linked moonlight pc is not listed twice and streaming is reflected`() {
        val cards = HomeModel.cards(listOf(ml("U1"), ml("U2")), listOf(agent("a", "U1")),
            mapOf("a" to AgentState.Online(PcInfo("a", "x"), allCaps)), mapOf("a" to true))
        assertEquals(listOf("a", "U2"), cards.map { it.id })
        assertTrue(cards[0].streaming)
    }

    @Test fun `agent online but moonlight offline hides screen mode`() {
        val c = HomeModel.cards(listOf(ml("U1", online = false)), listOf(agent("a", "U1")),
            mapOf("a" to AgentState.Online(PcInfo("a", "x"), setOf("input"))), emptyMap()).single()
        assertEquals(listOf(Mode.REMOTE, Mode.SLIDES), c.modes)
    }

    @Test fun `power is offered only when the host advertises it`() {
        val on = HomeModel.cards(emptyList(), listOf(agent("a")), mapOf("a" to AgentState.Online(PcInfo("a", "x"), setOf("input", "power"))), emptyMap()).single()
        val off = HomeModel.cards(emptyList(), listOf(agent("a")), mapOf("a" to AgentState.Online(PcInfo("a", "x"), setOf("input"))), emptyMap()).single()
        assertTrue(on.canPower)
        assertTrue(!off.canPower)
    }
}
