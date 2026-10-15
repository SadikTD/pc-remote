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
