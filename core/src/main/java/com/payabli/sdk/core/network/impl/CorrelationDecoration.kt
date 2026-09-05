package com.payabli.sdk.core.network.impl

import com.payabli.sdk.core.network.PayabliRequest

private const val CORRELATION_HEADER = "X-Correlation-ID"

/**
 * Stamps `X-Correlation-ID` onto every outbound request, one value per request.
 *
 * Scopes one HTTP request, which is what makes it none of the identifiers already on the wire. An
 * idempotency key scopes one logical operation and must stay the same across its retries, so the two
 * cannot be the same value: one has to change on every send and the other must not.
 *
 * Minted per call rather than per instance, which is what makes a replay and a retry distinguishable: the
 * chain is re-applied on every entry to [PayabliService.execute], and the layers that resend wrap it.
 *
 * The SDK sends this and reads nothing back.
 */
internal class CorrelationDecoration : PayabliRequestDecoration {
    override suspend fun decorate(request: PayabliRequest): PayabliRequest =
        request.withHeaders(mapOf(CORRELATION_HEADER to UuidV7.next().toString()))
}
