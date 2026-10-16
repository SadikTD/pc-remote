package com.pcremote.home

import com.pcremote.agent.AgentState
import com.pcremote.agent.PairedPc
import com.pcremote.nav.Mode

enum class PrimaryAction { OPEN, WAKE, OFFLINE_HELP, CHECKING }

data class PcCardUi(
    val id: String,
    val name: String,
    val online: Boolean,
    val streaming: Boolean,
    val modes: List<Mode>,
    val primary: PrimaryAction,
    /** The host advertises power actions. */
    val canPower: Boolean = false,
    /** Showing its lock or sign-in screen. */
    val locked: Boolean = false,
    /** The host can type a PIN on its lock screen. */
    val canUnlock: Boolean = false,
    val os: String = "",
)

object HomeModel {
    private fun capModes(caps: Set<String>) = buildList {
        if ("stream" in caps) add(Mode.SCREEN)
        if ("input" in caps) add(Mode.REMOTE)
        if ("media" in caps) add(Mode.MEDIA)
        if ("input" in caps) add(Mode.SLIDES)
        if ("pad" in caps) add(Mode.PAD)
        if ("files" in caps) add(Mode.FILES)
    }

    fun cards(agents: List<PairedPc>, live: Map<String, AgentState>, streaming: Map<String, Boolean>, locked: Map<String, Boolean> = emptyMap()): List<PcCardUi> =
        agents.map { pc ->
            val st = live[pc.id]
            val online = st is AgentState.Online
            val primary = when {
                online -> PrimaryAction.OPEN
                st == null || st is AgentState.Connecting -> PrimaryAction.CHECKING
                pc.macs.isNotEmpty() -> PrimaryAction.WAKE
                else -> PrimaryAction.OFFLINE_HELP
            }
            PcCardUi(pc.id, pc.name, online, streaming[pc.id] == true, (st as? AgentState.Online)?.caps.orEmpty().let(::capModes), primary,
                canPower = st is AgentState.Online && "power" in st.caps,
                locked = online && locked[pc.id] == true,
                canUnlock = st is AgentState.Online && "unlock" in st.caps,
                os = (st as? AgentState.Online)?.pc?.os.orEmpty())
        }
}
