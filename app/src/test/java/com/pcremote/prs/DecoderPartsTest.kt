package com.pcremote.prs

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DecoderPartsTest {
    private fun d(mime: String, hw: Boolean = true) = CodecPicker.Decoder("c2.x.$mime", mime, hw, false)

    @Test fun fallsBackInOrder() {
        val all = listOf(d("video/avc"), d("video/hevc"), d("video/av01"))
        assertEquals(listOf("hevc", "av1", "h264"), CodecPicker.order(all))
        assertEquals("software-only codecs are left out when hardware exists", listOf("h264"), CodecPicker.order(listOf(d("video/avc"), d("video/hevc", hw = false))))
        assertEquals(listOf("h264"), CodecPicker.order(listOf(d("video/avc", hw = false))))
        var list = CodecPicker.order(all)
        list = CodecPicker.fallback(list, "hevc"); assertEquals(listOf("av1", "h264"), list)
        list = CodecPicker.fallback(list, "av1"); assertEquals(listOf("h264"), list)
        assertTrue(CodecPicker.fallback(list, "h264").isEmpty())
    }

    @Test fun vendorKeysByDecoderName() {
        assertEquals(1, CodecPicker.vendorLowLatencyKeys("c2.qti.hevc.decoder")["vendor.qti-ext-dec-low-latency.enable"])
        assertEquals(mapOf("vendor.mtk.vdec.lowlatency.mode" to 1), CodecPicker.vendorLowLatencyKeys("c2.mtk.hevc.decoder"))
        assertEquals(mapOf("vendor.mtk.vdec.lowlatency.mode" to 1), CodecPicker.vendorLowLatencyKeys("OMX.MTK.VIDEO.DECODER.AVC"))
        assertTrue(CodecPicker.vendorLowLatencyKeys("c2.exynos.h264.decoder").isNotEmpty())
        assertTrue(CodecPicker.vendorLowLatencyKeys("c2.android.avc.decoder").isEmpty())
    }

    @Test fun audioBufferAtLeast20ms() {
        assertEquals(3840, AudioPlayer.bufferBytes(1000))
        assertEquals(7680, AudioPlayer.bufferBytes(7680))
        assertEquals("never below the platform minimum", 20000, AudioPlayer.bufferBytes(20000))
        assertEquals(19, AudioPlayer.opusHead().size)
    }

    private val sc = byteArrayOf(0, 0, 0, 1)
    private val sc3 = byteArrayOf(0, 0, 1)

    @Test fun csdFromH264Keyframe() {
        val sps = byteArrayOf(0x67, 1, 2); val pps = byteArrayOf(0x68, 3); val idr = byteArrayOf(0x65, 9, 9, 9)
        val csd = VideoDecoder.csd(sc + sps + sc3 + pps + sc + idr, "h264")
        assertEquals(2, csd.size)
        assertArrayEquals(sc + sps, csd[0]); assertArrayEquals(sc + pps, csd[1])
    }

    @Test fun csdFromHevcKeyframe() {
        val vps = byteArrayOf(0x40, 1); val sps = byteArrayOf(0x42, 1, 5); val pps = byteArrayOf(0x44, 1); val idr = byteArrayOf(0x26, 1, 7)
        val csd = VideoDecoder.csd(sc + vps + sc + sps + sc + pps + sc + idr, "hevc")
        assertEquals(1, csd.size)
        assertArrayEquals(sc + vps + sc + sps + sc + pps, csd[0])
        assertTrue(VideoDecoder.csd(byteArrayOf(0x12, 0, 1), "av1").isEmpty())
    }
}
