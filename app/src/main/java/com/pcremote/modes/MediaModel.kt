package com.pcremote.modes

import com.pcremote.agent.Msg
import kotlin.math.roundToInt

/** The volume dial sweeps 270°, from -135° (0%) to +135° (100%). */
object VolumeDial {
    const val SWEEP = 270f
    fun angleToVolume(deg: Float): Int = (((deg + SWEEP / 2) / SWEEP) * 100f).roundToInt().coerceIn(0, 100)
    fun volumeToAngle(v: Int): Float = v.coerceIn(0, 100) / 100f * SWEEP - SWEEP / 2

    /** Next volume for a drag at [deg], or null if it's a wrap across the bottom gap. */
    fun next(prev: Int, deg: Float): Int? = angleToVolume(deg).takeIf { kotlin.math.abs(it - prev) <= 50 }
}

/** Routes the phone's hardware volume keys to the PC while a mode wants them. */
class VolumeKeyRouter {
    private var handler: ((Boolean) -> Unit)? = null
    private var allowRepeat = false

    /** Default mapping: PC volume up/down (holding the key keeps adjusting). */
    fun activateVolume(send: (Msg) -> Unit) = activate(onKey = { up -> send(Msg.MediaCmd(if (up) "vol_up" else "vol_down")) }, allowRepeat = true)

    fun activate(onKey: (up: Boolean) -> Unit, allowRepeat: Boolean = false) { handler = onKey; this.allowRepeat = allowRepeat }

    fun deactivate() { handler = null }

    val active get() = handler != null

    /** Returns true if the key was consumed. */
    fun onVolumeKey(up: Boolean, repeat: Boolean = false): Boolean {
        val h = handler ?: return false
        if (!repeat || allowRepeat) h(up)
        return true
    }
}

/** App-wide router; MainActivity feeds hardware keys into it. */
val VolumeKeys = VolumeKeyRouter()
