package com.pcremote.pclist

import com.limelight.R
import com.limelight.nvstream.http.ComputerDetails.State
import com.limelight.nvstream.http.NvApp
import com.limelight.nvstream.http.PairingManager.PairState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PcListLogicTest {
    @Test fun `action per state`() {
        assertEquals(PcAction.CONNECT, PcListLogic.actionFor(State.ONLINE, PairState.PAIRED, true))
        assertEquals(PcAction.PAIR, PcListLogic.actionFor(State.ONLINE, PairState.NOT_PAIRED, true))
        assertEquals(PcAction.WAKE, PcListLogic.actionFor(State.OFFLINE, PairState.PAIRED, true))
        assertEquals(PcAction.CHECKING, PcListLogic.actionFor(State.UNKNOWN, null, true))
        assertEquals(PcAction.CHECKING, PcListLogic.actionFor(null, null, true)) // not polled yet
    }

    @Test fun `offline pc with no MAC offers setup help instead of a dead button`() {
        assertEquals(PcAction.OFFLINE, PcListLogic.actionFor(State.OFFLINE, null, false))
    }

    @Test fun `connect failure is unreachable, empty list is no apps`() {
        assertEquals(R.string.pcr_pc_unreachable, PcListLogic.connectProblem(null))
        assertEquals(R.string.pcr_no_apps, PcListLogic.connectProblem(emptyList()))
        assertNull(PcListLogic.connectProblem(listOf(NvApp("Desktop", 1, false))))
    }

    @Test fun `picks Desktop case-insensitively`() {
        val apps = listOf(NvApp("Steam Big Picture", 1, false), NvApp("desktop", 2, false))
        assertEquals(2, PcListLogic.pickDesktop(apps)!!.appId)
    }

    @Test fun `falls back to first app when no Desktop`() {
        assertEquals(7, PcListLogic.pickDesktop(listOf(NvApp("Steam", 7, false)))!!.appId)
        assertNull(PcListLogic.pickDesktop(emptyList()))
    }

    @Test fun `pair outcome messages`() {
        assertNull(PcListLogic.pairOutcomeMessage(PairState.PAIRED, false))
        assertEquals(R.string.pcr_pair_wrong_pin, PcListLogic.pairOutcomeMessage(PairState.PIN_WRONG, false))
        assertEquals(R.string.pcr_pair_ingame, PcListLogic.pairOutcomeMessage(PairState.FAILED, true))
        assertEquals(R.string.pcr_pair_failed, PcListLogic.pairOutcomeMessage(PairState.FAILED, false))
        assertEquals(R.string.pcr_pair_busy, PcListLogic.pairOutcomeMessage(PairState.ALREADY_IN_PROGRESS, false))
        assertEquals(R.string.pcr_pair_failed, PcListLogic.pairOutcomeMessage(null, false)) // PC dropped mid-pair
    }
}
