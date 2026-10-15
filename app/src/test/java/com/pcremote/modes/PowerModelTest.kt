package com.pcremote.modes

import com.pcremote.agent.Msg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerModelTest {
    @Test fun `lock needs no confirm, the rest do`() {
        assertFalse(PowerAction.LOCK.needsConfirm)
        assertTrue(PowerAction.SLEEP.needsConfirm)
        assertTrue(PowerAction.RESTART.needsConfirm)
        assertTrue(PowerAction.SHUTDOWN.needsConfirm)
        assertTrue(PowerAction.SIGNOUT.needsConfirm)
    }

    @Test fun `actions map to protocol names`() {
        assertEquals(Msg.Power("lock"), PowerAction.LOCK.msg())
        assertEquals(Msg.Power("signout"), PowerAction.SIGNOUT.msg())
        assertEquals(Msg.Power("shutdown"), PowerAction.SHUTDOWN.msg())
    }

    @Test fun `tapping a destructive action twice confirms it`() {
        val p = PowerConfirm()
        assertEquals(null, p.tap(PowerAction.SHUTDOWN))
        assertEquals(PowerAction.SHUTDOWN, p.pending)
        assertEquals(Msg.Power("shutdown"), p.tap(PowerAction.SHUTDOWN))
        assertEquals(Msg.Power("lock"), p.tap(PowerAction.LOCK))
    }

    @Test fun `switching actions resets the confirm`() {
        val p = PowerConfirm()
        p.tap(PowerAction.RESTART)
        assertEquals(null, p.tap(PowerAction.SHUTDOWN))
        assertEquals(PowerAction.SHUTDOWN, p.pending)
    }
}
