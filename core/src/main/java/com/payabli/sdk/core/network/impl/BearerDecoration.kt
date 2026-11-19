package com.payabli.sdk.core.network.impl

import com.payabli.sdk.core.auth.PayabliAuth
import com.payabli.sdk.core.model.PayabliErrorType
import com.payabli.sdk.core.model.PayabliGenericException
import com.payabli.sdk.core.network.PayabliRequest
import kotlinx.coroutines.currentCoroutineContext

private const val AUTHORIZATION_HEADER = "Authorization"
private const val BEARER_PREFIX = "Bearer "
internal const val REASON_NOT_READY = "no access token is ready to send, and this request does not obtain one"

/**
 * Stamps `Authorization: Bearer <token>`, read per request so a replay after a refresh carries the new one.
 * A chain step, so no `:core` caller reaches an undecorated transport by accident.
 * A request sending on the held credential only is refused when none is held, and never starts a provider call.
 */
internal class BearerDecoration(
    private val auth: PayabliAuth,
) : PayabliRequestDecoration {
    override suspend fun decorate(request: PayabliRequest): PayabliRequest {
        val token =
            if (request.sendsOnHeldCredentialOnly) {
                auth.heldToken() ?: throw PayabliGenericException(PayabliErrorType.MISSING_TOKEN, REASON_NOT_READY)
            } else {
                auth.accessToken()
            }
        // Record what actually goes on the wire, so a rejection names this token and not an earlier read.
        currentCoroutineContext()[SentToken]?.value = token
        return request.withHeaders(mapOf(AUTHORIZATION_HEADER to BEARER_PREFIX + token))
    }
}
