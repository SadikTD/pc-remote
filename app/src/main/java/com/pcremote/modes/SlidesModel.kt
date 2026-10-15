package com.pcremote.modes

import com.pcremote.agent.Msg
import kotlin.math.abs
import kotlin.math.roundToInt

/** Presenter keys: arrows work in PowerPoint, Google Slides, Keynote-in-browser and PDF viewers. */
object Slides {
    private fun press(vk: Int) = listOf(Msg.Key(vk, true), Msg.Key(vk, false))
    fun next() = press(0x27)
    fun prev() = press(0x25)
    /** B blanks the screen in PowerPoint (and toggles it back). */
    fun black() = press(0x42)
}

enum class TimerWarning { FIVE_MIN, ONE_MIN, TIME_UP }

/** Reports each warning once as the elapsed time crosses target-5 min, target-1 min and target. */
class TimerWarnings(private val targetMs: Long) {
    private var reached = -1

    fun tick(elapsedMs: Long): TimerWarning? {
        if (targetMs <= 0) return null
        val level = when {
            elapsedMs >= targetMs -> 2
            elapsedMs >= targetMs - 60_000 -> 1
            elapsedMs >= targetMs - 5 * 60_000 -> 0
            else -> -1
        }
        if (level <= reached) return null
        reached = level
        return TimerWarning.entries[level]
    }
}

/** Gyro → pointer for the laser: yaw moves left/right, pitch moves up/down. */
object Laser {
    private const val DEADZONE = 0.02f // rad/s
    private const val PX_PER_RAD = 900f

    fun map(gx: Float, gz: Float, dtMs: Long, sensitivity: Float): Msg.MouseMove? {
        val yaw = if (abs(gz) < DEADZONE) 0f else gz
        val pitch = if (abs(gx) < DEADZONE) 0f else gx
        if (yaw == 0f && pitch == 0f) return null
        val k = PX_PER_RAD * sensitivity * dtMs / 1000f
        val dx = (-yaw * k).roundToInt(); val dy = (-pitch * k).roundToInt()
        return if (dx == 0 && dy == 0) null else Msg.MouseMove(dx, dy)
    }
}
