package com.pcremote.quality

enum class QualityPreset(val width: Int, val height: Int, val fps: Int, val bitrateKbps: Int) {
    BATTERY(1280, 720, 30, 5_000),
    SMOOTH(1280, 720, 60, 10_000),
    SHARP(1920, 1080, 60, 20_000);

    fun stepDown(): QualityPreset = entries[(ordinal - 1).coerceAtLeast(0)]
}

data class LinkInfo(val linkMbps: Int, val freqMhz: Int, val rssi: Int)

enum class WifiHint { BAND_2_4_GHZ, WEAK_SIGNAL }

object LinkAdvisor {
    private const val MIN_BITRATE_KBPS = 3_000

    fun pick(link: LinkInfo?): QualityPreset =
        if (link != null && link.freqMhz >= 5_000 && link.linkMbps >= 300 && link.rssi >= -67) QualityPreset.SHARP
        else QualityPreset.SMOOTH

    // ponytail: link speed is the phone<->router PHY rate, not end-to-end throughput; a real probe needs PC-side code (stage 3).
    fun bitrateFor(preset: QualityPreset, link: LinkInfo?): Int {
        if (link == null) return preset.bitrateKbps
        return minOf(preset.bitrateKbps, link.linkMbps * 1000 / 4).coerceAtLeast(MIN_BITRATE_KBPS)
    }

    fun hint(link: LinkInfo?): WifiHint? = when {
        link == null -> null
        link.freqMhz in 2_400..2_500 -> WifiHint.BAND_2_4_GHZ
        link.rssi < -70 -> WifiHint.WEAK_SIGNAL
        else -> null
    }
}
