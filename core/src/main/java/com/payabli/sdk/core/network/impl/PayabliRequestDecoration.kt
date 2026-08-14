package com.payabli.sdk.core.network.impl

import com.payabli.sdk.core.network.PayabliRequest

/**
 * One step in the chain applied to every outbound request.
 *
 * Request in, request out: the widest shape that is still a pure function. A step that has to contribute
 * a *body* field rather than a header returns a request with a new body, so the interface does not have
 * to change for it.
 *
 * `internal` rather than annotated: nothing outside `:core` implements one, and a sibling module cannot
 * even name the type. `suspend` because a later step will sign, and signing reaches a key store.
 */
internal fun interface PayabliRequestDecoration {
    suspend fun decorate(request: PayabliRequest): PayabliRequest
}

/** Left to right: index 0 runs first, and a later step sees an earlier one's output. */
internal suspend fun List<PayabliRequestDecoration>.applyTo(request: PayabliRequest): PayabliRequest =
    fold(request) { acc, decoration -> decoration.decorate(acc) }

/**
 * Merges [extra] over the request's own headers, with [extra] winning.
 *
 * Case-insensitive, and the caller's key is **removed** rather than shadowed. Both halves matter: RFC 9110
 * header names are case-insensitive and `setRequestProperty` replaces case-insensitively, so leaving a
 * differently-cased duplicate in the map would let iteration order decide which value reaches the wire.
 * A decoration losing to a caller's header would be a bypass by another name.
 */
internal fun PayabliRequest.withHeaders(extra: Map<String, String>): PayabliRequest {
    if (extra.isEmpty()) return this
    val merged = LinkedHashMap<String, String>(headers.size + extra.size)
    val overridden = extra.keys.map { it.lowercase() }.toSet()
    headers.forEach { (name, value) -> if (name.lowercase() !in overridden) merged[name] = value }
    merged.putAll(extra)
    return copyWith(headers = merged)
}

/** Replaces the body, for a step that contributes a body field rather than a header. */
internal fun PayabliRequest.withBody(body: ByteArray?): PayabliRequest = copyWith(body = body)

/**
 * The one place a decorated request is rebuilt. Centralised so a new [PayabliRequest] property cannot be
 * silently dropped by a decoration that hand-rolled the copy.
 */
private fun PayabliRequest.copyWith(
    headers: Map<String, String> = this.headers,
    body: ByteArray? = this.body,
): PayabliRequest =
    PayabliRequest(
        method = method,
        path = path,
        route = route,
        query = query,
        headers = headers,
        body = body,
        isCredentialPinned = isCredentialPinned,
    )
