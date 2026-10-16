package com.pcremote.stream

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue

/** Zoom state for the stream view. Pivot is the view's top-left; coordinates are view-relative. */
class ZoomPan {
    var scale by mutableFloatStateOf(1f); private set
    var tx by mutableFloatStateOf(0f); private set
    var ty by mutableFloatStateOf(0f); private set
    private var w = 0f
    private var h = 0f

    val isZoomed get() = scale > 1.01f

    fun setContentSize(w: Float, h: Float) {
        this.w = w; this.h = h
        reset()
    }

    fun reset() { scale = 1f; tx = 0f; ty = 0f }

    fun pinch(factor: Float, focusX: Float, focusY: Float) {
        // keep the stream point under the fingers fixed on screen
        val sx = toStreamX(focusX); val sy = toStreamY(focusY)
        scale = (scale * factor).coerceIn(1f, MAX)
        tx = focusX - sx * scale
        ty = focusY - sy * scale
        clamp()
    }

    fun pan(dx: Float, dy: Float) { tx += dx; ty += dy; clamp() }

    /** Pans just enough to keep view point (x, y) at least [margin] inside the screen edges. */
    fun follow(x: Float, y: Float, margin: Float) {
        val sx = x * scale + tx; val sy = y * scale + ty
        pan(edge(sx, w, margin), edge(sy, h, margin))
    }

    fun toStreamX(x: Float) = (x - tx) / scale
    fun toStreamY(y: Float) = (y - ty) / scale

    private fun edge(s: Float, size: Float, m: Float) = when {
        s < m -> m - s
        s > size - m -> size - m - s
        else -> 0f
    }

    private fun clamp() {
        tx = tx.coerceIn(w - w * scale, 0f)
        ty = ty.coerceIn(h - h * scale, 0f)
    }

    companion object { const val MAX = 5f }
}
