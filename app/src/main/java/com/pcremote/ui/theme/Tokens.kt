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

object Midnight {
    val dark = Palette(
        bg = 0xFF0B0D12, surface = 0xFF141821, surfaceHigh = 0xFF1C2130, border = 0x14FFFFFF,
        accent = 0xFF7C8CFF, accent2 = 0xFF4FD1FF, onAccent = 0xFF0B0D12,
        ok = 0xFF3DDC97, warn = 0xFFFFC857, bad = 0xFFFF5D73,
        text = 0xFFE9ECF5, textDim = 0xFF8F98AD,
    )
    val light = Palette(
        bg = 0xFFF6F7FB, surface = 0xFFFFFFFF, surfaceHigh = 0xFFEEF0F6, border = 0x140B0D12,
        accent = 0xFF4F5BD5, accent2 = 0xFF0A8FC2, onAccent = 0xFFFFFFFF,
        ok = 0xFF1E9E69, warn = 0xFFB7791F, bad = 0xFFD63A55,
        text = 0xFF0E1220, textDim = 0xFF5A6275,
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
