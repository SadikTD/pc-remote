package com.pcremote.stream

/**
 * Calls [onSustained] once the connection has stayed poor for [thresholdMs].
 * The native core reports POOR only on change, so this owns a timer instead of polling.
 */
class PoorConnectionWatch(
    private val thresholdMs: Long,
    private val post: (Long, Runnable) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val onSustained: () -> Unit,
) {
    private val fire = Runnable { onSustained() }
    private var armed = false

    fun onStatus(poor: Boolean) {
        if (poor && !armed) { armed = true; post(thresholdMs, fire) }
        else if (!poor && armed) { armed = false; cancel(fire) }
    }
}
