package com.pcremote.stream

import com.limelight.nvstream.jni.MoonBridge

object ReconnectPolicy {
    const val MAX_ATTEMPTS = 3

    // Only network drops are auto-retried. These fail again the same way (or happen after
    // connectionStarted resets the counter, which would loop), so they go straight to the error.
    private val NOT_AUTO_RETRIED = setOf(
        MoonBridge.ML_ERROR_GRACEFUL_TERMINATION,
        MoonBridge.ML_ERROR_NO_VIDEO_TRAFFIC,
        MoonBridge.ML_ERROR_NO_VIDEO_FRAME,
        MoonBridge.ML_ERROR_UNEXPECTED_EARLY_TERMINATION,
        MoonBridge.ML_ERROR_PROTECTED_CONTENT,
        MoonBridge.ML_ERROR_FRAME_CONVERSION,
    )

    fun shouldRetry(errorCode: Int, portsBlocked: Boolean, attemptsSoFar: Int): Boolean =
        errorCode !in NOT_AUTO_RETRIED && !portsBlocked && attemptsSoFar < MAX_ATTEMPTS

    fun delayMs(attemptsSoFar: Int): Long = 1000L * (attemptsSoFar + 1)
}
