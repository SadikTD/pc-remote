package com.pcremote.stream

/** Zoom state for the stream view. Pivot is the view's top-left; coordinates are view-relative. */
class ZoomPan {
    var scale = 1f; private set
    var tx = 0f; private set
    var ty = 0f; private set
    private var w = 0f
    private var h = 0f

    val isZoomed get() = scale > 1.01f

    fun setContentSize(w: Float, h: Float) {
        this.w = w; this.h = h
        scale = 1f; tx = 0f; ty = 0f
    }

    fun pinch(factor: Float, focusX: Float, focusY: Float) {
        // keep the stream point under the fingers fixed on screen
        val sx = toStreamX(focusX); val sy = toStreamY(focusY)
        scale = (scale * factor).coerceIn(1f, 4f)
        tx = focusX - sx * scale
        ty = focusY - sy * scale
        clamp()
    }

    fun pan(dx: Float, dy: Float) { tx += dx; ty += dy; clamp() }

    fun toStreamX(x: Float) = (x - tx) / scale
    fun toStreamY(y: Float) = (y - ty) / scale

    private fun clamp() {
        tx = tx.coerceIn(w - w * scale, 0f)
        ty = ty.coerceIn(h - h * scale, 0f)
    }
}
