package com.payabli.sdk.core.event

import androidx.annotation.RestrictTo
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Deep enough that no real lifecycle burst reaches it, small enough to stay a bound. A subscriber
 * sixty-four transitions behind has stopped collecting, and holding more only delays noticing.
 */
internal const val BUFFER = 64

/**
 * Fans lifecycle events out to every active subscriber.
 *
 * For **observation only**, where several watchers may each want the same transition: a host driving UI
 * while something else logs. That is the one problem multicast solves, and the overflow policy is settled
 * here so each capability with a lifecycle stream does not re-argue it.
 *
 * **Not a telemetry channel, and not a general event bus.** With no subscriber attached an event is
 * discarded, which is correct for something nobody is watching and wrong for anything that has to be
 * delivered. A reporting sink has one consumer rather than many, and wants batching and retry; it takes
 * its records directly, not through here.
 *
 * It buffers and drops rather than suspending the emitter, as `PayabliAuth.tokenChanges` does, so a stalled
 * collector cannot hold up a payment. It does **not** take that type's buffer of one: keeping only the
 * newest suits a token, where an older value is superseded, while a transition is a distinct fact. One is
 * also too small to buffer anything, since two events emitted before a collector is scheduled would drop
 * the first, losing transitions on a healthy subscriber rather than only a stalled one.
 *
 * So the guarantee is bounded: **every event reaches every subscriber that keeps up**, and only one more
 * than [BUFFER] behind loses the oldest.
 *
 * No replay, by choice. "What is true right now" is `state`'s question, and a `StateFlow` answers it for
 * every subscriber rather than approximately for the last event.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class EventMulticaster<T : Any> {
    private val sink =
        MutableSharedFlow<T>(
            extraBufferCapacity = BUFFER,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    /**
     * Events emitted after a subscriber subscribes, for as long as it keeps up.
     *
     * Bounded, not guaranteed: a subscriber more than [BUFFER] behind loses the oldest, and one that is not
     * subscribed sees nothing. Do not treat this as reliable delivery.
     */
    public val events: SharedFlow<T> = sink.asSharedFlow()

    /**
     * Delivers [event] to every active subscriber.
     *
     * Not `suspend`, and cannot fail: [BufferOverflow.DROP_OLDEST] makes the emit always succeed by
     * discarding the oldest buffered value instead of waiting for room. Callers therefore need no failure
     * branch, which is what keeps an event emission out of a payment path's error handling.
     */
    public fun emit(event: T) {
        sink.tryEmit(event)
    }
}
