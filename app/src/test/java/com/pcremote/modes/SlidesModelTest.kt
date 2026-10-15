package com.pcremote.modes

import com.pcremote.agent.Msg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlidesModelTest {
    @Test fun `next and previous are right and left arrow presses`() {
        assertEquals(listOf(Msg.Key(0x27, true), Msg.Key(0x27, false)), Slides.next())
        assertEquals(listOf(Msg.Key(0x25, true), Msg.Key(0x25, false)), Slides.prev())
        assertEquals(listOf(Msg.Key(0x42, true), Msg.Key(0x42, false)), Slides.black())
    }

    @Test fun `timer warnings fire once each at 5 min, 1 min and time up`() {
        val w = TimerWarnings(targetMs = 20 * 60_000L)
        assertNull(w.tick(10 * 60_000L))
        assertEquals(TimerWarning.FIVE_MIN, w.tick(15 * 60_000L))
        assertNull(w.tick(15 * 60_000L + 1_000))
        assertEquals(TimerWarning.ONE_MIN, w.tick(19 * 60_000L))
        assertEquals(TimerWarning.TIME_UP, w.tick(20 * 60_000L))
        assertNull(w.tick(25 * 60_000L))
    }

    @Test fun `jumping past several thresholds reports the latest only once`() {
        val w = TimerWarnings(targetMs = 10 * 60_000L)
        assertEquals(TimerWarning.ONE_MIN, w.tick(9 * 60_000L + 30_000))
        assertNull(w.tick(9 * 60_000L + 40_000))
    }

    @Test fun `no target means no warnings`() {
        assertNull(TimerWarnings(targetMs = 0).tick(99 * 60_000L))
    }

    @Test fun `gyro laser ignores tiny rotation and maps yaw and pitch`() {
        assertNull(Laser.map(gx = 0.01f, gz = 0.015f, dtMs = 16, sensitivity = 1f))
        val m = Laser.map(gx = 0f, gz = 1f, dtMs = 16, sensitivity = 1f)!!
        assertTrue("turning left moves left", m.dx < 0)
        val up = Laser.map(gx = 1f, gz = 0f, dtMs = 16, sensitivity = 1f)!!
        assertTrue("tilting up moves up", up.dy < 0)
    }
}
