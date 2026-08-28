package com.payabli.sdk.core.telemetry

import androidx.annotation.RestrictTo

/**
 * Where a capability hands an event it wants counted.
 *
 * The seam is here rather than in the telemetry module so that no capability artifact depends on a sibling.
 * A module that emits names this interface; the module that queues, allowlists and ships the events installs
 * an implementation through [TelemetryRecorders]. An integrator who links neither still compiles.
 *
 * Three requirements an implementation must hold, because a payment path calls this:
 *
 * - **It returns immediately.** Enqueue and nothing more. Not `suspend`, so no call site can accidentally
 *   suspend a payment on a reporting channel.
 * - **It never throws.** [TelemetryRecorders.record] catches what escapes, and an implementation that leans
 *   on that is still wrong.
 * - **It is safe to call from any thread**, concurrently, on any dispatcher.
 *
 * [properties] is scrubbed by the implementation before anything is retained, so a caller cannot be trusted
 * to have got it right and is not the last line. Callers still send only what the catalog declares for the
 * event: no instrument data, no payer data, no credential, no resolved path, and no server-supplied prose.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public fun interface TelemetryRecorder {
    /** Records [event] with [properties]. Returns without blocking; throws nothing. */
    public fun record(
        event: String,
        properties: Map<String, String>,
    )
}

/**
 * A recorder that can be told which session an event belongs to, rather than assuming its own.
 *
 * Used through [TelemetryRecorders.recordFor], which falls back to [TelemetryRecorder.record] for a
 * recorder that does not implement this: the event and its properties still arrive, and only the session
 * does not. That fallback is what keeps a test double a two-argument lambda.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public interface SessionScopedRecorder {
    /** Records [event] as belonging to [session], whichever session this recorder itself serves. */
    public fun record(
        event: String,
        properties: Map<String, String>,
        session: TelemetrySessionContext,
    )
}
