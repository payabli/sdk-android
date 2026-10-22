package com.payabli.sdk.core.network

import androidx.annotation.RestrictTo
import kotlinx.serialization.KSerializer

/**
 * A pending HTTP request assembled by the SDK.
 *
 * Resolved against the environment base URL by the transport, which also attaches auth headers.
 * [query] is an ordered list rather than a map because query strings may repeat a key.
 *
 * @property route the route *template* for logs and telemetry, for example
 *   `/api/v2/MoneyIn/capture/{id}`. [path] is resolved and may embed an identifier, so it is never
 *   loggable. Supplying a template here is what lets the transport record which endpoint was called.
 * @property isCredentialPinned whether this request is bound to the exact credential it carries, so
 *   replacing that credential breaks the binding. Credential recovery is skipped: no refresh, no
 *   replay, and the rejection reaches the caller as it arrived. It is a property of the route rather
 *   than of a response. A route that answers something other than a 401 today is still pinned, and its
 *   rejection becomes one the day that changes.
 * @property isCallerRefusal whether a response refuses this caller, which a new credential cannot change. Such a
 *   rejection reaches the caller as it arrived and never ends the session.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class PayabliRequest(
    public val method: HttpMethod,
    public val path: String,
    public val route: String? = null,
    public val query: List<Pair<String, String>> = emptyList(),
    public val headers: Map<String, String> = emptyMap(),
    public val body: ByteArray? = null,
    public val isCredentialPinned: Boolean = false,
    public val isCallerRefusal: (PayabliResponse) -> Boolean = { false },
) {
    /**
     * Never includes headers, body, or the resolved [path] — a path may embed an identifier, which is why
     * [route] exists. `toString` reaches exception messages and diagnostics, which the logger cannot redact.
     *
     * @return the method and the route template, or `[REDACTED]` when no template was supplied.
     */
    override fun toString(): String = "PayabliRequest(${method.wireName} ${route ?: "[REDACTED]"})"

    public companion object {
        public const val CONTENT_TYPE_HEADER: String = "Content-Type"
        public const val APPLICATION_JSON: String = "application/json"

        /**
         * The header a money-moving request names its attempt by, so a repeat is recognizable as one.
         *
         * This spelling, not `Idempotency-Key`. Here rather than in a capability module because both the
         * card-not-present and card-present routes send it, and a wire constant spelled by hand in two
         * modules is one that drifts.
         */
        public const val IDEMPOTENCY_KEY_HEADER: String = "idempotencyKey"

        /**
         * Convenience for JSON bodies, adding `Content-Type: application/json`.
         *
         * [bodySerializer] is explicit for the same reason [PayabliTransport.execute] takes one: a
         * reified variant would resolve the serializer reflectively at runtime.
         *
         * There is no [query] here. A route needing one alongside a JSON body uses the constructor, which
         * is also what a route whose body must be zeroized has to do, since this encodes to a `String`.
         *
         * The parameter list mirrors the constructor's, and every call site names its arguments.
         *
         * @param method the verb the request carries.
         * @param path the resolved path, identifiers embedded. Never logged, which is what [route] is for.
         * @param body the value serialized as the request's JSON body.
         * @param bodySerializer the generated serializer that encodes [body].
         * @param route the template a log may carry, or null for none.
         * @param headers extra headers sent with the request.
         * @param isCredentialPinned whether the request is bound to the credential it carries, skipping
         *   credential recovery on a refusal.
         * @param isCallerRefusal whether a response refuses this caller, which a new credential cannot
         *   change, so such a rejection is returned as it arrived rather than recovered from.
         * @return the request carrying [body] encoded as JSON, with the content type set.
         */
        @SuppressWarnings("kotlin:S107")
        public fun <T> json(
            method: HttpMethod,
            path: String,
            body: T,
            bodySerializer: KSerializer<T>,
            route: String? = null,
            headers: Map<String, String> = emptyMap(),
            isCredentialPinned: Boolean = false,
            isCallerRefusal: (PayabliResponse) -> Boolean = { false },
        ): PayabliRequest =
            PayabliRequest(
                method = method,
                path = path,
                route = route,
                headers = headers + (CONTENT_TYPE_HEADER to APPLICATION_JSON),
                body = PayabliJson.format.encodeToString(bodySerializer, body).toByteArray(Charsets.UTF_8),
                isCredentialPinned = isCredentialPinned,
                isCallerRefusal = isCallerRefusal,
            )
    }
}
