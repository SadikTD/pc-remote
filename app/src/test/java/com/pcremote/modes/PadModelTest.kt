package com.pcremote.modes

import com.pcremote.agent.Msg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PadModelTest {
    @Test fun `xinput button masks`() {
        assertEquals(0x1000, PadButton.A.mask)
        assertEquals(0x8000, PadButton.Y.mask)
        assertEquals(0x0001, PadButton.UP.mask)
        assertEquals(0x0010, PadButton.START.mask)
        assertEquals(0x0200, PadButton.RB.mask)
    }

    @Test fun `pressing builds the mask and message`() {
        val s = PadState().press(PadButton.A).press(PadButton.LB).release(PadButton.LB)
        assertEquals(Msg.Pad(0, 0x1000, 0, 0, 0, 0, 0, 0), s.toMsg(0))
    }

    @Test fun `sticks clamp to int16 and screen-down is stick-down`() {
        val s = PadState().stick(left = true, x = 2f, y = 1f)
        val m = s.toMsg(0)
        assertEquals(32767, m.lx)
        assertEquals(-32768, m.ly)
        assertEquals(255, PadState().trigger(left = false, v = 1.5f).toMsg(0).rt)
    }

    @Test fun `sender throttles to 8 ms and only sends changes`() {
        val p = PadSender(minIntervalMs = 8)
        val a = PadState().press(PadButton.A)
        assertTrue(p.offer(a, 0) != null)
        assertNull(p.offer(a, 20)) // unchanged
        val b = a.press(PadButton.B)
        assertEquals(b.toMsg(0), p.offer(b, 24)) // changed, and 8 ms have passed
    }

    @Test fun `rapid changes are merged and flushed`() {
        val p = PadSender(minIntervalMs = 8)
        p.offer(PadState().press(PadButton.A), 0)
        assertNull(p.offer(PadState().press(PadButton.B), 2))
        assertNull(p.offer(PadState().press(PadButton.X), 4))
        assertEquals(PadState().press(PadButton.X).toMsg(0), p.flush(8))
        assertNull(p.flush(16))
    }

    @Test fun gyroHiddenWithoutSensor() {
        assertFalse(PadLayouts.available(hasGyro = false).any { it.gyro != GyroUse.NONE })
        assertTrue(PadLayouts.available(hasGyro = true).any { it.gyro == GyroUse.STEER })
    }

    @Test fun `layout survives a json round trip`() {
        val l = PadLayouts.XBOX.copy(elements = PadLayouts.XBOX.elements.map { it.copy(x = it.x + 0.01f) })
        assertEquals(l, PadLayouts.fromJson(PadLayouts.toJson(l)))
    }
}
