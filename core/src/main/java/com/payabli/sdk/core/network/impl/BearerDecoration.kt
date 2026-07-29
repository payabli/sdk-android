package com.payabli.sdk.core.network.impl

import com.payabli.sdk.core.auth.PayabliAuth
import com.payabli.sdk.core.network.PayabliRequest
import kotlinx.coroutines.currentCoroutineContext

private const val AUTHORIZATION_HEADER = "Authorization"
private const val BEARER_PREFIX = "Bearer "

/**
 * Stamps `Authorization: Bearer <token>` onto every outbound request.
 *
 * A chain step rather than a wrapping transport, so no `:core` caller reaches an undecorated transport by
 * accident. Guards a mistake, not an adversary: the boundary is `internal`, which Java does not honour.
 *
 * Reads the token per request, which is what lets a replay after a refresh carry the new one.
 */
internal class BearerDecoration(
    private val auth: PayabliAuth,
) : PayabliRequestDecoration {
    override suspend fun decorate(request: PayabliRequest): PayabliRequest {
        val token = auth.accessToken()
        // Record what actually goes on the wire, so a rejection names this token and not an earlier read.
        currentCoroutineContext()[SentToken]?.value = token
        return request.withHeaders(mapOf(AUTHORIZATION_HEADER to BEARER_PREFIX + token))
    }
}
