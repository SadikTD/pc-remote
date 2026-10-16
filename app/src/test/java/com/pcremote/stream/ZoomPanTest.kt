package com.pcremote.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoomPanTest {
    private fun zp() = ZoomPan().apply { setContentSize(1000f, 500f) }

    @Test fun `pinch keeps focus point fixed`() {
        val z = zp()
        z.pinch(2f, 500f, 250f)
        assertEquals(2f, z.scale, 0.001f)
        // stream point under the focus is unchanged
        assertEquals(500f, z.toStreamX(500f), 0.001f)
        assertEquals(250f, z.toStreamY(250f), 0.001f)
    }

    @Test fun `scale clamped between 1 and 5`() {
        val z = zp()
        z.pinch(10f, 0f, 0f); assertEquals(5f, z.scale, 0.001f)
        z.pinch(0.01f, 0f, 0f); assertEquals(1f, z.scale, 0.001f)
        assertFalse(z.isZoomed)
    }

    @Test fun `pan cannot move the picture off screen`() {
        val z = zp()
        z.pinch(2f, 0f, 0f)
        z.pan(5000f, 5000f)
        assertEquals(0f, z.tx, 0.001f); assertEquals(0f, z.ty, 0.001f)
        z.pan(-5000f, -5000f)
        assertEquals(-1000f, z.tx, 0.001f); assertEquals(-500f, z.ty, 0.001f)
    }

    @Test fun `setContentSize resets zoom`() {
        val z = zp()
        z.pinch(3f, 100f, 100f)
        assertTrue(z.isZoomed)
        z.setContentSize(500f, 1000f)
        assertEquals(1f, z.scale, 0.001f); assertEquals(0f, z.tx, 0.001f)
    }

    @Test fun `follow keeps a point inside the margins`() {
        val z = zp()
        z.pinch(2f, 0f, 0f) // shows view x 0..500
        z.follow(700f, 100f, 100f) // at screen 1400: pan left until it sits at 900
        assertEquals(900f, 700f * z.scale + z.tx, 0.001f)
        z.follow(300f, 100f, 100f) // already inside: no change
        assertEquals(-500f, z.tx, 0.001f)
    }

    @Test fun `unzoomed mapping is identity`() {
        val z = zp()
        assertEquals(123f, z.toStreamX(123f), 0.001f)
    }
}
