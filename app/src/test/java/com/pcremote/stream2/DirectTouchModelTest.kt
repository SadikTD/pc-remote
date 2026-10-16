package com.pcremote.stream2

import com.pcremote.agent.Msg
import org.junit.Assert.assertEquals
import org.junit.Test

class DirectTouchModelTest {
    private val m = DirectTouchModel()

    @Test fun tapClicksWhereTouched() {
        assertEquals(listOf<Msg>(Msg.MousePos(0.25f, 0.5f)), m.down(0.25f, 0.5f, 100f, 100f, 0))
        assertEquals(listOf<Msg>(Msg.MouseButton("left", true), Msg.MouseButton("left", false)), m.up(120))
    }

    @Test fun dragHoldsLeftButton() {
        m.down(0.1f, 0.1f, 0f, 0f, 0)
        assertEquals(listOf(Msg.MousePos(0.2f, 0.1f), Msg.MouseButton("left", true)), m.move(0.2f, 0.1f, 40f, 0f, 0f))
        assertEquals(listOf<Msg>(Msg.MousePos(0.3f, 0.1f)), m.move(0.3f, 0.1f, 80f, 0f, 0f))
        assertEquals(listOf<Msg>(Msg.MouseButton("left", false)), m.up(500))
    }

    @Test fun longPressRightClicksOnce() {
        m.down(0.5f, 0.5f, 0f, 0f, 0)
        assertEquals(2, m.longPress().size)
        assertEquals(0, m.longPress().size)
        assertEquals(emptyList<Msg>(), m.up(900))
    }

    @Test fun twoFingersScrollAndClampPositions() {
        m.down(0.5f, 0.5f, 0f, 0f, 0); m.down(0.6f, 0.5f, 10f, 0f, 5)
        assertEquals(listOf<Msg>(Msg.MouseScroll(120)), m.move(0.5f, 0.6f, 0f, 60f, 60f))
        m.up(300); m.up(310)
        assertEquals(listOf<Msg>(Msg.MousePos(1f, 0f)), m.down(1.4f, -0.2f, 0f, 0f, 1000))
    }
}
