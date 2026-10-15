package com.pcremote.modes

import com.pcremote.agent.Msg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackpadModelTest {
    private fun model(speed: Float = 1f, natural: Boolean = false) = TrackpadModel(speed, natural)

    @Test fun `one finger tap is a left click`() {
        val m = model()
        m.down(0, 100f, 100f, 0)
        val out = m.up(0, 120)
        assertEquals(listOf(Msg.MouseButton("left", true), Msg.MouseButton("left", false)), out)
    }

    @Test fun `two finger tap is a right click`() {
        val m = model()
        m.down(0, 100f, 100f, 0); m.down(1, 200f, 100f, 10)
        m.up(1, 100)
        val out = m.up(0, 110)
        assertEquals(listOf(Msg.MouseButton("right", true), Msg.MouseButton("right", false)), out)
    }

    @Test fun `three finger tap is a middle click`() {
        val m = model()
        m.down(0, 0f, 0f, 0); m.down(1, 50f, 0f, 5); m.down(2, 100f, 0f, 10)
        m.up(2, 90); m.up(1, 95)
        assertEquals(listOf(Msg.MouseButton("middle", true), Msg.MouseButton("middle", false)), m.up(0, 100))
    }

    @Test fun `slow one finger move maps one to one at speed 1`() {
        val m = model()
        m.down(0, 100f, 100f, 0)
        assertEquals(listOf(Msg.MouseMove(10, -5)), m.move(0, 110f, 95f, 100))
    }

    @Test fun `pointer speed scales movement`() {
        val m = model(speed = 2f)
        m.down(0, 0f, 0f, 0)
        assertEquals(listOf(Msg.MouseMove(20, 0)), m.move(0, 10f, 0f, 100))
    }

    @Test fun `fast movement accelerates`() {
        val m = model()
        m.down(0, 0f, 0f, 0)
        val fast = m.move(0, 100f, 0f, 20).single() as Msg.MouseMove // 5000 px/s
        assertTrue(fast.dx > 100)
    }

    @Test fun `a moved finger is not a tap`() {
        val m = model()
        m.down(0, 0f, 0f, 0)
        m.move(0, 40f, 0f, 50)
        assertTrue(m.up(0, 100).isEmpty())
    }

    @Test fun `two finger drag down scrolls down, natural inverts`() {
        val m = model()
        m.down(0, 0f, 0f, 0); m.down(1, 50f, 0f, 0)
        m.move(0, 0f, 30f, 50)
        val out = m.move(1, 50f, 30f, 50)
        assertTrue(out.single() is Msg.MouseScroll && (out.single() as Msg.MouseScroll).dy < 0)

        val n = model(natural = true)
        n.down(0, 0f, 0f, 0); n.down(1, 50f, 0f, 0)
        n.move(0, 0f, 30f, 50)
        assertTrue((n.move(1, 50f, 30f, 50).single() as Msg.MouseScroll).dy > 0)
    }

    @Test fun `long press then drag holds the left button`() {
        val m = model()
        m.down(0, 0f, 0f, 0)
        assertEquals(listOf(Msg.MouseButton("left", true)), m.hold(0, 500))
        assertEquals(listOf(Msg.MouseMove(10, 0)), m.move(0, 10f, 0f, 700))
        assertEquals(listOf(Msg.MouseButton("left", false)), m.up(0, 800))
    }
}
