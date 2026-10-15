package com.pcremote.stream

import com.limelight.R
import com.limelight.nvstream.jni.MoonBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionErrorsTest {
    @Test fun `blocked ports win over any code`() {
        val e = ConnectionErrors.forTermination(MoonBridge.ML_ERROR_NO_VIDEO_TRAFFIC, portsBlocked = true)
        assertEquals(R.string.pcr_err_blocked_title, e.title)
        assertTrue(e.retryable)
    }

    @Test fun `no video traffic explains sunshine and secure screen`() {
        assertEquals(R.string.pcr_err_no_video_msg,
            ConnectionErrors.forTermination(MoonBridge.ML_ERROR_NO_VIDEO_TRAFFIC, false).message)
        assertEquals(R.string.pcr_err_no_video_msg,
            ConnectionErrors.forTermination(MoonBridge.ML_ERROR_NO_VIDEO_FRAME, false).message)
    }

    @Test fun `protected content is not retryable`() {
        assertFalse(ConnectionErrors.forTermination(MoonBridge.ML_ERROR_PROTECTED_CONTENT, false).retryable)
    }

    @Test fun `early termination and frame conversion explain the PC side, not wifi`() {
        assertEquals(R.string.pcr_err_start_title,
            ConnectionErrors.forTermination(MoonBridge.ML_ERROR_UNEXPECTED_EARLY_TERMINATION, false).title)
        assertEquals(R.string.pcr_err_decoder_title,
            ConnectionErrors.forTermination(MoonBridge.ML_ERROR_FRAME_CONVERSION, false).title)
    }

    @Test fun `unknown code is a retryable lost connection`() {
        val e = ConnectionErrors.forTermination(-12345, false)
        assertEquals(R.string.pcr_err_lost_title, e.title)
        assertTrue(e.retryable)
    }

    @Test fun `video stage failure is a decoder problem`() {
        assertEquals(R.string.pcr_err_decoder_title, ConnectionErrors.forStageFailure("video decoder", false).title)
        assertEquals(R.string.pcr_err_start_title, ConnectionErrors.forStageFailure("RTSP handshake", false).title)
    }
}
