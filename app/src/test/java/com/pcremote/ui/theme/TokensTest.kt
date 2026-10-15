package com.pcremote.ui.theme

import org.junit.Assert.assertTrue
import org.junit.Test

class TokensTest {
    private fun check(p: Palette) {
        for (s in listOf(p.bg, p.surface, p.surfaceHigh)) {
            assertTrue("text on $s", contrast(p.text, s) >= 4.5)
        }
        assertTrue("textDim on surface", contrast(p.textDim, p.surface) >= 3.0)
        assertTrue("onAccent on accent", contrast(p.onAccent, p.accent) >= 4.5)
    }

    @Test fun `text on every surface meets WCAG AA`() = check(Midnight.dark)

    @Test fun `light palette derived from same tokens also meets AA`() = check(Midnight.light)

    @Test fun `contrast of black on white is 21`() {
        assertTrue(contrast(0xFF000000, 0xFFFFFFFF) in 20.9..21.1)
    }
}
