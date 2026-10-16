package com.pcremote.modes

import com.pcremote.agent.Msg
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Turns raw touches into mouse messages, laptop-trackpad style:
 * tap = left, 2-finger tap = right, 3-finger tap = middle, 2-finger drag = scroll,
 * long-press then move = drag. Pointer speed with gentle acceleration for fast flicks.
 */
class TrackpadModel(private val speed: Float = 1f, private val natural: Boolean = false) {
    private class P(var x: Float, var y: Float, val x0: Float, val y0: Float)

    private val pointers = mutableMapOf<Int, P>()
    private var maxPointers = 0
    private var gestureStart = 0L
    private var lastMove = 0L
    private var moved = false
    private var dragging = false
    private var fx = 0f
    private var fy = 0f
    private var scrollAcc = 0f

    fun down(id: Int, x: Float, y: Float, t: Long): List<Msg> {
        if (pointers.isEmpty()) {
            gestureStart = t; lastMove = t; moved = false; maxPointers = 0; scrollAcc = 0f
        }
        pointers[id] = P(x, y, x, y)
        maxPointers = maxOf(maxPointers, pointers.size)
        return emptyList()
    }

    fun move(id: Int, x: Float, y: Float, t: Long): List<Msg> {
        val p = pointers[id] ?: return emptyList()
        val dx = x - p.x; val dy = y - p.y
        p.x = x; p.y = y
        if (hypot(x - p.x0, y - p.y0) > TAP_SLOP) moved = true
        val dt = (t - lastMove).coerceAtLeast(1)
        lastMove = t
        return when (pointers.size) {
            1 -> {
                if (maxPointers > 1) return emptyList() // leftover finger of a scroll/right-click
                val v = hypot(dx, dy) / (dt / 1000f) // px/s
                val gain = speed * (1f + ((v - ACCEL_START) / ACCEL_RANGE).coerceIn(0f, ACCEL_MAX))
                fx += dx * gain; fy += dy * gain
                val ix = fx.toInt(); val iy = fy.toInt()
                fx -= ix; fy -= iy
                if (ix == 0 && iy == 0) emptyList() else listOf(Msg.MouseMove(ix, iy))
            }
            2 -> {
                scrollAcc += dy / 2f // centroid movement
                if (abs(scrollAcc) < SCROLL_STEP_PX) return emptyList()
                val notches = (scrollAcc / SCROLL_STEP_PX).toInt()
                scrollAcc -= notches * SCROLL_STEP_PX
                val wheel = -notches * WHEEL_NOTCH // fingers down = page down (Windows default)
                listOf(Msg.MouseScroll(if (natural) -wheel else wheel))
            }
            else -> emptyList()
        }
    }

    /** Called when one finger has rested without moving long enough: start a drag. */
    fun hold(id: Int, t: Long): List<Msg> {
        if (pointers.size != 1 || id !in pointers || moved || dragging) return emptyList()
        dragging = true
        return listOf(Msg.MouseButton("left", true))
    }

    fun up(id: Int, t: Long): List<Msg> {
        pointers.remove(id) ?: return emptyList()
        if (pointers.isNotEmpty()) return emptyList()
        if (dragging) { dragging = false; return listOf(Msg.MouseButton("left", false)) }
        if (moved || t - gestureStart > TAP_MS) return emptyList()
        val b = when (maxPointers) { 1 -> "left"; 2 -> "right"; else -> "middle" }
        return listOf(Msg.MouseButton(b, true), Msg.MouseButton(b, false))
    }

    /** All fingers lifted unexpectedly (gesture cancelled): release anything held. */
    fun cancel(): List<Msg> {
        pointers.clear()
        return if (dragging) { dragging = false; listOf(Msg.MouseButton("left", false)) } else emptyList()
    }

    companion object {
        const val TAP_SLOP = 12f
        const val TAP_MS = 250L
        const val HOLD_MS = 450L
        private const val ACCEL_START = 300f
        private const val ACCEL_RANGE = 1500f
        private const val ACCEL_MAX = 1.5f
        private const val SCROLL_STEP_PX = 30f
        private const val WHEEL_NOTCH = 120
    }
}
