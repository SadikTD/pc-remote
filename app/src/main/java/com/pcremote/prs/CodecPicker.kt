package com.pcremote.prs

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build

/** Which stream codecs this phone can decode in hardware, best first ("hevc", "av1", "h264"). */
object CodecPicker {
    val MIME = mapOf("hevc" to "video/hevc", "av1" to "video/av01", "h264" to "video/avc")
    private val PREFERENCE = listOf("hevc", "av1", "h264")

    data class Decoder(val name: String, val mime: String, val hardware: Boolean, val lowLatency: Boolean)

    fun supported(): List<String> = order(decoders())

    /** The decoder to use for `codec`: hardware first, low-latency first. */
    fun decoderFor(codec: String): Decoder? = decoders().filter { it.mime == MIME[codec] }.sortedWith(compareBy({ !it.hardware }, { !it.lowLatency })).firstOrNull()

    fun order(available: List<Decoder>): List<String> {
        val hw = PREFERENCE.filter { c -> available.any { it.mime == MIME[c] && it.hardware } }
        return hw.ifEmpty { PREFERENCE.filter { c -> available.any { it.mime == MIME[c] } } }
    }

    /** After `failed` couldn't be decoded, what to ask the host for next; empty means give up. */
    fun fallback(current: List<String>, failed: String): List<String> = current.filter { it != failed }

    private fun decoders(): List<Decoder> = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        .filter { !it.isEncoder }
        .flatMap { info -> info.supportedTypes.filter { it in MIME.values }.map { info to it } }
        .map { (info, mime) ->
            val hw = if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated
            else !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android.")
            val ll = Build.VERSION.SDK_INT >= 30 &&
                runCatching { info.getCapabilitiesForType(mime).isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency) }.getOrDefault(false)
            Decoder(info.name, mime, hw, ll)
        }

    /** Vendor switches that cut decoder latency, by decoder-name prefix. */
    fun vendorLowLatencyKeys(name: String): Map<String, Int> {
        val n = name.lowercase()
        return when {
            n.startsWith("c2.qti") || n.startsWith("omx.qcom") ->
                mapOf("vendor.qti-ext-dec-low-latency.enable" to 1, "vendor.qti-ext-dec-picture-order.enable" to 1)
            n.startsWith("c2.mtk") || n.startsWith("omx.mtk") -> mapOf("vendor.mtk.vdec.lowlatency.mode" to 1)
            n.startsWith("c2.exynos") || n.startsWith("omx.exynos") -> mapOf("vendor.rtc-ext-dec-low-latency.enable" to 1)
            n.startsWith("omx.hisi") || n.startsWith("c2.hisi") ->
                mapOf("vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-req" to 1,
                    "vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-rdy" to -1)
            n.startsWith("c2.amlogic") || n.startsWith("omx.amlogic") -> mapOf("vendor.low-latency.enable" to 1)
            else -> emptyMap()
        }
    }
}
