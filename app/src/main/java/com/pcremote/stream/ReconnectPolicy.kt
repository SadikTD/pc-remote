package com.pcremote.stream

import com.limelight.nvstream.jni.MoonBridge

object ReconnectPolicy {
    const val MAX_ATTEMPTS = 3

    fun shouldRetry(errorCode: Int, portsBlocked: Boolean, attemptsSoFar: Int): Boolean =
        errorCode != MoonBridge.ML_ERROR_GRACEFUL_TERMINATION &&
            !portsBlocked &&
            ConnectionErrors.forTermination(errorCode, false).retryable &&
            attemptsSoFar < MAX_ATTEMPTS

    fun delayMs(attemptsSoFar: Int): Long = 1000L * (attemptsSoFar + 1)
}
