package com.pcremote.home

import com.pcremote.agent.AgentState
import com.pcremote.agent.PairedPc
import com.pcremote.nav.Mode

/** The bits of a Moonlight ComputerDetails that Home needs. [online] null = not polled yet. */
data class MlPc(val uuid: String, val name: String, val online: Boolean?, val paired: Boolean, val hasMac: Boolean, val address: String?)

enum class PrimaryAction { OPEN, WAKE, PAIR, OFFLINE_HELP, CHECKING }

data class PcCardUi(
    val id: String,
    val name: String,
    val online: Boolean,
    val streaming: Boolean,
    val modes: List<Mode>,
    val primary: PrimaryAction,
    /** Paired only through Moonlight's PIN flow (no PC Remote Host). */
    val legacy: Boolean,
    val moonlightUuid: String?,
)

object HomeModel {
    private fun capModes(caps: Set<String>) = buildList {
        if ("input" in caps) add(Mode.REMOTE)
        if ("media" in caps) add(Mode.MEDIA)
        if ("input" in caps) add(Mode.SLIDES)
        if ("pad" in caps) add(Mode.PAD)
        if ("files" in caps) add(Mode.FILES)
    }

    fun cards(
        ml: List<MlPc>,
        agents: List<PairedPc>,
        live: Map<String, AgentState>,
        streaming: Map<String, Boolean>,
    ): List<PcCardUi> {
        val linked = agents.mapNotNull { it.moonlightUuid }.toSet()
        val agentCards = agents.map { pc ->
            val m = ml.firstOrNull { it.uuid == pc.moonlightUuid }
            val st = live[pc.id]
            val agentOnline = st is AgentState.Online
            val screen = m != null && m.paired && m.online == true
            val modes = (if (screen) listOf(Mode.SCREEN) else emptyList()) +
                (if (st is AgentState.Online) capModes(st.caps) else emptyList())
            val online = agentOnline || m?.online == true
            val primary = when {
                online -> PrimaryAction.OPEN
                st == null || st is AgentState.Connecting -> PrimaryAction.CHECKING
                pc.macs.isNotEmpty() || m?.hasMac == true -> PrimaryAction.WAKE
                else -> PrimaryAction.OFFLINE_HELP
            }
            PcCardUi(pc.id, pc.name, online, streaming[pc.id] == true, modes, primary, false, pc.moonlightUuid)
        }
        val legacyCards = ml.filter { it.uuid !in linked }.map { m ->
            val primary = when (m.online) {
                null -> PrimaryAction.CHECKING
                true -> if (m.paired) PrimaryAction.OPEN else PrimaryAction.PAIR
                false -> if (m.hasMac) PrimaryAction.WAKE else PrimaryAction.OFFLINE_HELP
            }
            PcCardUi(m.uuid, m.name, m.online == true, streaming[m.uuid] == true,
                if (m.paired) listOf(Mode.SCREEN) else emptyList(), primary, true, m.uuid)
        }
        return agentCards + legacyCards
    }
}
