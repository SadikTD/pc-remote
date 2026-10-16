package com.pcremote.stream2

import com.pcremote.agent.Msg
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Touch-screen mode: the finger is the pointer. Positions are fractions (0..1) of the stream picture.
 * tap = left click there, long-press = right click, drag = left-button drag, 2-finger drag = scroll.
 */
class DirectTouchModel(private val natural: Boolean = false) {
    private var x0 = 0f; private var y0 = 0f
    private var downAt = 0L
    private var dragging = false
    private var moved = false
    private var fingers = 0
    private var maxFingers = 0
    private var scrollY = 0f
    private var longPressed = false

    /** `px` values are screen pixels used only for slop; `fx`/`fy` are picture fractions. */
    fun down(fx: Float, fy: Float, px: Float, py: Float, t: Long): List<Msg> {
        fingers++
        maxFingers = maxOf(maxFingers, fingers)
        if (fingers > 1) return emptyList()
        x0 = px; y0 = py; downAt = t; moved = false; dragging = false; longPressed = false; scrollY = 0f; maxFingers = 1
        return listOf(pos(fx, fy))
    }

    fun move(fx: Float, fy: Float, px: Float, py: Float, dyPx: Float): List<Msg> {
        if (maxFingers >= 2) {
            if (fingers < 2) return emptyList()
            scrollY += dyPx / 2
            val notches = (scrollY / SCROLL_PX).toInt()
            if (notches == 0) return emptyList()
            scrollY -= notches * SCROLL_PX
            val wheel = notches * 120 // fingers down = content follows = wheel up
            return listOf(Msg.MouseScroll(if (natural) -wheel else wheel))
        }
        if (longPressed) return emptyList()
        if (!moved && hypot(px - x0, py - y0) > SLOP) {
            moved = true; dragging = true
            return listOf(pos(fx, fy), Msg.MouseButton("left", true))
        }
        return if (moved) listOf(pos(fx, fy)) else emptyList()
    }

    /** Called once a finger has rested [LONG_MS] without moving. */
    fun longPress(): List<Msg> {
        if (fingers != 1 || maxFingers != 1 || moved || longPressed) return emptyList()
        longPressed = true
        return listOf(Msg.MouseButton("right", true), Msg.MouseButton("right", false))
    }

    fun up(t: Long): List<Msg> {
        fingers = (fingers - 1).coerceAtLeast(0)
        if (fingers > 0) return emptyList()
        return when {
            dragging -> { dragging = false; listOf(Msg.MouseButton("left", false)) }
            maxFingers == 1 && !moved && !longPressed && t - downAt < LONG_MS -> listOf(Msg.MouseButton("left", true), Msg.MouseButton("left", false))
            maxFingers == 2 && abs(scrollY) < SCROLL_PX && t - downAt < 250 -> listOf(Msg.MouseButton("right", true), Msg.MouseButton("right", false))
            else -> emptyList()
        }
    }

    fun cancel(): List<Msg> {
        fingers = 0
        return if (dragging) { dragging = false; listOf(Msg.MouseButton("left", false)) } else emptyList()
    }

    private fun pos(fx: Float, fy: Float) = Msg.MousePos(fx.coerceIn(0f, 1f), fy.coerceIn(0f, 1f))

    companion object {
        const val SLOP = 14f
        const val LONG_MS = 500L
        private const val SCROLL_PX = 30f
    }
}
