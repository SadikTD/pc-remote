package com.pcremote.stream

/** Reports true once the connection has been continuously poor for at least [thresholdMs]. */
class PoorConnectionWatch(private val thresholdMs: Long = 10_000) {
    private var poorSince = -1L

    fun update(poor: Boolean, nowMs: Long): Boolean {
        if (!poor) { poorSince = -1; return false }
        if (poorSince < 0) poorSince = nowMs
        return nowMs - poorSince >= thresholdMs
    }
}
