package com.pcremote.stream

import org.junit.Assert.assertEquals
import org.junit.Test

class PoorConnectionWatchTest {
    /** Fake main-thread scheduler: records the pending task so the test can fire it. */
    private class FakeScheduler {
        var pending: Runnable? = null
        var delay = 0L
        val post: (Long, Runnable) -> Unit = { d, r -> delay = d; pending = r }
        val cancel: (Runnable) -> Unit = { r -> if (pending === r) pending = null }
        fun fire() { pending?.also { pending = null }?.run() }
    }

    @Test fun `a single poor report fires after the threshold`() {
        // the native core reports POOR once, on change, never repeatedly
        val s = FakeScheduler(); var fired = 0
        val w = PoorConnectionWatch(10_000, s.post, s.cancel) { fired++ }
        w.onStatus(poor = true)
        assertEquals(10_000L, s.delay)
        assertEquals(0, fired)
        s.fire()
        assertEquals(1, fired)
    }

    @Test fun `recovering before the threshold cancels`() {
        val s = FakeScheduler(); var fired = 0
        val w = PoorConnectionWatch(10_000, s.post, s.cancel) { fired++ }
        w.onStatus(poor = true)
        w.onStatus(poor = false)
        s.fire()
        assertEquals(0, fired)
    }

    @Test fun `repeated poor reports schedule once`() {
        val s = FakeScheduler(); var posts = 0
        val countingPost: (Long, Runnable) -> Unit = { d, r -> posts++; s.post(d, r) }
        val w = PoorConnectionWatch(10_000, countingPost, s.cancel) {}
        w.onStatus(poor = true)
        w.onStatus(poor = true)
        assertEquals(1, posts)
    }
}
