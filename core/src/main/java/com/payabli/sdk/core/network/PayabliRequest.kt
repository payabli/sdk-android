package com.payabli.sdk.core.network

import androidx.annotation.RestrictTo
import kotlinx.serialization.KSerializer

/**
 * A pending HTTP request assembled by the SDK.
 *
 * Resolved against the environment base URL by the transport, which also attaches auth headers.
 * [query] is an ordered list rather than a map because query strings may repeat a key.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class PayabliRequest(
    public val method: HttpMethod,
    public val path: String,
    /**
     * The route *template* for logs and telemetry, for example `/api/v2/MoneyIn/capture/{id}`.
     *
     * [path] is resolved and may embed an identifier, so it is never loggable. Supplying a template
     * here is what lets the transport record which endpoint was called.
     */
    public val route: String? = null,
    public val query: List<Pair<String, String>> = emptyList(),
    public val headers: Map<String, String> = emptyMap(),
    public val body: ByteArray? = null,
    /**
     * The service binds this request to the exact credential it carries, so replacing that credential
     * breaks the binding. Credential recovery is skipped: no refresh, no replay, and the rejection reaches
     * the caller as it arrived.
     *
     * It comes from the route's contract with the service. A route that answers something other than a 401
     * today is still pinned, and its rejection becomes one the day the service starts sending it.
     */
    public val isCredentialPinned: Boolean = false,
) {
    /**
     * Never includes headers, body, or the resolved [path] — a path may embed an identifier, which is why
     * [route] exists. `toString` reaches exception messages and diagnostics, which the logger cannot redact.
     */
    override fun toString(): String = "PayabliRequest(${method.wireName} ${route ?: "[REDACTED]"})"

    public companion object {
        public const val CONTENT_TYPE_HEADER: String = "Content-Type"
        public const val APPLICATION_JSON: String = "application/json"

        /**
         * Convenience for JSON bodies, adding `Content-Type: application/json`.
         *
         * [bodySerializer] is explicit for the same reason [PayabliTransport.execute] takes one: a
         * reified variant would resolve the serializer reflectively at runtime.
         */
        public fun <T> json(
            method: HttpMethod,
            path: String,
            body: T,
            bodySerializer: KSerializer<T>,
            route: String? = null,
            query: List<Pair<String, String>> = emptyList(),
            headers: Map<String, String> = emptyMap(),
            isCredentialPinned: Boolean = false,
        ): PayabliRequest =
            PayabliRequest(
                method = method,
                path = path,
                route = route,
                query = query,
                headers = headers + (CONTENT_TYPE_HEADER to APPLICATION_JSON),
                body = PayabliJson.format.encodeToString(bodySerializer, body).toByteArray(Charsets.UTF_8),
                isCredentialPinned = isCredentialPinned,
            )
    }
}
