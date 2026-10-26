package com.payabli.example.app.sdk

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Holds the window a token recheck must not enter: from a submission starting to its outcome being
 * delivered. The SDK's flag covers only the flight, so [taken] closes the outcome half and [holds]
 * spans both; [withoutLatching] keeps a direct call's own submission off the window.
 *
 * @param flight the SDK's flag for a submission in flight, however it was started.
 * @param scope the caller's, on which the watcher runs beside the submissions.
 */
internal class PayInOutcomeLatch(
    flight: StateFlow<Boolean>,
    scope: CoroutineScope,
) {
    private val pending = AtomicBoolean(false)
    private val direct = AtomicBoolean(false)

    /** True while the window is open: a submission has started and its outcome has not been delivered. */
    val holds: Boolean get() = pending.get()

    init {
        scope.launch {
            flight.collect { inFlight ->
                if (inFlight && !direct.get()) pending.set(true)
            }
        }
    }

    /** Closes the window: the outcome it was held open for has been delivered. */
    fun taken() {
        pending.set(false)
    }

    /**
     * Runs [call] with its submission kept off the window.
     *
     * @param call the direct call, whose outcome returns to its caller.
     * @return what the call answered.
     */
    suspend fun <T> withoutLatching(call: suspend () -> T): T {
        direct.set(true)
        try {
            return call()
        } finally {
            direct.set(false)
        }
    }
}
