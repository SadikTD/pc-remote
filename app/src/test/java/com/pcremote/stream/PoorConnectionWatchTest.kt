package com.pcremote.stream

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PoorConnectionWatchTest {
    @Test fun `fires only after sustained poor`() {
        val w = PoorConnectionWatch(10_000)
        assertFalse(w.update(true, 0))
        assertFalse(w.update(true, 9_999))
        assertTrue(w.update(true, 10_000))
    }

    @Test fun `good status resets the timer`() {
        val w = PoorConnectionWatch(10_000)
        w.update(true, 0)
        w.update(false, 5_000)
        assertFalse(w.update(true, 12_000))
        assertTrue(w.update(true, 22_000))
    }
}
