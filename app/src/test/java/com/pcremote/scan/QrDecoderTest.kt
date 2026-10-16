package com.pcremote.scan

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrDecoderTest {
    private val url = "pcremote://pair?n=SADIKTD007&h=192.168.0.126&p=47999&fp=" + "ab".repeat(32) + "&t=M9tGaEcnoA4efDGpUVnMag"

    /** Renders a QR into an 8-bit luminance frame like a camera Y plane, with a quiet border. */
    private fun frame(text: String, scale: Int = 6, border: Int = 40): Triple<ByteArray, Int, Int> {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0)
        val w = m.width * scale + border * 2; val h = m.height * scale + border * 2
        val y = ByteArray(w * h) { 0xF0.toByte() }
        for (r in 0 until m.height) for (c in 0 until m.width) if (m[c, r]) {
            for (dy in 0 until scale) for (dx in 0 until scale) y[(border + r * scale + dy) * w + border + c * scale + dx] = 0x10
        }
        return Triple(y, w, h)
    }

    @Test fun `decodes a pairing QR from a camera-like frame`() {
        val (y, w, h) = frame(url)
        assertEquals(url, QrDecoder.decode(y, w, h, rowStride = w))
    }

    @Test fun `handles row padding in the camera buffer`() {
        val (y, w, h) = frame(url)
        val stride = w + 64
        val padded = ByteArray(stride * h).also { for (r in 0 until h) System.arraycopy(y, r * w, it, r * stride, w) }
        assertEquals(url, QrDecoder.decode(padded, w, h, rowStride = stride))
    }

    @Test fun `blank frame decodes to null`() {
        assertNull(QrDecoder.decode(ByteArray(320 * 240) { 0x80.toByte() }, 320, 240, 320))
    }
}
