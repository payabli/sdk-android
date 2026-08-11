package com.payabli.sdk.core.network.impl

import com.payabli.sdk.core.network.PayabliRequest

/**
 * Gives a request that carries a body the JSON media type, unless it already names one.
 *
 * Every Payabli request body is JSON, and the transport sets no header of its own: it forwards what the
 * request carries, and `HttpURLConnection` sends an unset content type as form encoding. So a body built
 * without [PayabliRequest.json] reached the service labelled as a form.
 *
 * **A default, not an override, which makes it the opposite of [BearerDecoration].** A step that must win
 * replaces the caller's header; this one steps aside, so a request that names its own type keeps it and a
 * future form-encoded or multipart body needs no exemption here.
 */
internal class JsonBodyDecoration : PayabliRequestDecoration {
    override suspend fun decorate(request: PayabliRequest): PayabliRequest {
        if (request.body == null || request.namesContentType()) return request
        return request.withHeaders(
            mapOf(PayabliRequest.CONTENT_TYPE_HEADER to PayabliRequest.APPLICATION_JSON),
        )
    }

    /** RFC 9110 header names are case-insensitive, so `content-type` counts as naming one. */
    private fun PayabliRequest.namesContentType(): Boolean =
        headers.keys.any { it.equals(PayabliRequest.CONTENT_TYPE_HEADER, ignoreCase = true) }
}
