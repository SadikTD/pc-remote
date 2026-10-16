package com.pcremote.moonlight

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Runs a blocking call on its own thread. Cancelling the caller returns at once and leaves the
 * thread to finish on its own — for calls like Moonlight's pairing read, which has no timeout and
 * ignores interrupts.
 */
suspend fun <T> abandonable(block: () -> T): T = suspendCancellableCoroutine { cont ->
    val t = Thread {
        val r = runCatching(block)
        if (cont.isActive) r.fold({ cont.resume(it) }, { cont.resumeWithException(it) })
    }
    t.isDaemon = true
    cont.invokeOnCancellation { t.interrupt() }
    t.start()
}
