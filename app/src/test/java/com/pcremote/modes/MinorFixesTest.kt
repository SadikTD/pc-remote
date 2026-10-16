package com.pcremote.modes

import com.pcremote.agent.Msg
import com.pcremote.agent.wsUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MinorFixesTest {
    @Test fun `after a two-finger scroll the remaining finger does not move the cursor`() {
        val m = TrackpadModel()
        m.down(0, 0f, 0f, 0); m.down(1, 50f, 0f, 0)
        m.move(0, 0f, 40f, 50); m.move(1, 50f, 40f, 50)
        m.up(1, 80)
        assertTrue(m.move(0, 30f, 60f, 100).isEmpty())
    }

    @Test fun `ipv6 hosts are bracketed in the websocket url`() {
        assertEquals("wss://192.168.0.2:47999/v1", wsUrl("192.168.0.2", 47999))
        assertEquals("wss://[2001:db8::1]:47999/v1", wsUrl("2001:db8::1", 47999))
        assertEquals("wss://[2001:db8::1]:47999/v1", wsUrl("[2001:db8::1]", 47999))
    }

    @Test fun `racing needs only a tilt sensor, shooter needs a gyroscope`() {
        val tiltOnly = PadLayouts.available(hasGyro = false, hasTilt = true)
        assertTrue(tiltOnly.any { it.gyro == GyroUse.STEER })
        assertTrue(tiltOnly.none { it.gyro == GyroUse.AIM })
        assertTrue(PadLayouts.available(hasGyro = false, hasTilt = false).none { it.gyro != GyroUse.NONE })
    }

    @Test fun `clipboard limit counts bytes, not characters`() {
        // 400k characters of a 3-byte script is 1.2 MB: over the 1 MB protocol limit
        assertNull(ClipSync().onLocalChange("ক".repeat(400_000)))
    }
}
