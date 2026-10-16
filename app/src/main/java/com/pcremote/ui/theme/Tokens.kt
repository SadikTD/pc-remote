package com.pcremote.ui.theme

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Midnight design tokens as ARGB longs, so they're testable without Compose. */
data class Palette(
    val bg: Long,
    val surface: Long,
    val surfaceHigh: Long,
    val border: Long,
    val accent: Long,
    val accent2: Long,
    val onAccent: Long,
    val ok: Long,
    val warn: Long,
    val bad: Long,
    val text: Long,
    val textDim: Long,
)

/**
 * v3 "screen glow": a deep blue-black room lit by the PC's own screen. One indigo accent; green,
 * amber and red only ever mean live, waiting and wrong.
 */
object Midnight {
    val dark = Palette(
        bg = 0xFF05070C, surface = 0xFF0D111A, surfaceHigh = 0xFF161B27, border = 0x1FFFFFFF,
        accent = 0xFF7C93FF, accent2 = 0xFF5468F0, onAccent = 0xFF05070C,
        ok = 0xFF34D399, warn = 0xFFFBBF24, bad = 0xFFF87171,
        text = 0xFFF2F4FA, textDim = 0xFF98A2B8,
    )
    val light = Palette(
        bg = 0xFFF3F5FA, surface = 0xFFFFFFFF, surfaceHigh = 0xFFE9EDF5, border = 0x1A05070C,
        accent = 0xFF3A50D9, accent2 = 0xFF2B3FB8, onAccent = 0xFFFFFFFF,
        ok = 0xFF0F8A5F, warn = 0xFFA16207, bad = 0xFFC2334A,
        text = 0xFF0B0F1A, textDim = 0xFF566077,
    )
}

private fun channel(c: Long, shift: Int): Double {
    val v = ((c shr shift) and 0xFF) / 255.0
    return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
}

private fun luminance(c: Long) = 0.2126 * channel(c, 16) + 0.7152 * channel(c, 8) + 0.0722 * channel(c, 0)

/** WCAG 2 contrast ratio between two opaque ARGB colours. */
fun contrast(a: Long, b: Long): Double {
    val la = luminance(a); val lb = luminance(b)
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
}
