package com.payabli.sdk.core.network

import androidx.annotation.RestrictTo
import kotlinx.serialization.KSerializer

/**
 * The transport seam every endpoint client depends on.
 *
 * The single choke-point every outbound request passes through. Decorations are applied inside the
 * implementation rather than by a wrapping layer, so there is no undecorated path.
 *
 * **`PayabliService` is the only implementation that reaches the network.** A second one would bypass the
 * choke-point. Test fakes are fine; a second real transport is not.
 *
 * Retry is deliberately *not* here. A decoration is a function over a request; retry is control flow
 * that decides whether the whole operation runs again, so it wraps a call to this seam rather than
 * living inside it. See `RetryPolicy`.
 *
 * Implementations must be safe to call from any coroutine on any dispatcher and must hold no mutable
 * shared state. Kotlin cannot express that in the type system, so it is a documented contract that
 * every implementation honours.
 *
 * Failures throw rather than returning a result type, the only shape that composes with coroutine
 * cancellation. Implementations throw `PayabliException`; a non-2xx *response* is not a failure and
 * comes back from [execute] intact, for the caller to pass through [PayabliHttpErrors].
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public interface PayabliTransport {
    /** Executes [request] and returns the raw response. Throws on transport failure. */
    public suspend fun execute(request: PayabliRequest): PayabliResponse

    /**
     * Executes [request] and decodes a [PayabliV2Envelope] around [T].
     *
     * [payloadSerializer] is passed explicitly rather than reified. A reified variant could only
     * obtain a serializer through `serializer(typeOf<T>())`, which resolves it reflectively at
     * runtime: that puts an R8 keep obligation on every payload type declared in a sibling module,
     * which `:core` cannot author keep rules for, and reflective JSON mapping is barred by the
     * dependency policy. Callers pass the generated serializer, e.g.
     * `execute(request, InitiateData.serializer())`.
     *
     * Legacy-envelope routes use [execute] and decode via [PayabliEnvelope] themselves; there is no
     * runtime dispatch between the two envelope shapes, the call site chooses.
     */
    public suspend fun <T> execute(
        request: PayabliRequest,
        payloadSerializer: KSerializer<T>,
    ): PayabliV2Envelope<T>
}
