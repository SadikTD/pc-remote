package com.pcremote.stream

import com.limelight.nvstream.jni.MoonBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectPolicyTest {
    @Test fun `graceful termination is never retried`() {
        assertFalse(ReconnectPolicy.shouldRetry(MoonBridge.ML_ERROR_GRACEFUL_TERMINATION, false, 0))
    }

    @Test fun `protected content and blocked ports are not retried`() {
        assertFalse(ReconnectPolicy.shouldRetry(MoonBridge.ML_ERROR_PROTECTED_CONTENT, false, 0))
        assertFalse(ReconnectPolicy.shouldRetry(-1, true, 0))
    }

    @Test fun `no video errors are not auto retried`() {
        // they happen after connectionStarted resets the counter, so retrying would loop forever
        assertFalse(ReconnectPolicy.shouldRetry(MoonBridge.ML_ERROR_NO_VIDEO_TRAFFIC, false, 0))
        assertFalse(ReconnectPolicy.shouldRetry(MoonBridge.ML_ERROR_NO_VIDEO_FRAME, false, 0))
    }

    @Test fun `host-side failures are not auto retried`() {
        assertFalse(ReconnectPolicy.shouldRetry(MoonBridge.ML_ERROR_UNEXPECTED_EARLY_TERMINATION, false, 0))
        assertFalse(ReconnectPolicy.shouldRetry(MoonBridge.ML_ERROR_FRAME_CONVERSION, false, 0))
    }

    @Test fun `drops retried up to three times`() {
        assertTrue(ReconnectPolicy.shouldRetry(-1, false, 0))
        assertTrue(ReconnectPolicy.shouldRetry(-1, false, 2))
        assertFalse(ReconnectPolicy.shouldRetry(-1, false, 3))
    }

    @Test fun `delay grows linearly`() {
        assertEquals(1000L, ReconnectPolicy.delayMs(0))
        assertEquals(3000L, ReconnectPolicy.delayMs(2))
    }
}
