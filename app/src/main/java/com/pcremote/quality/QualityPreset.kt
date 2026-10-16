package com.pcremote.quality

enum class QualityPreset(val width: Int, val height: Int, val fps: Int, val bitrateKbps: Int) {
    BATTERY(1280, 720, 30, 5_000),
    SMOOTH(1280, 720, 60, 10_000),
    SHARP(1920, 1080, 60, 20_000);

    fun stepDown(): QualityPreset = entries[(ordinal - 1).coerceAtLeast(0)]
}
