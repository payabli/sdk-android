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
 * Implementations must be safe to call from any coroutine on any dispatcher, concurrently. Shared state is
 * allowed, and both implementations hold some: the bearer comes from `PayabliAuth`, which `PayabliService`
 * reaches through its decoration chain and `AuthenticatedTransport` holds directly. What the contract
 * requires is that any such state be thread-safe in its own right, as that holder is. Kotlin cannot express
 * this in the type system, so it is a documented contract that every implementation honours.
 *
 * Failures throw rather than returning a result type, the only shape that composes with coroutine
 * cancellation. Implementations throw `PayabliException`; a non-2xx *response* is not a failure and
 * comes back from [execute] intact, for the caller to pass through [PayabliHttpErrors]. That is what keeps
 * status interpretation out of the transport, and `PayabliService`, the implementation a capability writes
 * against, honours it without exception.
 *
 * **One carve-out, and only for credential recovery.** `AuthenticatedTransport` does not hand back a
 * credential rejection at all. It consumes the first one: refresh, then either replay the request and return
 * the replay's response, or, where the method makes a replay unsafe, return the original instead. A second
 * consecutive rejection becomes `TOKEN_EXPIRED`, because by then the only remedy has been tried and failed,
 * so the status carries nothing a caller could act on.
 *
 * **So do not expect to observe an initial 401.** The single case where the original response surfaces is a
 * widened, non-401 rejection on a method that cannot be safely replayed. Anything that is not a credential
 * rejection comes back intact, always.
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
