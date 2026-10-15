package com.pcremote.modes

import com.pcremote.agent.Msg
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** XInput button masks (what the PC's virtual Xbox 360 controller expects). */
enum class PadButton(val mask: Int, val label: String) {
    UP(0x0001, "↑"), DOWN(0x0002, "↓"), LEFT(0x0004, "←"), RIGHT(0x0008, "→"),
    START(0x0010, "☰"), BACK(0x0020, "⧉"), LS(0x0040, "L3"), RS(0x0080, "R3"),
    LB(0x0100, "LB"), RB(0x0200, "RB"), GUIDE(0x0400, "⌂"),
    A(0x1000, "A"), B(0x2000, "B"), X(0x4000, "X"), Y(0x8000, "Y"),
}

/** Immutable controller state; sticks are -1..1 in screen coordinates (down = +y). */
data class PadState(val btn: Int = 0, val lt: Int = 0, val rt: Int = 0, val lx: Int = 0, val ly: Int = 0, val rx: Int = 0, val ry: Int = 0) {
    fun press(b: PadButton) = copy(btn = btn or b.mask)
    fun release(b: PadButton) = copy(btn = btn and b.mask.inv())

    fun stick(left: Boolean, x: Float, y: Float): PadState {
        val ax = axis(x.coerceIn(-1f, 1f)); val ay = axis(-y.coerceIn(-1f, 1f)) // XInput: up is +y
        return if (left) copy(lx = ax, ly = ay) else copy(rx = ax, ry = ay)
    }

    fun trigger(left: Boolean, v: Float): PadState {
        val t = (v.coerceIn(0f, 1f) * 255).toInt()
        return if (left) copy(lt = t) else copy(rt = t)
    }

    fun toMsg(id: Int) = Msg.Pad(id, btn, lt, rt, lx, ly, rx, ry)

    private fun axis(v: Float) = if (v >= 0) (v * 32767).toInt() else (v * 32768).toInt()
}

/** Sends at most every [minIntervalMs] and only when the state changed; the latest pending state wins. */
class PadSender(private val minIntervalMs: Long = 8) {
    private var lastSent: PadState? = null
    private var lastTime = Long.MIN_VALUE / 2
    private var pending: PadState? = null

    fun offer(s: PadState, now: Long, id: Int = 0): Msg.Pad? {
        if (s == lastSent) { pending = null; return null }
        if (now - lastTime >= minIntervalMs) return send(s, now, id)
        pending = s
        return null
    }

    /** Call on a timer; sends the merged pending state when allowed. */
    fun flush(now: Long, id: Int = 0): Msg.Pad? {
        val p = pending ?: return null
        if (now - lastTime < minIntervalMs) return null
        return send(p, now, id)
    }

    private fun send(s: PadState, now: Long, id: Int): Msg.Pad {
        lastSent = s; lastTime = now; pending = null
        return s.toMsg(id)
    }
}

enum class GyroUse { NONE, STEER, AIM }
enum class ElementKind { BUTTON, STICK_L, STICK_R, DPAD, TRIGGER_L, TRIGGER_R }

/** A control on screen: centre (x, y) and size as fractions of the pad area's height. */
@Serializable
data class PadElement(val kind: ElementKind, val x: Float, val y: Float, val size: Float, val button: PadButton? = null)

@Serializable
data class PadLayout(
    val id: String,
    val gyro: GyroUse,
    val elements: List<PadElement>,
    val opacity: Float = 0.85f,
    val haptic: Float = 1f,
)

object PadLayouts {
    private fun b(button: PadButton, x: Float, y: Float, s: Float = 0.17f) = PadElement(ElementKind.BUTTON, x, y, s, button)
    private val face = listOf(b(PadButton.Y, 0.86f, 0.38f), b(PadButton.X, 0.78f, 0.55f), b(PadButton.B, 0.94f, 0.55f), b(PadButton.A, 0.86f, 0.72f))
    private val shoulders = listOf(
        PadElement(ElementKind.TRIGGER_L, 0.10f, 0.08f, 0.14f), b(PadButton.LB, 0.24f, 0.08f, 0.13f),
        b(PadButton.RB, 0.76f, 0.08f, 0.13f), PadElement(ElementKind.TRIGGER_R, 0.90f, 0.08f, 0.14f),
    )
    private val center = listOf(b(PadButton.BACK, 0.42f, 0.30f, 0.11f), b(PadButton.GUIDE, 0.50f, 0.20f, 0.11f), b(PadButton.START, 0.58f, 0.30f, 0.11f))

    val XBOX = PadLayout("xbox", GyroUse.NONE, shoulders + center + face + listOf(
        PadElement(ElementKind.STICK_L, 0.16f, 0.48f, 0.36f), PadElement(ElementKind.DPAD, 0.32f, 0.78f, 0.30f),
        PadElement(ElementKind.STICK_R, 0.66f, 0.80f, 0.30f),
    ))
    val RACING = PadLayout("racing", GyroUse.STEER, center + listOf(
        PadElement(ElementKind.TRIGGER_L, 0.12f, 0.62f, 0.45f), PadElement(ElementKind.TRIGGER_R, 0.88f, 0.62f, 0.45f),
        b(PadButton.A, 0.70f, 0.35f), b(PadButton.B, 0.30f, 0.35f), b(PadButton.X, 0.40f, 0.85f, 0.14f), b(PadButton.Y, 0.60f, 0.85f, 0.14f),
    ))
    val SHOOTER = PadLayout("shooter", GyroUse.AIM, shoulders + center + face + listOf(
        PadElement(ElementKind.STICK_L, 0.18f, 0.60f, 0.40f), b(PadButton.LS, 0.34f, 0.86f, 0.13f), b(PadButton.RS, 0.66f, 0.86f, 0.13f),
    ))
    val MINIMAL = PadLayout("minimal", GyroUse.NONE, listOf(
        PadElement(ElementKind.DPAD, 0.20f, 0.55f, 0.45f), b(PadButton.A, 0.80f, 0.65f, 0.22f), b(PadButton.B, 0.92f, 0.45f, 0.22f),
        b(PadButton.X, 0.68f, 0.45f, 0.22f), b(PadButton.Y, 0.80f, 0.25f, 0.22f), b(PadButton.START, 0.50f, 0.15f, 0.12f),
    ))

    val all = listOf(XBOX, RACING, SHOOTER, MINIMAL)

    fun available(hasGyro: Boolean) = all.filter { hasGyro || it.gyro == GyroUse.NONE }

    private val json = Json { ignoreUnknownKeys = true }
    fun toJson(l: PadLayout) = json.encodeToString(PadLayout.serializer(), l)
    fun fromJson(s: String): PadLayout? = runCatching { json.decodeFromString(PadLayout.serializer(), s) }.getOrNull()
}
