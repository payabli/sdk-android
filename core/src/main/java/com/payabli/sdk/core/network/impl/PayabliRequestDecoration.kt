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

/**
 * Left to right: index 0 runs first, and a later step sees an earlier one's output.
 *
 * @param request the request the chain starts from.
 * @return the request the last step returned.
 */
internal suspend fun List<PayabliRequestDecoration>.applyTo(request: PayabliRequest): PayabliRequest =
    fold(request) { acc, decoration -> decoration.decorate(acc) }

/**
 * Merges [extra] over the request's own headers, with [extra] winning. Case-insensitive, and the
 * caller's key is removed rather than shadowed: leaving a differently-cased duplicate would let
 * iteration order decide which value reaches the wire.
 *
 * @param extra the headers that win, their names in any case.
 * @return the request carrying the merged headers.
 */
internal fun PayabliRequest.withHeaders(extra: Map<String, String>): PayabliRequest {
    if (extra.isEmpty()) return this
    val merged = LinkedHashMap<String, String>(headers.size + extra.size)
    val overridden = extra.keys.map { it.lowercase() }.toSet()
    headers.forEach { (name, value) -> if (name.lowercase() !in overridden) merged[name] = value }
    merged.putAll(extra)
    return copyWith(headers = merged)
}

/**
 * Replaces the body, for a step that contributes a body field rather than a header.
 *
 * @param body the body the returned request carries, or null to send none.
 * @return the request carrying [body].
 */
internal fun PayabliRequest.withBody(body: ByteArray?): PayabliRequest = copyWith(body = body)

/**
 * The one place a decorated request is rebuilt. Centralised so a new [PayabliRequest] property cannot be
 * silently dropped by a decoration that hand-rolled the copy.
 *
 * @param headers the headers the rebuilt request carries.
 * @param body the body the rebuilt request carries.
 * @return the request rebuilt with the named parts replaced.
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
        isCallerRefusal = isCallerRefusal,
    )
