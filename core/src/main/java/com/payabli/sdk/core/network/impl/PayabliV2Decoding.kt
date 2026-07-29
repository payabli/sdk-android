package com.payabli.sdk.core.network.impl

import com.payabli.sdk.core.model.PayabliErrorCode
import com.payabli.sdk.core.model.PayabliGenericException
import com.payabli.sdk.core.network.PayabliHttpErrors
import com.payabli.sdk.core.network.PayabliJson
import com.payabli.sdk.core.network.PayabliResponse
import com.payabli.sdk.core.network.PayabliV2Envelope
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException

internal const val REASON_DECODE_FAILED: String = "Failed to decode response envelope"

/**
 * Maps the status, then decodes the v2 envelope. Status first, so a proxy's HTML error page becomes a typed
 * error rather than a decode failure.
 *
 * Shared because `AuthenticatedTransport` cannot delegate to the base's overload: that one maps a 401 before
 * the wrapper could recover it.
 */
internal fun <T> PayabliResponse.asV2Envelope(payloadSerializer: KSerializer<T>): PayabliV2Envelope<T> {
    PayabliHttpErrors.from(this)?.let { throw it }
    return try {
        PayabliJson.format.decodeFromString(PayabliV2Envelope.serializer(payloadSerializer), bodyAsText())
    } catch (e: SerializationException) {
        // SerializationException extends IllegalArgumentException; catching the supertype would swallow
        // genuine programming errors raised from inside a serializer.
        // RedactedCause, not e: the message would carry the response body verbatim.
        throw PayabliGenericException(
            PayabliErrorCode.DECODING_ERROR,
            REASON_DECODE_FAILED,
            cause = RedactedCause(e),
        )
    }
}
