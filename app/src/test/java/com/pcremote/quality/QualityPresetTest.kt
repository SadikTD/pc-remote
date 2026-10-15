package com.pcremote.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QualityPresetTest {
    @Test fun `fast 5ghz link picks sharp`() {
        assertEquals(QualityPreset.SHARP, LinkAdvisor.pick(LinkInfo(866, 5180, -50)))
    }

    @Test fun `2_4ghz link picks smooth and hints`() {
        val link = LinkInfo(144, 2437, -50)
        assertEquals(QualityPreset.SMOOTH, LinkAdvisor.pick(link))
        assertEquals(WifiHint.BAND_2_4_GHZ, LinkAdvisor.hint(link))
    }

    @Test fun `weak signal hints`() {
        assertEquals(WifiHint.WEAK_SIGNAL, LinkAdvisor.hint(LinkInfo(400, 5180, -75)))
    }

    @Test fun `no wifi info falls back to smooth without hint`() {
        assertEquals(QualityPreset.SMOOTH, LinkAdvisor.pick(null))
        assertNull(LinkAdvisor.hint(null))
        assertEquals(QualityPreset.SMOOTH.bitrateKbps, LinkAdvisor.bitrateFor(QualityPreset.SMOOTH, null))
    }

    @Test fun `bitrate capped to a quarter of link speed with floor`() {
        assertEquals(10_000, LinkAdvisor.bitrateFor(QualityPreset.SHARP, LinkInfo(40, 5180, -50)))
        assertEquals(3_000, LinkAdvisor.bitrateFor(QualityPreset.SHARP, LinkInfo(6, 2437, -80)))
        assertEquals(QualityPreset.SHARP.bitrateKbps, LinkAdvisor.bitrateFor(QualityPreset.SHARP, LinkInfo(866, 5180, -50)))
    }

    @Test fun `step down goes sharp to smooth to battery and stops`() {
        assertEquals(QualityPreset.SMOOTH, QualityPreset.SHARP.stepDown())
        assertEquals(QualityPreset.BATTERY, QualityPreset.SMOOTH.stepDown())
        assertEquals(QualityPreset.BATTERY, QualityPreset.BATTERY.stepDown())
    }
}
