package com.pcremote.modes

import com.pcremote.agent.Msg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaModelTest {
    @Test fun `dial angle maps across a 270 degree sweep and clamps`() {
        assertEquals(0, VolumeDial.angleToVolume(-135f))
        assertEquals(50, VolumeDial.angleToVolume(0f))
        assertEquals(100, VolumeDial.angleToVolume(135f))
        assertEquals(100, VolumeDial.angleToVolume(170f))
        assertEquals(0, VolumeDial.angleToVolume(-179f))
        assertEquals(-135f, VolumeDial.volumeToAngle(0), 0.01f)
    }

    @Test fun `volume keys go to the PC only while a mode is active`() {
        val sent = mutableListOf<Msg>()
        val r = VolumeKeyRouter()
        assertFalse(r.onVolumeKey(up = true))
        r.activateVolume { sent += it }
        assertTrue(r.onVolumeKey(up = true))
        assertTrue(r.onVolumeKey(up = false))
        r.deactivate()
        assertFalse(r.onVolumeKey(up = true))
        assertEquals(listOf(Msg.MediaCmd("vol_up"), Msg.MediaCmd("vol_down")), sent)
    }

    @Test fun `custom mapping is used for slides`() {
        val sent = mutableListOf<Boolean>()
        val r = VolumeKeyRouter()
        r.activate(onKey = { up -> sent += up })
        r.onVolumeKey(up = false)
        assertEquals(listOf(false), sent)
    }
}
